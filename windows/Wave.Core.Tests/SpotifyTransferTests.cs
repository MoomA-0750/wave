using System.IO.Compression;
using System.Text.Json.Nodes;
using Wave.Core.Music;
using Wave.Core.Net;
using Wave.Core.Transfer;

namespace Wave.Core.Tests;

/// <summary>Spotify から移す: データのダウンロードの読み込み・Web API の応答・突き合わせ・書き込みの進み具合。</summary>
public class SpotifyTransferTests
{
    private static string SpotifyDir => Path.Combine(AppContext.BaseDirectory, "Fixtures", "spotify");

    private static JsonNode Api(string name) => JsonNode.Parse(File.ReadAllText(Path.Combine(SpotifyDir, name + ".json")))!;

    // --- データのダウンロード ---

    [Fact]
    public void ExportFolderIsReadTolerantly()
    {
        var lib = SpotifyExportReader.ReadPath(SpotifyDir);
        // Playlist1（小文字のキー）と Playlist2（大文字で始まるキー）。Playlist3 は壊れていて読めない
        Assert.Equal(["wave の移行の試し", "もう1つ"], lib.Playlists.Select(p => p.Name));
        var first = lib.Playlists[0];
        Assert.Equal(["Mela!", "Shout Baby", "夏慕"], first.Tracks.Select(t => t.Title));
        // 手元のファイルの曲は URI から読む（長さも）
        Assert.Equal(["緑黄色社会"], first.Tracks[2].Artists);
        Assert.Equal(260_000, first.Tracks[2].DurationMs);
        Assert.Equal("Mela!", lib.Playlists[1].Tracks.Single().Title);
        Assert.Equal(["Mela!", "sabotage", "キャラクター"], lib.LikedTracks.Select(t => t.Title));
        Assert.Null(lib.LikedTracks[0].DurationMs);
        Assert.Equal("SINGALONG", lib.Albums.Single().Title);
        Assert.Equal(["緑黄色社会"], lib.Albums.Single().Artists);
        Assert.Equal("緑黄色社会", lib.Artists.Single().Name);
        // 読めなかったもの: 壊れたファイル、曲名の無い項目、エピソード
        Assert.Contains(new ImportProblem("Playlist3.json", ImportProblemKind.Unreadable), lib.Problems);
        Assert.Contains(new ImportProblem("YourLibrary.json", ImportProblemKind.SkippedItems, 1), lib.Problems);
        Assert.Contains(new ImportProblem("Playlist1.json", ImportProblemKind.SkippedEpisodes, 1), lib.Problems);
    }

    [Fact]
    public void ExportZipIsReadAndOtherFilesAreIgnored()
    {
        var dir = Directory.CreateTempSubdirectory("wave-spotify-");
        try
        {
            var zipPath = Path.Combine(dir.FullName, "my_spotify_data.zip");
            using (var zip = ZipFile.Open(zipPath, ZipArchiveMode.Create))
            {
                foreach (var name in new[] { "YourLibrary.json", "Playlist1.json" })
                    zip.CreateEntryFromFile(Path.Combine(SpotifyDir, name), "Spotify Account Data/" + name);
                using var w = new StreamWriter(zip.CreateEntry("Spotify Account Data/StreamingHistory_music_0.json").Open());
                w.Write("[{\"trackName\":\"x\"}]");
            }
            var lib = SpotifyExportReader.ReadPath(zipPath);
            Assert.Single(lib.Playlists);
            Assert.Equal(3, lib.LikedTracks.Count);
            Assert.DoesNotContain(lib.Problems, p => p.File.StartsWith("StreamingHistory"));
        }
        finally
        {
            dir.Delete(true);
        }
    }

    [Fact]
    public void SingleJsonWithAnotherNameIsReadByItsContents()
    {
        var lib = SpotifyExportReader.ReadJson("renamed.json", "{\"tracks\":[{\"artistName\":\"緑黄色社会\",\"trackName\":\"Mela!\"}]}");
        Assert.Equal("Mela!", lib.LikedTracks.Single().Title);
        var unknown = SpotifyExportReader.ReadJson("Userdata.json", "{\"username\":\"x\"}");
        Assert.True(unknown.IsEmpty);
        Assert.Equal(ImportProblemKind.UnknownShape, unknown.Problems.Single().Kind);
    }

