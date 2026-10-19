using System.Net;
using System.Text.Json.Nodes;
using Wave.Core.Auth;
using Wave.Core.Music;
using Wave.Core.Net;
using Wave.Core.Stream;

namespace Wave.Core.Tests;

/// <summary>ログイン切れの扱い（SPEC「切れたと確かめられたときだけ切れたと扱う」、ADR-0004。Android の WaveSessionTest と同じ分け方）。</summary>
public class SessionHealthTests
{
    private const string AccountHeader = """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"me"}]}}}}}}}]}""";
    private const string LoggedOutMenu = """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[]}}}}]}""";

    /// <summary>エンドポイントごとに決めた応答を返す。</summary>
    private sealed class Server : HttpMessageHandler
    {
        public Dictionary<string, Func<HttpResponseMessage>> Routes { get; } = [];
        public List<string> Calls { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel)
        {
            var endpoint = request.RequestUri!.AbsolutePath.Replace("/youtubei/v1/", "");
            lock (Calls) Calls.Add(endpoint);
            return Task.FromResult(Routes.TryGetValue(endpoint, out var make) ? make() : new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{}") });
        }

        public int CountOf(string endpoint)
        {
            lock (Calls) return Calls.Count(c => c == endpoint);
        }
    }

    private static HttpResponseMessage Json(string body, HttpStatusCode status = HttpStatusCode.OK) => new(status) { Content = new StringContent(body) };

    private static (InnerTubeClient Client, Server Server, Func<long> Now, Action<long> Advance) LoggedIn()
    {
        var server = new Server();
        long now = 0;
        var client = new InnerTubeClient(new HttpClient(server), "ja", "JP", () => now);
        client.SetCredentials(Credentials.FromLogin("SAPISID=abc; SID=x", null, null, 0));
        return (client, server, () => now, ms => now += ms);
    }

    private static async Task Settled(InnerTubeClient client)
    {
        if (client.PendingConfirm is { } pending) await pending;
    }

    [Fact]
    public async Task NetworkFailureDoesNotExpireTheLogin()
    {
        var (client, server, _, _) = LoggedIn();
        server.Routes["account/account_menu"] = () => throw new HttpRequestException("offline");
        var repo = new MusicRepository(client);
        var result = await repo.AccountAsync();
        Assert.Equal(FailureKind.Network, result.Failure);
        Assert.Equal(SessionStatus.Active, client.Session.Status);
    }

    [Fact]
    public async Task Unauthorized401ExpiresAtOnce()
    {
        var (client, server, _, _) = LoggedIn();
        server.Routes["browse"] = () => Json("{}", HttpStatusCode.Unauthorized);
        var result = await client.PostAsync("browse", new JsonObject());
        Assert.Equal(FailureKind.NotLoggedIn, result.Failure);
        Assert.Equal(SessionStatus.Expired, client.Session.Status);
    }

    [Fact]
    public async Task Forbidden403IsOnlySuspectWhenTheAccountIsStillThere()
    {
        var (client, server, _, _) = LoggedIn();
        server.Routes["browse"] = () => Json("{}", HttpStatusCode.Forbidden);
        server.Routes["account/account_menu"] = () => Json(AccountHeader);
        var states = new List<SessionStatus>();
        client.Session.Changed += states.Add;
        await client.PostAsync("browse", new JsonObject());
        await Settled(client);
        // 疑い → 確かめてアカウントのヘッダがあったので元に戻る
        Assert.Equal([SessionStatus.Suspect, SessionStatus.Active], states);
        Assert.Equal(SessionStatus.Active, client.Session.Status);
    }

    [Fact]
    public async Task SuspectBecomesExpiredOnlyWhenTheConfirmShowsLoggedOut()
    {
        var (client, server, _, _) = LoggedIn();
        server.Routes["browse"] = () => Json("{}", HttpStatusCode.Forbidden);
        server.Routes["account/account_menu"] = () => Json(LoggedOutMenu);
        await client.PostAsync("browse", new JsonObject());
        await Settled(client);
        Assert.Equal(SessionStatus.Expired, client.Session.Status);
    }

    [Fact]
    public async Task ConfirmRedirectedToSignInExpires()
    {
        var (client, server, _, _) = LoggedIn();
        server.Routes["browse"] = () => Json("{}", HttpStatusCode.Forbidden);
        server.Routes["account/account_menu"] = () =>
        {
            var moved = new HttpResponseMessage(HttpStatusCode.Found);
            moved.Headers.Location = new Uri("https://accounts.google.com/ServiceLogin");
            return moved;
        };
        await client.PostAsync("browse", new JsonObject());
        await Settled(client);
        Assert.Equal(SessionStatus.Expired, client.Session.Status);
    }

