using System.Net;
using System.Text.Json.Nodes;
using Wave.Core.Auth;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.Core.Tests;

/// <summary>日本語と英語の2つの応答（2026-11-07 に取って、使わない欄を落としたもの）から、元の表記を選ぶ（Android の OriginalTitlesTest と同じ確かめ方）。</summary>
public class OriginalTitlesTests
{
    private static OriginalTitles.Result Merged(string name, string lang, IReadOnlyDictionary<string, string>? known = null) =>
        OriginalTitles.Merge(Fixtures.Json($"bilingual_{name}_{lang}"), Fixtures.Json($"bilingual_{name}_{OriginalTitles.OtherLanguage(lang)}"), lang, known);

    private const string Taylor = "UCPC0L1d253x-KuMNwa05TpA";

    /// <summary>曲の並びを比べられる文字にする（record の一覧は中身で比べないので）。</summary>
    private static string Dump(IEnumerable<Song> songs) =>
        string.Join("\n", songs.Select(s => $"{s.VideoId}|{s.Title}|{string.Join(",", s.Artists.Select(a => a.Name + "@" + a.BrowseId))}|{s.Album?.Title}"));

    private static string Dump(HomePage page) =>
        string.Join("\n", page.Shelves.Select(s => $"{s.Title}|{s.Strapline}:" + string.Join(";", s.Items.Select(i => $"{i.Key}|{i.Title}|{i.Subtitle}"))));

    [Fact]
    public void WesternSongsKeepTheirEnglishNamesInJapanese()
    {
        var songs = MusicParsers.SearchSongs(Merged("search_songs", "ja").Json).Songs;
        var artists = songs.SelectMany(s => s.Artists.Select(a => a.Name)).ToList();
        Assert.Contains("Taylor Swift", artists);
        Assert.DoesNotContain(artists, a => a.Contains("テイラー"));
        Assert.Contains(songs, s => s.Title == "All Too Well");
        // 英語の画面では、もともと英語なので変えない
        Assert.Equal(Dump(MusicParsers.SearchSongs(Fixtures.Json("bilingual_search_songs_en")).Songs), Dump(MusicParsers.SearchSongs(Merged("search_songs", "en").Json).Songs));
    }

    [Fact]
    public void JapaneseSongsKeepTheirJapaneseNamesInEnglish()
    {
        var album = MusicParsers.AlbumPage(Merged("album_yonezu", "en").Json, "MPREb_NnSQrGZDyMk");
        Assert.NotNull(album);
        Assert.Equal(["米津玄師"], album.Artists.Select(a => a.Name));
        var titles = album.Songs.Select(s => s.Title).ToList();
        Assert.Contains("アイネクライネ", titles);
        Assert.Contains("花に嵐", titles);
        Assert.DoesNotContain(titles, t => t.Contains(" - "));
        // 日本語の画面では、アルバムの曲はそのまま
        Assert.Equal(
            Dump(MusicParsers.AlbumPage(Fixtures.Json("bilingual_album_yonezu_ja"), "MPREb_NnSQrGZDyMk")!.Songs),
            Dump(MusicParsers.AlbumPage(Merged("album_yonezu", "ja").Json, "MPREb_NnSQrGZDyMk")!.Songs));
    }

    [Fact]
    public void ArtistPageUsesTheArtistsOwnNames()
    {
        var ja = Merged("artist_taylor", "ja");
        var page = MusicParsers.ArtistPage(ja.Json);
        Assert.NotNull(page);
        Assert.Equal("Taylor Swift", page.Name);
        Assert.Contains(page.TopSongs, s => s.Title == "Shake It Off");
        Assert.DoesNotContain(page.TopSongs, s => s.Title.Any(c => c is >= 'ァ' and <= 'ヶ'));
        Assert.Equal("en", ja.Learned.GetValueOrDefault(Taylor));
        // 英語の画面で、日本語の副題（「～気にしてなんかいられないっ！！」の漢字）を日本の曲と取り違えない
        var en = MusicParsers.ArtistPage(Merged("artist_taylor", "en").Json)!;
        Assert.Equal(Dump(MusicParsers.ArtistPage(Fixtures.Json("bilingual_artist_taylor_en"))!.TopSongs), Dump(en.TopSongs));
    }

    [Fact]
    public void QueueUsesWhatWasLearnedAboutTheArtist()
    {
        // キューの動画の行はアーティスト名（チャンネル名）が同じで題しか違わないので、覚えたことが無ければ変えない
        var plain = MusicParsers.UpNext(Merged("next_taylor", "ja").Json).Songs[0];
        Assert.StartsWith("シェイク", plain.Title);
        var learned = MusicParsers.UpNext(Merged("next_taylor", "ja", new Dictionary<string, string> { [Taylor] = "en" }).Json).Songs[0];
        Assert.Equal("Shake It Off", learned.Title);
        Assert.Equal("Shake It Off", MusicParsers.UpNext(Merged("next_taylor", "en").Json).Songs[0].Title);
    }