    // --- Web API ---

    [Fact]
    public void ApiPagesAreRead()
    {
        var (playlists, next) = SpotifyApiParsers.Playlists(Api("api_playlists"));
        Assert.Equal(3, playlists.Count);
        Assert.Equal("me", playlists[0].OwnerId);
        Assert.True(playlists[2].Collaborative);
        Assert.NotNull(next);

        var (tracks, episodes, skipped, itemsNext) = SpotifyApiParsers.PlaylistItems(Api("api_playlist_items"));
        var mela = Assert.Single(tracks);
        Assert.Equal(245_000, mela.DurationMs);
        Assert.Equal("JPXX02000001", mela.Isrc);
        Assert.Equal("Actor", mela.Album);
        Assert.Equal(1, episodes);
        Assert.Equal(1, skipped);
        Assert.Null(itemsNext);

        var saved = SpotifyApiParsers.SavedTracks(Api("api_saved_tracks"));
        Assert.Equal("sabotage", saved.Tracks.Single().Title);
        Assert.NotNull(saved.Next);
        Assert.Equal("SINGALONG", SpotifyApiParsers.SavedAlbums(Api("api_saved_albums")).Albums.Single().Title);
        var artists = SpotifyApiParsers.FollowedArtists(Api("api_followed_artists"));
        Assert.Equal(2, artists.Artists.Count);
        Assert.NotNull(artists.Next);
    }