    [Fact]
    public async Task UnclearConfirmKeepsSuspectAndConfirmsAtMostEveryThirtySeconds()
    {
        var (client, server, _, advance) = LoggedIn();
        server.Routes["browse"] = () => Json("{}", HttpStatusCode.Forbidden);
        server.Routes["account/account_menu"] = () => Json("{}");
        await client.PostAsync("browse", new JsonObject());
        await Settled(client);
        await client.PostAsync("browse", new JsonObject());
        await Settled(client);
        // 中身の分からない応答では切れたことにしない。確かめは30秒に1回まで
        Assert.Equal(SessionStatus.Suspect, client.Session.Status);
        Assert.Equal(1, server.CountOf("account/account_menu"));
        advance(SessionHealth.ConfirmIntervalMs);
        await client.PostAsync("browse", new JsonObject());
        await Settled(client);
        Assert.Equal(2, server.CountOf("account/account_menu"));
    }

    [Fact]
    public async Task SongLoginRequiredConfirmsInTheBackgroundWithoutChangingTheState()
    {
        var (client, server, _, _) = LoggedIn();
        var states = new List<SessionStatus>();
        client.Session.Changed += states.Add;
        server.Routes["account/account_menu"] = () => Json(AccountHeader);
        client.RequestConfirm(client.Generation);
        await Settled(client);
        Assert.Equal(1, server.CountOf("account/account_menu"));
        Assert.Empty(states);
        Assert.Equal(SessionStatus.Active, client.Session.Status);

        // 本当に外れていたときだけ切れた扱いにする
        var (other, otherServer, _, _) = LoggedIn();
        otherServer.Routes["account/account_menu"] = () => Json(LoggedOutMenu);
        other.RequestConfirm(other.Generation);
        await Settled(other);
        Assert.Equal(SessionStatus.Expired, other.Session.Status);
    }

    [Fact]
    public async Task SignInPromptOnALoggedInBrowseIsSuspectNotExpired()
    {
        var (client, server, _, _) = LoggedIn();
        server.Routes["browse"] = () => Json("""{"contents":{"messageRenderer":{"text":{"runs":[{"text":"sign in"}]}}}}""");
        server.Routes["account/account_menu"] = () => Json("{}");
        var repo = new MusicRepository(client);
        var result = await repo.RecentActivityAsync();
        await Settled(client);
        Assert.Equal(FailureKind.NotLoggedIn, result.Failure);
        Assert.Equal(SessionStatus.Suspect, client.Session.Status);
    }

    [Fact]
    public async Task OldGenerationResultsDoNotTouchTheNewLogin()
    {
        var (client, _, _, _) = LoggedIn();
        var old = client.Generation;
        client.SetCredentials(Credentials.FromLogin("SAPISID=def; SID=y", null, null, 1));
        client.Session.Expire(old);
        Assert.Equal(SessionStatus.Active, client.Session.Status);
        client.SetCredentials(null);
        Assert.Equal(SessionStatus.LoggedOut, client.Session.Status);
        await Task.CompletedTask;
    }

    [Fact]
    public void ReadingTheAccountClearsExpiredButTheConfirmOnlyClearsSuspect()
    {
        var health = new SessionHealth(() => 0);
        health.Reset(1, loggedIn: true);
        health.Expire(1);
        health.Proven(1, fromConfirm: true);
        Assert.Equal(SessionStatus.Expired, health.Status);
        health.Proven(1, fromConfirm: false);
        Assert.Equal(SessionStatus.Active, health.Status);
    }
}

/// <summary>拒否が続く曲を少しの間解決し直さない（SPEC「拒否が続く曲は少しの間解決し直さない」。Android の ResolvedStreamCache と同じ2分）。</summary>
public class RejectedSongsTests
{
    [Fact]
    public void RejectedSongsCoolDownForTwoMinutesPerLogin()
    {
        long now = 0;
        var rejected = new RejectedSongs(() => now);
        rejected.MarkRejected("a", 1);
        Assert.True(rejected.RecentlyRejected("a", 1));
        Assert.False(rejected.RecentlyRejected("b", 1));
        now = RejectedSongs.CooldownMs - 1;
        Assert.True(rejected.RecentlyRejected("a", 1));
        now = RejectedSongs.CooldownMs;
        Assert.False(rejected.RecentlyRejected("a", 1));
        // ログイン・ログアウト（世代が変わる）で忘れる
        rejected.MarkRejected("a", 1);
        Assert.False(rejected.RecentlyRejected("a", 2));
    }
}

/// <summary>解決の失敗のうち、飛ばしてよいのはその曲だけの失敗（SPEC 54・58。Android の PlaybackDecisions と同じ考え）。</summary>
public class StreamFailuresTests
{
    [Theory]
    [InlineData(StreamFailure.Unplayable, true)]
    [InlineData(StreamFailure.NoAudioFormat, true)]
    [InlineData(StreamFailure.SongLoginRequired, true)]
    [InlineData(StreamFailure.NotLoggedIn, false)]
    [InlineData(StreamFailure.Network, false)]
    [InlineData(StreamFailure.Http, false)]
    [InlineData(StreamFailure.Parse, false)]
    [InlineData(StreamFailure.PlayerConfigUnavailable, false)]
    [InlineData(StreamFailure.SabrOnly, false)]
    [InlineData(StreamFailure.DecipherFailed, false)]
    [InlineData(StreamFailure.RejectedUrl, false)]
    public void OnlyThisSongFailuresAreSkipped(StreamFailure failure, bool skip) => Assert.Equal(skip, StreamFailures.IsThisSongOnly(failure));
}
