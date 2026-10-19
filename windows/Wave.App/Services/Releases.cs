using Microsoft.UI.Dispatching;
using Wave.Core.Music;

namespace Wave.App.Services;

/// <summary>
/// 登録したアーティストの新作（Android の Releases と同じ）。アプリが動いている間、起動の少し後と6時間おきに確かめ、
/// 見つけたら Windows の通知で知らせる。最近の新作はホームとアーティストのページに出す。
/// </summary>
public sealed class Releases(DispatcherQueue ui)
{
    private static readonly string FilePath = Path.Combine(AppPaths.Data, "release-watch.json");
    private static readonly TimeSpan Interval = TimeSpan.FromHours(6);
    private const int MaxArtists = 300;
    private const int MaxNotifications = 10;

    /// <summary>新作を通知するのに要る、そのアーティストの高く評価した曲の数。</summary>
    private const int MinLikedToNotify = 3;
    private DispatcherQueueTimer? timer;
    private bool checking;

    /// <summary>最近の新作（新しい順）。</summary>
    public IReadOnlyList<NewRelease> Recent { get; private set; } = [];

    public event Action? Changed;

    private static ReleaseWatchState Read()
    {
        try
        {
            return File.Exists(FilePath) ? ReleaseWatchState.Decode(File.ReadAllText(FilePath)) ?? ReleaseWatchState.Empty : ReleaseWatchState.Empty;
        }
        catch (IOException)
        {
            return ReleaseWatchState.Empty;
        }
    }

    /// <summary>保存してある最近の新作を読み、定期の確かめを始める。通知を押したらそのアルバム（動画ならアーティスト）を開く。</summary>
    public void Start()
    {
        Recent = Read().Expire(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()).Recent;
        Toasts.Initialize();
        Toasts.Activated += launch => ui.TryEnqueue(() => Open(launch));
        // 開発用: データの場所に toast-test を置いて起動すると、最近の新作の先頭で通知を1回試す（置いた印は消す）
        var test = Path.Combine(AppPaths.Data, "toast-test");
        if (File.Exists(test) && Recent.FirstOrDefault() is { } sample)
        {
            File.Delete(test);
            Notify(sample);
        }
        timer = ui.CreateTimer();
        timer.Interval = TimeSpan.FromMinutes(1);
        timer.Tick += (_, _) =>
        {
            // 最初は起動の1分後、その後は6時間おき
            timer.Interval = Interval;
            _ = CheckAsync();
        };
        timer.Start();
    }

    private static void Open(string launch)
    {
        var args = launch.Split('&').Select(p => p.Split('=', 2)).Where(p => p.Length == 2).ToDictionary(p => p[0], p => Uri.UnescapeDataString(p[1]));
        if (args.TryGetValue("album", out var album)) Nav.Album(album);
        else if (args.TryGetValue("artist", out var artist)) Nav.Artist(artist);
        App.Window?.Activate();
    }

    /// <summary>登録したアーティストのページを順に見て、新作を探す（同時に1本だけ）。</summary>
    public async Task CheckAsync()
    {
        if (checking || !AppServices.Client.IsLoggedIn || !AppServices.Settings.ReleaseNotices) return;
        checking = true;
        try
        {
            var complete = true;
            var subscribed = await AppServices.Music.SubscribedArtistsAsync(() => complete = false);
            if (!subscribed.IsOk) return;
            var artists = subscribed.Value!.Take(MaxArtists).ToList();
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            // 登録をやめたアーティストの記録は、登録の一覧を最後まで読めたときだけ捨てる（途中のページで失敗すると欠けて返るため。Android と同じ）
            var state = Read().Expire(now);
            if (complete && subscribed.Value!.Count > 0) state = state.Retain(subscribed.Value!.Select(a => a.BrowseId).ToHashSet());
            var found = new List<NewRelease>();
            foreach (var artist in artists)
            {
                var page = await AppServices.Music.ArtistAsync(artist.BrowseId);
                if (page.IsOk)
                {
                    var (fresh, next) = state.Observe(artist.BrowseId, page.Value!, now, DateTime.Now.Year);
                    state = next;
                    found.AddRange(fresh);
                }
                // まとめて叩かない
                await Task.Delay(300);
            }
            await File.WriteAllTextAsync(FilePath, state.Encode());
            Recent = state.Recent;
            AppPaths.Log($"release watch artists={artists.Count} new={found.Count} recent={Recent.Count}");
            Changed?.Invoke();
            if (found.Count == 0) return;
            // 鳴らす通知は、よく聴くアーティスト（高く評価した曲が MinLikedToNotify 曲以上）の新作だけにする（Android と同じ。
            // Apple Music のお気に入りの新作の通知と同じ考え方）。ほかの新作はホームとアーティストのページの「新作」に並べるだけ
            var (liked, _) = await AppServices.Library.LikedSongsAsync();
            var favorites = found.Where(r => LibraryState.ByArtist(liked, r.ArtistId, r.ArtistName).Count >= MinLikedToNotify).ToList();
            AppPaths.Log($"release notify found={found.Count} favorites={favorites.Count} liked={liked.Count}");
            foreach (var release in favorites.Take(MaxNotifications)) Notify(release);
        }
        catch (Exception e) when (e is IOException or HttpRequestException)
        {
            AppPaths.Log($"release watch failed type={e.GetType().Name}");
        }
        finally
        {
            checking = false;
        }
    }

    private static async void Notify(NewRelease release)
    {
        var launch = release.AlbumBrowseId is { } album ? "album=" + Uri.EscapeDataString(album) : "artist=" + Uri.EscapeDataString(release.ArtistId);
        var title = release.VideoId is not null ? L.T($"{release.ArtistName} の新しい動画", $"New video from {release.ArtistName}") : L.T($"{release.ArtistName} の新作", $"New from {release.ArtistName}");
        Toasts.Show(title, release.Title, launch, await ImageFileAsync(release));
    }

    /// <summary>通知の画像（パッケージにしていないアプリの通知は URL を読めないので、ファイルに落とす）。</summary>
    private static async Task<string?> ImageFileAsync(NewRelease release)
    {
        if (release.ThumbnailUrl is not { } url || !url.StartsWith("https://", StringComparison.Ordinal)) return null;
        try
        {
            var dir = Directory.CreateDirectory(Path.Combine(AppPaths.Data, "toast-images")).FullName;
            var file = Path.Combine(dir, Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(release.Key)))[..16] + ".jpg");
            if (!File.Exists(file)) await File.WriteAllBytesAsync(file, await ImageHttp.GetByteArrayAsync(Ui.Resize(url, 256)));
            return new Uri(file).AbsoluteUri;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException)
        {
            return null;
        }
    }

    // 画像の取得は認証情報を持たないクライアントで
    private static readonly HttpClient ImageHttp = new(new SocketsHttpHandler { UseCookies = false }) { Timeout = TimeSpan.FromSeconds(15) };

    /// <summary>ログアウト・アカウントの切り替え。</summary>
    public void Clear()
    {
        try
        {
            File.Delete(FilePath);
            Directory.Delete(Path.Combine(AppPaths.Data, "toast-images"), recursive: true);
        }
        catch (DirectoryNotFoundException)
        {
        }
        catch (IOException)
        {
        }
        Recent = [];
        Changed?.Invoke();
    }
}