    [Fact]
    public void KatakanaJapaneseArtistsAndYouTubePlaylistsStay()
    {
        // サカナクション（カタカナ名の邦楽）は、曲名に日本の文字が残るので日本の曲と分かる。YouTube のプレイリスト名・棚の見出しは画面の言語のまま
        var en = MusicParsers.HomePage(Merged("home", "en").Json);
        var plain = MusicParsers.HomePage(Fixtures.Json("bilingual_home_en"));
        Assert.Equal(plain.Shelves.Select(s => s.Title), en.Shelves.Select(s => s.Title));
        // YouTube のプレイリストの補足（並ぶアーティストの名前）は画面の言語のまま
        var items = string.Join("\n", en.Shelves.SelectMany(s => s.Items).OfType<HomeItem.SongItem>().Select(i => i.Song.Title + " " + i.Song.Subtitle));
        Assert.Contains("サカナクション", items);
        Assert.Contains("夜の踊り子", items);
        Assert.DoesNotContain("Yoru No Odoriko", items);
        Assert.Contains("米津玄師", items);
        Assert.DoesNotContain("Kenshi Yonezu", items);
        Assert.Contains("Official髭男dism", items);
        var ja = MusicParsers.HomePage(Merged("home", "ja").Json);
        Assert.Equal(Dump(MusicParsers.HomePage(Fixtures.Json("bilingual_home_ja"))), Dump(ja));
    }

    [Fact]
    public void OtherLanguageIsTheOppositeOfTheScreen()
    {
        Assert.Equal("en", OriginalTitles.OtherLanguage("ja"));
        Assert.Equal("en", OriginalTitles.OtherLanguage("ja-JP"));
        Assert.Equal("ja", OriginalTitles.OtherLanguage("en-GB"));
    }

    // 設定の「曲名を元の表記で」: 入れたときだけ、検索を日本語と英語の2つで送って突き合わせる（Android の OriginalTitlesSessionTest と同じ）

    /// <summary>検索に、送られた言語の応答を返し、送られた言語を覚える。</summary>
    private sealed class Server(HttpStatusCode englishStatus = HttpStatusCode.OK) : HttpMessageHandler
    {
        public List<string> Languages { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel)
        {
            var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancel);
            var hl = JsonNode.Parse(body)?["context"]?["client"]?["hl"]?.GetValue<string>() ?? "";
            lock (Languages) Languages.Add(hl);
            if (!request.RequestUri!.AbsolutePath.EndsWith("/search")) return new HttpResponseMessage(HttpStatusCode.NotFound);
            if (hl == "en" && englishStatus != HttpStatusCode.OK) return new HttpResponseMessage(englishStatus);
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(Fixtures.Text(hl == "en" ? "bilingual_search_songs_en" : "bilingual_search_songs_ja")) };
        }
    }

    private static InnerTubeClient Client(Server server, bool originalTitles)
    {
        var client = new InnerTubeClient(new HttpClient(server), "ja", "JP") { OriginalTitles = originalTitles };
        client.SetCredentials(Credentials.FromLogin("SAPISID=synthetic-sapisid; SID=x", null, null, 0));
        return client;
    }

    private static List<string> Artists(ApiResult<SongPage> result)
    {
        Assert.True(result.IsOk);
        return result.Value!.Songs.SelectMany(s => s.Artists.Select(a => a.Name)).ToList();
    }

    [Fact]
    public async Task OffSendsOneRequestInTheScreenLanguage()
    {
        var server = new Server();
        var names = Artists(await new MusicRepository(Client(server, originalTitles: false)).SearchSongsAsync("Taylor Swift"));
        Assert.Equal(["ja"], server.Languages);
        Assert.Contains("テイラー・スウィフト", names);
    }

    [Fact]
    public async Task OnAsksInBothLanguagesAndKeepsTheOriginalNames()
    {
        var server = new Server();
        var names = Artists(await new MusicRepository(Client(server, originalTitles: true)).SearchSongsAsync("Taylor Swift"));
        Assert.Equal(["en", "ja"], server.Languages.Order());
        Assert.All(names, n => Assert.Equal("Taylor Swift", n));
    }

    [Fact]
    public async Task AFailedSecondLanguageFallsBackToTheScreenLanguage()
    {
        var server = new Server(HttpStatusCode.InternalServerError);
        var names = Artists(await new MusicRepository(Client(server, originalTitles: true)).SearchSongsAsync("Taylor Swift"));
        Assert.Contains("テイラー・スウィフト", names);
        // もう1つの言語が 401 でも、ログインの状態は画面の言語の方だけで決める
        var unauthorized = Client(new Server(HttpStatusCode.Unauthorized), originalTitles: true);
        Assert.Contains("テイラー・スウィフト", Artists(await new MusicRepository(unauthorized).SearchSongsAsync("Taylor Swift")));
        Assert.Equal(SessionStatus.Active, unauthorized.Session.Status);
    }
}