    [Fact]
    public void PkceAndRedirect()
    {
        // RFC 7636 の付録 B の例
        Assert.Equal("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SpotifyAuth.Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        var verifier = SpotifyAuth.CreateVerifier();
        Assert.InRange(verifier.Length, 43, 128);
        Assert.DoesNotContain('=', verifier);
        var url = SpotifyAuth.AuthorizeUrl("abc", "http://127.0.0.1:5123/callback", "ch", "st");
        Assert.Contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5123%2Fcallback", url);
        Assert.Contains("code_challenge_method=S256", url);
        Assert.Contains("scope=playlist-read-private%20playlist-read-collaborative%20user-library-read%20user-follow-read", url);
        // 戻りを受けるポートと、登録してもらう戻り先がそろっている（違うと Spotify が断る）
        Assert.Equal($"http://127.0.0.1:{SpotifyAuth.CallbackPort}/callback", SpotifyAuth.RegisteredRedirectUri);
        using (var receiver = new LoopbackReceiver(0)) Assert.StartsWith("http://127.0.0.1:", receiver.RedirectUri);
        var q = LoopbackReceiver.ParseQuery("/callback?code=a%2Bb&state=xyz");
        Assert.Equal("a+b", q["code"]);
        Assert.Equal("xyz", q["state"]);
        Assert.Equal(TimeSpan.FromSeconds(7), SpotifyApiParsers.RetryAfter("7"));
        Assert.Equal(TimeSpan.FromSeconds(5), SpotifyApiParsers.RetryAfter(null));
        Assert.Equal(TimeSpan.FromSeconds(60), SpotifyApiParsers.RetryAfter("3600"));
        var token = SpotifyAuth.ParseToken(JsonNode.Parse("{\"access_token\":\"t\",\"expires_in\":3600}"), "old", 1000);
        Assert.Equal("old", token!.RefreshToken);
        Assert.Equal(3_601_000, token.ExpiresAtMs);
    }

    // --- 突き合わせ ---

    private static Song S(string id, string title, string artist, string? album = null, int? seconds = null, string? kind = Song.KindSong) =>
        new(id, title, [new ArtistRef(artist, null)], album is null ? null : new AlbumRef(album, null), seconds, null, kind);

    [Fact]
    public void FoldIgnoresCaseWidthAndKana()
    {
        Assert.Equal(TransferMatcher.Fold("キャラクター"), TransferMatcher.Fold("きゃらくたー"));
        Assert.Equal(TransferMatcher.Fold("ＭＥＬＡ！"), TransferMatcher.Fold("mela"));
        Assert.Equal(TransferMatcher.Fold("ｷｬﾗｸﾀｰ"), TransferMatcher.Fold("キャラクター"));
        Assert.Equal("Song", TransferMatcher.StripDecorations("Song (feat. Someone)"));
        Assert.Equal("Song", TransferMatcher.StripDecorations("Song - Remastered 2011"));
        Assert.Equal("Song", TransferMatcher.StripDecorations("Song - 2011 Remaster"));
        Assert.Equal("Song", TransferMatcher.StripDecorations("Song [Remastered]"));
        Assert.Equal("Song", TransferMatcher.StripDecorations("Song feat. Someone"));
        Assert.Equal("Song (Live)", TransferMatcher.StripDecorations("Song (Live)"));
        Assert.True(TransferMatcher.DifferentScripts("緑黄色社会", "Ryokuoushoku Shakai"));
        Assert.False(TransferMatcher.DifferentScripts("Official髭男dism", "Official HIGE DANdism"));
    }

    [Fact]
    public void TheOfficialSongWinsOverVideosAndOtherSongs()
    {
        var track = new SpotifyTrack("Mela!", ["緑黄色社会"], "Actor");
        var ranked = TransferMatcher.RankSongs(track,
        [
            S("mv", "Mela!", "緑黄色社会", null, null, Song.KindMusicVideo),
            S("other", "sabotage", "緑黄色社会", "SINGALONG"),
            S("atv", "Mela!", "緑黄色社会", "Actor"),
            S("ugc", "Mela! 歌ってみた", "誰か", null, null, Song.KindUserVideo),
        ]);
        Assert.Equal("atv", ranked[0].Id);
        Assert.Equal(MatchStatus.Matched, TransferMatcher.StatusOf(ranked));
        Assert.True(ranked.Single(c => c.Id == "other").Score < TransferMatcher.AutoThreshold);
        Assert.True(ranked.Single(c => c.Id == "ugc").Score < TransferMatcher.AutoThreshold);
    }

    [Fact]
    public void ACoverByAnArtistInAnotherScriptIsNotChosenAutomatically()
    {
        // YouTube の検索の上位に、同じ曲名の別の人のカバー（ローマ字の名前）が来ても、合うアーティストの曲を選ぶ
        var track = new SpotifyTrack("Mela!", ["緑黄色社会"], "Actor");
        var ranked = TransferMatcher.RankSongs(track, [S("cover", "Mela!", "World Relaxation Project", "紅白"), S("right", "Mela!", "緑黄色社会", "Mela!")]);
        Assert.Equal("right", ranked[0].Id);
        Assert.Equal(MatchStatus.Matched, TransferMatcher.StatusOf(ranked));
        // カバーしか無ければ、選んではおくが要確認
        var onlyCover = TransferMatcher.RankSongs(track, [S("cover", "Mela!", "World Relaxation Project", "紅白")]);
        Assert.Equal(MatchStatus.Review, TransferMatcher.StatusOf(onlyCover));
        // 英語の題の同名の曲（Beastie Boys の Sabotage）も同じ
        var sabotage = TransferMatcher.RankSongs(new SpotifyTrack("sabotage", ["緑黄色社会"], "SINGALONG"), [S("bb", "Sabotage (Remastered 2009)", "Beastie Boys", "Ill Communication")]);
        Assert.Equal(MatchStatus.Review, TransferMatcher.StatusOf(sabotage));
    }

    [Fact]
    public void DurationSeparatesVersionsWhenKnown()
    {
        var track = new SpotifyTrack("Mela!", ["緑黄色社会"], null, DurationMs: 245_000);
        var ranked = TransferMatcher.RankSongs(track, [S("long", "Mela!", "緑黄色社会", null, 400), S("right", "Mela!", "緑黄色社会", null, 246)]);
        Assert.Equal("right", ranked[0].Id);
        Assert.Equal(1, TransferMatcher.DurationScore(200_000, 200));
        Assert.Null(TransferMatcher.DurationScore(null, 200));
    }

    [Fact]
    public void RomajiArtistIsNotTreatedAsAMismatch()
    {
        // Spotify がローマ字、YouTube が漢字でも、曲名が合えば自動で選ぶ（アーティストは「分からない」として扱う）
        var track = new SpotifyTrack("Mela!", ["Ryokuoushoku Shakai"], "Actor");
        var ranked = TransferMatcher.RankSongs(track, [S("atv", "Mela!", "緑黄色社会", "Actor")]);
        Assert.Equal(MatchStatus.Matched, TransferMatcher.StatusOf(ranked));
        // アーティストの登録は名前しか無いので、文字の種類が違えば要確認にする
        var artists = TransferMatcher.RankArtists(new SpotifyArtist("Ryokuoushoku Shakai"), [new ArtistSummary("UC1", "緑黄色社会", null, null)]);
        Assert.Equal(MatchStatus.Review, TransferMatcher.StatusOf(artists));
        var same = TransferMatcher.RankArtists(new SpotifyArtist("緑黄色社会"), [new ArtistSummary("UC1", "緑黄色社会", "アーティスト", null)]);
        Assert.Equal(MatchStatus.Matched, TransferMatcher.StatusOf(same));
        Assert.Equal(MatchStatus.NotFound, TransferMatcher.StatusOf([]));
    }

    [Fact]
    public void AlbumsUseTheArtistInTheSubtitle()
    {
        var album = new SpotifyAlbum("SINGALONG", ["緑黄色社会"]);
        var ranked = TransferMatcher.RankAlbums(album,
        [
            new AlbumSummary("MPREb_other", "SINGALONG", "アルバム • 別の人 • 2019", null),
            new AlbumSummary("MPREb_right", "SINGALONG", "アルバム • 緑黄色社会 • 2020", null),
        ]);
        Assert.Equal("MPREb_right", ranked[0].Id);
        Assert.Equal(MatchStatus.Matched, TransferMatcher.StatusOf(ranked));
    }

    // --- 移す作業 ---

    private static TransferSession Sample() => TransferSession.From(SpotifyExportReader.ReadPath(SpotifyDir), "export", "spotify", 0);

    [Fact]
    public void SessionMergesTheSameSongAndPlansOnlyWhatIsChosen()
    {
        var s = Sample();
        // Mela! は2つのプレイリストと高く評価した曲にあるが、突き合わせは1回
        Assert.Single(s.Items, i => i.Title == "Mela!");
        var mela = s.Items.Single(i => i.Title == "Mela!");
        mela.SetCandidates([new MatchCandidate("v-mela", "Mela!", "緑黄色社会", null, 0.95)]);
        var sabotage = s.Items.Single(i => i.Title == "sabotage");
        sabotage.SetCandidates([new MatchCandidate("v-sabo", "sabotage", "緑黄色社会", null, 0.5)]);
        Assert.Equal(MatchStatus.Review, sabotage.Status);
        Assert.Equal("v-sabo", sabotage.ChosenId);
        var chara = s.Items.Single(i => i.Title == "キャラクター");
        chara.SetCandidates([new MatchCandidate("v-chara", "キャラクター", "緑黄色社会", null, 0.9)]);
        chara.Excluded = true;
        s.Items.Single(i => i.Title == "Shout Baby").SetCandidates([]);

        var plan = TransferPlan.Of(s);
        Assert.Equal(["v-mela"], plan.Playlists[0].VideoIds);
        Assert.Equal(["v-mela"], plan.Playlists[1].VideoIds);
        // 高く評価は古い順（一覧の逆）。外した曲・見つからない曲は入れない
        Assert.Equal(["v-sabo", "v-mela"], plan.Likes);

        s.IncludeLiked = false;
        Assert.Empty(TransferPlan.Of(s).Likes);
        s.Done[TransferSession.PlaylistOp(0)] = WriteOutcome.Written;
        Assert.Single(TransferPlan.Of(s).Playlists);

        // 保存して読み直しても同じ
        var again = TransferSession.FromJson(s.ToJson())!;
        Assert.Equal("v-sabo", again.Items.Single(i => i.Title == "sabotage").Chosen!.Id);
        Assert.True(again.IsDone(TransferSession.PlaylistOp(0)));
        Assert.Contains(again.Problems, p => p.Kind == ImportProblemKind.Unreadable);
    }

    [Fact]
    public void ManualChoiceSurvivesAndIsAddedToTheCandidates()
    {
        var item = new TransferItem { Key = "k", Title = "x" };
        item.SetCandidates([]);
        Assert.Equal(MatchStatus.NotFound, item.Status);
        item.Excluded = true;
        item.Choose(new MatchCandidate("picked", "x", "y", null, 0.3));
        Assert.False(item.Excluded);
        Assert.Equal("picked", item.Target);
        item.SetCandidates([new MatchCandidate("auto", "x", "y", null, 0.9)]);
        Assert.Equal("picked", item.Target);
    }

    [Fact]
    public void PlaylistTitleMarksOnlyWhenTheSameNameExists()
    {
        Assert.Equal("ドライブ", TransferRunner.PlaylistTitle("ドライブ", ["作業用", "Chill"]));
        Assert.Equal("ドライブ (from Spotify)", TransferRunner.PlaylistTitle("ドライブ", ["作業用", "ドライブ"]));
        Assert.Equal("Chill (from Spotify)", TransferRunner.PlaylistTitle("Chill", [" chill "]));
        Assert.Equal("ドライブ (from Spotify)", TransferRunner.PlaylistTitle("ドライブ", [], existingUnknown: true));
    }

    private sealed class FakeWriter : ITransferWriter
    {
        public readonly Dictionary<string, List<string>> Playlists = [];
        public readonly HashSet<string> Liked = ["already-liked"];
        public readonly List<string> Calls = [];
        public int FailSearches;
        public bool LoggedOut;
        public int CancelAfterAdds = -1;
        public CancellationTokenSource? Cancel;

        public Task<ApiResult<IReadOnlyList<Song>>> SearchSongsAsync(string query, CancellationToken cancel)
        {
            Calls.Add("search:" + query);
            if (FailSearches-- > 0) return Task.FromResult(ApiResult<IReadOnlyList<Song>>.Failed(FailureKind.Network));
            if (ThrottleSearches-- > 0) return Task.FromResult(ApiResult<IReadOnlyList<Song>>.Failed(FailureKind.NotLoggedIn, 403));
            var title = query.Split(' ', 2).Last();
            return Task.FromResult(ApiResult<IReadOnlyList<Song>>.Ok([S("v-" + title, title, "緑黄色社会")]));
        }

        public Task<ApiResult<IReadOnlyList<Song>>> SearchTopSongsAsync(string query, CancellationToken cancel) =>
            Task.FromResult(ApiResult<IReadOnlyList<Song>>.Ok([]));

        public Task<ApiResult<IReadOnlyList<AlbumSummary>>> SearchAlbumsAsync(string query, CancellationToken cancel) =>
            Task.FromResult(ApiResult<IReadOnlyList<AlbumSummary>>.Ok([new AlbumSummary("MPREb_1", "SINGALONG", "アルバム • 緑黄色社会", null)]));

        public Task<ApiResult<IReadOnlyList<ArtistSummary>>> SearchArtistsAsync(string query, CancellationToken cancel) =>
            Task.FromResult(ApiResult<IReadOnlyList<ArtistSummary>>.Ok([new ArtistSummary("UC1", "緑黄色社会", null, null)]));

        public Task<ApiResult<string>> CreatePlaylistAsync(string title, IReadOnlyList<string> videoIds, CancellationToken cancel)
        {
            if (LoggedOut) return Task.FromResult(ApiResult<string>.Failed(FailureKind.NotLoggedIn));
            var id = "PL" + Playlists.Count;
            Playlists[id] = [.. videoIds.Distinct()];
            Calls.Add("create:" + title);
            return Task.FromResult(ApiResult<string>.Ok(id));
        }

        public HashSet<string>? LibraryTitles = [];
        public int ThrottleSearches;
        public bool LoginLost;

        public Task<bool> LoginLostAsync(CancellationToken cancel) => Task.FromResult(LoginLost || LoggedOut);

        public Task<HashSet<string>?> LibraryPlaylistTitlesAsync(CancellationToken cancel) =>
            Task.FromResult(LibraryTitles is null ? null : new HashSet<string>(LibraryTitles));

        public Task<ApiResult<IReadOnlyList<Song>>> AlbumSongsAsync(string browseId, CancellationToken cancel)
        {
            Calls.Add("album-songs:" + browseId);
            return Task.FromResult(ApiResult<IReadOnlyList<Song>>.Ok([
                new Song("a-1", "Shout Baby", [new ArtistRef("緑黄色社会", "UC1")], new AlbumRef("SINGALONG", browseId), 264, null, Song.KindSong),
                new Song("a-2", "sabotage", [new ArtistRef("緑黄色社会", "UC1")], new AlbumRef("SINGALONG", browseId), 239, null, Song.KindSong),
                new Song("a-3", "Mela!", [new ArtistRef("緑黄色社会", "UC1")], new AlbumRef("SINGALONG", browseId), 243, null, Song.KindSong),
            ]));
        }

        public Task<HashSet<string>?> PlaylistVideoIdsAsync(string playlistId, CancellationToken cancel) =>
            Task.FromResult<HashSet<string>?>(Playlists.TryGetValue(playlistId, out var l) ? [.. l] : null);

        /// <summary>この曲が混ざった追加は全部断る（YouTube で足せない曲）。</summary>
        public string? Unaddable;

        public Task<bool> AddToPlaylistAsync(string playlistId, IReadOnlyList<string> videoIds, CancellationToken cancel)
        {
            Calls.Add("add:" + videoIds.Count);
            if (Unaddable is not null && videoIds.Contains(Unaddable)) return Task.FromResult(false);
            foreach (var v in videoIds)
                if (!Playlists[playlistId].Contains(v)) Playlists[playlistId].Add(v);
            if (CancelAfterAdds-- == 0) Cancel!.Cancel();
            return Task.FromResult(true);
        }

        public Task<HashSet<string>?> LikedVideoIdsAsync(CancellationToken cancel) => Task.FromResult<HashSet<string>?>([.. Liked]);

        public Task<bool?> IsLikedAsync(string videoId, CancellationToken cancel) => Task.FromResult<bool?>(Liked.Contains(videoId));

        public Task<ApiResult<bool>> SetLikedAsync(string videoId, CancellationToken cancel)
        {
            Calls.Add("like:" + videoId);
            Liked.Add(videoId);
            return Task.FromResult(ApiResult<bool>.Ok(true));
        }

        public Task<ApiResult<(bool? Saved, string? SavePlaylistId)>> AlbumSaveStateAsync(string browseId, CancellationToken cancel) =>
            Task.FromResult(ApiResult<(bool?, string?)>.Ok((false, "OLAK5uy_1")));

        public Task<ApiResult<bool>> SaveAlbumAsync(string savePlaylistId, CancellationToken cancel)
        {
            Calls.Add("save:" + savePlaylistId);
            return Task.FromResult(ApiResult<bool>.Ok(true));
        }

        public Task<ApiResult<(bool? Subscribed, string? ChannelId)>> ArtistSubscriptionAsync(string browseId, CancellationToken cancel) =>
            Task.FromResult(ApiResult<(bool?, string?)>.Ok((true, "UC1")));

        public Task<ApiResult<bool>> SubscribeAsync(string channelId, CancellationToken cancel)
        {
            Calls.Add("subscribe:" + channelId);
            return Task.FromResult(ApiResult<bool>.Ok(true));
        }
    }

    private static TransferRunner Runner(FakeWriter w, List<int>? saves = null) =>
        new(w, _ => saves?.Add(1), (_, c) => { c.ThrowIfCancellationRequested(); return Task.CompletedTask; });

    [Fact]
    public async Task MatchWaitsAndContinuesWhenYouTubeRefusesButTheLoginIsAlive()
    {
        var s = Sample();
        var w = new FakeWriter { ThrottleSearches = 2 };
        var runner = Runner(w);
        var waits = new List<int>();
        runner.Progress += p => { if (p.WaitSeconds is { } sec) waits.Add(sec); };
        Assert.Equal(TransferStop.None, await runner.MatchAsync(s, default));
        Assert.DoesNotContain(s.ItemsInScope(), i => i.Status == MatchStatus.Pending);
        Assert.Equal([60, 120], waits);
    }

    [Fact]
    public async Task SongsOfTheSameAlbumAreMatchedFromTheAlbumPage()
    {
        // 同じアルバムの曲が3曲: アルバムを1回探してページを1回読み、曲ごとには探さない。ページに無い曲だけ1曲ずつ探す
        var tracks = new[] { "Shout Baby", "sabotage", "Mela!", "存在しない曲" }
            .Select(t => new SpotifyTrack(t, ["緑黄色社会"], "SINGALONG")).ToList();
        var s = TransferSession.From(new SpotifyLibrary([], tracks, [], [], []), "api", "spotify", 0);
        var w = new FakeWriter();
        var runner = Runner(w);
        Assert.Equal(TransferStop.None, await runner.MatchAsync(s, default));
        Assert.Equal(1, w.Calls.Count(c => c.StartsWith("album-songs:")));
        Assert.Equal(3, runner.MatchedByAlbum);
        Assert.Equal(["search:緑黄色社会 存在しない曲"], w.Calls.Where(c => c.StartsWith("search:")).ToList());
        Assert.Equal("a-2", s.Items.Single(i => i.Title == "sabotage").Target);
    }

    [Fact]
    public async Task MatchRestsAfterEachBatch()
    {
        var s = Sample();
        var waits = new List<TimeSpan>();
        var runner = new TransferRunner(new FakeWriter(), _ => { }, (t, _) => { waits.Add(t); return Task.CompletedTask; })
        {
            SearchGap = TimeSpan.FromSeconds(1),
            BatchSize = 2,
            BatchPause = TimeSpan.FromSeconds(60),
        };
        Assert.Equal(TransferStop.None, await runner.MatchAsync(s, default));
        var n = s.ItemsInScope().Count;
        Assert.Equal(n / 2, waits.Count(w => w == TimeSpan.FromSeconds(60)));
    }

    [Fact]
    public async Task MatchStopsWhenTheLoginIsReallyLost()
    {
        var s = Sample();
        var w = new FakeWriter { ThrottleSearches = 100, LoginLost = true };
        Assert.Equal(TransferStop.NotLoggedIn, await Runner(w).MatchAsync(s, default));
    }

    [Fact]
    public async Task MatchRetriesAfterAFailureAndSearchesEachSongOnce()
    {
        var s = Sample();
        var w = new FakeWriter { FailSearches = 2 };
        Assert.Equal(TransferStop.None, await Runner(w).MatchAsync(s, default));
        Assert.All(s.ItemsInScope(), i => Assert.NotEqual(MatchStatus.Pending, i.Status));
        Assert.Equal(1, w.Calls.Count(c => c == "search:緑黄色社会 Mela!") - 2);
        Assert.Equal("v-Mela!", s.Items.Single(i => i.Title == "Mela!").Target);
    }

    [Fact]
    public async Task MatchStopsWhenTheNetworkKeepsFailing()
    {
        var s = Sample();
        var w = new FakeWriter { FailSearches = 100 };
        Assert.Equal(TransferStop.Network, await Runner(w).MatchAsync(s, default));
        Assert.All(s.ItemsInScope(), i => Assert.Equal(MatchStatus.Pending, i.Status));
    }

    [Fact]
    public async Task WriteMarksPlaylistsWhoseNameIsAlreadyInTheLibrary()
    {
        var s = Sample();
        var w = new FakeWriter { LibraryTitles = ["もう1つ"] };
        await Runner(w).MatchAsync(s, default);
        Assert.Equal(TransferStop.None, await Runner(w).WriteAsync(s, default));
        Assert.Contains("create:wave の移行の試し", w.Calls);
        Assert.Contains("create:もう1つ (from Spotify)", w.Calls);
    }

    [Fact]
    public async Task WriteSkipsWhatIsAlreadyThereAndResumesWithoutDuplicates()
    {
        var s = Sample();
        var w = new FakeWriter();
        await Runner(w).MatchAsync(s, default);
        s.Items.Single(i => i.Title == "sabotage").Choose(new MatchCandidate("already-liked", "sabotage", "", null, 1));

        // 1つ目のプレイリストに足している途中で止める
        using var cancel = new CancellationTokenSource();
        w.Cancel = cancel;
        w.CancelAfterAdds = 0;
        // 作るときに渡す曲を減らして、足す処理を通す
        s.Playlists[0].CreatedPlaylistId = (await w.CreatePlaylistAsync(s.Playlists[0].Name, ["v-Mela!"], default)).Value;
        Assert.Equal(TransferStop.Cancelled, await Runner(w).WriteAsync(s, cancel.Token));
        Assert.False(s.IsDone(TransferSession.PlaylistOp(0)));

        // 続きから: もう1つ作らず、足りない曲だけを足す
        w.Cancel = null;
        Assert.Equal(TransferStop.None, await Runner(w).WriteAsync(s, default));
        Assert.Equal(2, w.Calls.Count(c => c.StartsWith("create:")));
        Assert.Equal(["v-Mela!", "v-Shout Baby", "v-夏慕"], w.Playlists["PL0"]);
        Assert.Equal(["v-Mela!"], w.Playlists["PL1"]);
        Assert.Equal(WriteOutcome.Written, s.Done[TransferSession.PlaylistOp(0)]);
        // 前から高く評価していた曲は書かない
        Assert.Equal(WriteOutcome.Already, s.Done[TransferSession.LikeOp("already-liked")]);
        Assert.DoesNotContain("like:already-liked", w.Calls);
        Assert.Contains("like:v-Mela!", w.Calls);
        Assert.Contains("save:OLAK5uy_1", w.Calls);
        // 登録済みのアーティストは登録し直さない
        Assert.DoesNotContain(w.Calls, c => c.StartsWith("subscribe:"));
        Assert.Equal(WriteOutcome.Already, s.Done[TransferSession.ArtistOp("UC1")]);

        // もう一度行っても何も書かない
        var calls = w.Calls.Count(c => !c.StartsWith("search:"));
        Assert.True(TransferPlan.Of(s).IsEmpty);
        await Runner(w).WriteAsync(s, default);
        Assert.Equal(calls, w.Calls.Count(c => !c.StartsWith("search:")));
        var summary = TransferRunner.Summarize(s);
        Assert.Equal(2, summary.Playlists);
        Assert.Equal(0, summary.Failed);
    }

    [Fact]
    public async Task AFailedBatchFallsBackToOneByOneAndCountsWhatIsMissing()
    {
        var s = Sample();
        var w = new FakeWriter { Unaddable = "v-Shout Baby" };
        await Runner(w).MatchAsync(s, default);
        s.IncludeLiked = s.IncludeAlbums = s.IncludeArtists = false;
        s.Playlists[1].Include = false;
        s.Playlists[0].CreatedPlaylistId = (await w.CreatePlaylistAsync(s.Playlists[0].Name, ["v-Mela!"], default)).Value;
        Assert.Equal(TransferStop.None, await Runner(w).WriteAsync(s, default));
        // まとめて（2曲）を4回断られたあと、1曲ずつ足す。足せない1曲だけが残る
        Assert.Equal(["v-Mela!", "v-夏慕"], w.Playlists["PL0"]);
        Assert.Equal(1, s.Playlists[0].Missing);
        Assert.Equal(WriteOutcome.Failed, s.Done[TransferSession.PlaylistOp(0)]);
        Assert.Equal(1, TransferRunner.Summarize(s).PlaylistMissing);
    }

    [Fact]
    public async Task WriteStopsWhenSignedOut()
    {
        var s = Sample();
        var w = new FakeWriter { LoggedOut = true };
        await Runner(w).MatchAsync(s, default);
        Assert.Equal(TransferStop.NotLoggedIn, await Runner(w).WriteAsync(s, default));
        Assert.Null(s.Playlists[0].CreatedPlaylistId);
    }
}
