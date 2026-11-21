using System.Text.Json;
using System.Text.Json.Serialization;

namespace Wave.Core.Transfer;

public enum TransferKind { Track, Album, Artist }

/// <summary>移す作業の段階（画面はこれで出し分ける。途中で閉じても、保存したところから続ける）。</summary>
public enum TransferStage { Loaded, Matching, Review, Writing, Finished }

/// <summary>書き込みの1件の結果。Written は wave が書いたもの、Already は前から YouTube Music にあったもの（書いていない）。</summary>
public enum WriteOutcome { Written, Already, Failed }

/// <summary>突き合わせる1件（同じ曲がいくつものプレイリストにあっても1件にまとめ、検索は1回だけ）。</summary>
public sealed class TransferItem
{
    public string Key { get; set; } = "";
    public TransferKind Kind { get; set; }
    public string Title { get; set; } = "";
    public List<string> Artists { get; set; } = [];
    public string? Album { get; set; }
    public int? DurationMs { get; set; }
    public string? Isrc { get; set; }
    public MatchStatus Status { get; set; }
    public List<MatchCandidate> Candidates { get; set; } = [];
    /// <summary>選んだ候補の ID（自動では一番点の高いもの。見つからなければ null）。</summary>
    public string? ChosenId { get; set; }
    /// <summary>本人が「外す」にした（移さない）。</summary>
    public bool Excluded { get; set; }
    /// <summary>本人が選び直した（自動の選び直しで上書きしない）。</summary>
    public bool Manual { get; set; }
    /// <summary>「Claude で探す」を行った。</summary>
    public bool AiTried { get; set; }
    /// <summary>選んでいる候補は Claude が選んだもの。</summary>
    public bool AiPicked { get; set; }
    /// <summary>Claude の選んだ理由・見つからないと考えた理由。</summary>
    public string? AiNote { get; set; }

    [JsonIgnore] public MatchCandidate? Chosen => ChosenId is null ? null : Candidates.FirstOrDefault(c => c.Id == ChosenId);

    /// <summary>移す先が決まっている（外していない・候補を選んでいる）。</summary>
    [JsonIgnore] public string? Target => Excluded ? null : Chosen?.Id;

    [JsonIgnore] public string ArtistsText => string.Join(", ", Artists);

    public SpotifyTrack AsTrack() => new(Title, Artists, Album, DurationMs, Isrc);
    public SpotifyAlbum AsAlbum() => new(Title, Artists);
    public SpotifyArtist AsArtist() => new(Title);

    /// <summary>候補を入れ、一番上を選ぶ（本人が選び直したものは選びを残す）。</summary>
    public void SetCandidates(List<MatchCandidate> candidates)
    {
        var keep = Manual ? Chosen : null;
        Status = TransferMatcher.StatusOf(candidates);
        Candidates = candidates;
        if (keep is not null && !Candidates.Any(c => c.Id == keep.Id)) Candidates.Insert(0, keep);
        ChosenId = keep?.Id ?? candidates.FirstOrDefault()?.Id;
        if (keep is null) AiPicked = false;
    }

    /// <summary>本人が候補を選んだ（手で検索して選んだものは候補の先頭に足す）。</summary>
    public void Choose(MatchCandidate candidate)
    {
        if (!Candidates.Any(c => c.Id == candidate.Id)) Candidates.Insert(0, candidate);
        ChosenId = candidate.Id;
        Excluded = false;
        Manual = true;
        AiPicked = false;
    }
}

public sealed class TransferPlaylist
{
    public string Name { get; set; } = "";
    public List<string> TrackKeys { get; set; } = [];
    /// <summary>中身を読めた（Web API で他人のプレイリストは false。名前だけ出す）。</summary>
    public bool Readable { get; set; } = true;
    /// <summary>本人が移すことにしている。</summary>
    public bool Include { get; set; } = true;
    /// <summary>YouTube Music に作ったプレイリスト（途中で止めても、続きはここへ足す。同じ名前でもう1つ作らない）。</summary>
    public string? CreatedPlaylistId { get; set; }
    /// <summary>入らなかった曲の数（最後に読み直したとき）。</summary>
    public int Missing { get; set; }
}

/// <summary>
/// Spotify から移す作業の全部（読んだもの・突き合わせ・本人の選び・書き込みの進み具合）。JSON にして保存し、途中から続ける。
/// 書き込みの進み具合は Done（操作の鍵 → 結果）で持ち、Written・Already の操作はもう一度行わない。
/// </summary>
public sealed class TransferSession
{
    public int Version { get; set; } = 1;
    /// <summary>"export"（データのダウンロード）か "api"（Web API）。</summary>
    public string Source { get; set; } = "export";
    public string? SourceName { get; set; }
    public long CreatedAtMs { get; set; }
    public TransferStage Stage { get; set; }
    public List<TransferItem> Items { get; set; } = [];
    public List<TransferPlaylist> Playlists { get; set; } = [];
    public List<string> LikedKeys { get; set; } = [];
    public List<string> AlbumKeys { get; set; } = [];
    public List<string> ArtistKeys { get; set; } = [];
    public bool IncludePlaylists { get; set; } = true;
    public bool IncludeLiked { get; set; } = true;
    public bool IncludeAlbums { get; set; } = true;
    public bool IncludeArtists { get; set; } = true;
    public List<ImportProblem> Problems { get; set; } = [];
    public Dictionary<string, WriteOutcome> Done { get; set; } = [];

    private Dictionary<string, TransferItem>? index;

    public TransferItem? Item(string key)
    {
        if (index is null || index.Count != Items.Count) index = Items.ToDictionary(i => i.Key);
        return index.GetValueOrDefault(key);
    }

    // 同じものの鍵は、Spotify の URI ではなく折りたたんだ名前で作る（データのダウンロードでは URI の無い項目があり、同じ曲が別の URI で入っていることもある）
    public static string TrackKey(SpotifyTrack t) =>
        "t:" + TransferMatcher.Fold(t.Title) + "|" + TransferMatcher.Fold(t.Artists.FirstOrDefault() ?? "") + "|" + TransferMatcher.Fold(t.Album ?? "");

    public static string AlbumKey(SpotifyAlbum a) => "a:" + TransferMatcher.Fold(a.Title) + "|" + TransferMatcher.Fold(a.Artists.FirstOrDefault() ?? "");

    public static string ArtistKey(SpotifyArtist a) => "r:" + TransferMatcher.Fold(a.Name);

    public static TransferSession From(SpotifyLibrary library, string source, string? sourceName, long nowMs)
    {
        var s = new TransferSession { Source = source, SourceName = sourceName, CreatedAtMs = nowMs, Problems = [.. library.Problems] };
        var items = new Dictionary<string, TransferItem>();
        string AddTrack(SpotifyTrack t)
        {
            var key = TrackKey(t);
            if (items.TryGetValue(key, out var known))
            {
                // 長さ・ISRC はどちらかにあれば使う
                known.DurationMs ??= t.DurationMs;
                known.Isrc ??= t.Isrc;
            }
            else items[key] = new TransferItem { Key = key, Kind = TransferKind.Track, Title = t.Title, Artists = [.. t.Artists], Album = t.Album, DurationMs = t.DurationMs, Isrc = t.Isrc };
            return key;
        }
        foreach (var p in library.Playlists)
            s.Playlists.Add(new TransferPlaylist { Name = p.Name, Readable = p.Readable, Include = p.Readable && p.Tracks.Count > 0, TrackKeys = p.Tracks.Select(AddTrack).ToList() });
        s.LikedKeys = library.LikedTracks.Select(AddTrack).Distinct().ToList();
        foreach (var a in library.Albums)
        {
            var key = AlbumKey(a);
            if (items.TryAdd(key, new TransferItem { Key = key, Kind = TransferKind.Album, Title = a.Title, Artists = [.. a.Artists] })) s.AlbumKeys.Add(key);
        }
        foreach (var a in library.Artists)
        {
            var key = ArtistKey(a);
            if (items.TryAdd(key, new TransferItem { Key = key, Kind = TransferKind.Artist, Title = a.Name })) s.ArtistKeys.Add(key);
        }
        s.Items = [.. items.Values];
        s.IncludePlaylists = s.Playlists.Any(p => p.Include);
        s.IncludeLiked = s.LikedKeys.Count > 0;
        s.IncludeAlbums = s.AlbumKeys.Count > 0;
        s.IncludeArtists = s.ArtistKeys.Count > 0;
        return s;
    }

    /// <summary>移すことにした種類・プレイリストに入っているもの（突き合わせる・見直す対象）。</summary>
    public List<TransferItem> ItemsInScope()
    {
        var keys = new HashSet<string>();
        if (IncludePlaylists)
            foreach (var p in Playlists.Where(p => p.Include && p.Readable)) keys.UnionWith(p.TrackKeys);
        if (IncludeLiked) keys.UnionWith(LikedKeys);
        if (IncludeAlbums) keys.UnionWith(AlbumKeys);
        if (IncludeArtists) keys.UnionWith(ArtistKeys);
        return Items.Where(i => keys.Contains(i.Key)).ToList();
    }

    /// <summary>その操作が済んでいる（書いた・前からあった）。失敗したものはもう一度行う。</summary>
    public bool IsDone(string opKey) => Done.TryGetValue(opKey, out var o) && o != WriteOutcome.Failed;

    public static string PlaylistOp(int index) => "playlist:" + index;
    public static string LikeOp(string videoId) => "like:" + videoId;
    public static string AlbumOp(string browseId) => "album:" + browseId;
    public static string ArtistOp(string browseId) => "artist:" + browseId;

    // --- 保存 ---

    private static readonly JsonSerializerOptions Options = new() { Converters = { new JsonStringEnumConverter() } };

    public string ToJson() => JsonSerializer.Serialize(this, Options);

    public static TransferSession? FromJson(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<TransferSession>(json, Options);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>一時ファイルに書いてから置き換える（書いている途中で落ちても前のものが残る）。</summary>
    public void Save(string path)
    {
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, ToJson());
        File.Move(tmp, path, overwrite: true);
    }

    public static TransferSession? Load(string path)
    {
        try
        {
            return File.Exists(path) ? FromJson(File.ReadAllText(path)) : null;
        }
        catch (IOException)
        {
            return null;
        }
    }
}

/// <summary>これから書くもの（書く前の確認と、書き込みの順）。</summary>
public sealed record PlannedPlaylist(int Index, string Name, IReadOnlyList<string> VideoIds, string? ExistingPlaylistId);

public sealed record TransferPlan(
    IReadOnlyList<PlannedPlaylist> Playlists,
    IReadOnlyList<string> Likes,
    IReadOnlyList<string> Albums,
    IReadOnlyList<string> Artists)
{
    public int PlaylistSongs => Playlists.Sum(p => p.VideoIds.Count);
    public bool IsEmpty => Playlists.Count == 0 && Likes.Count == 0 && Albums.Count == 0 && Artists.Count == 0;

    /// <summary>
    /// 選んだものから、まだ済んでいない書き込みを並べる。プレイリストの曲は同じ曲を1つにし、移す先の無い曲（外した・見つからない）は除く。
    /// 高く評価する曲は古い順（Spotify の一覧は新しい順なので逆にする。YouTube Music の一覧で新しいものが上に来る）。
    /// </summary>
    public static TransferPlan Of(TransferSession s)
    {
        var playlists = new List<PlannedPlaylist>();
        if (s.IncludePlaylists)
        {
            for (var i = 0; i < s.Playlists.Count; i++)
            {
                var p = s.Playlists[i];
                if (!p.Include || !p.Readable || s.IsDone(TransferSession.PlaylistOp(i))) continue;
                var ids = p.TrackKeys.Select(k => s.Item(k)?.Target).Where(id => id is not null).Select(id => id!).Distinct().ToList();
                if (ids.Count > 0) playlists.Add(new PlannedPlaylist(i, p.Name, ids, p.CreatedPlaylistId));
            }
        }
        List<string> Targets(bool include, IEnumerable<string> keys, Func<string, string> op) => !include ? [] :
            keys.Select(k => s.Item(k)?.Target).Where(id => id is not null).Select(id => id!).Distinct().Where(id => !s.IsDone(op(id))).ToList();
        var likes = Targets(s.IncludeLiked, Enumerable.Reverse(s.LikedKeys), TransferSession.LikeOp);
        return new TransferPlan(playlists, likes, Targets(s.IncludeAlbums, s.AlbumKeys, TransferSession.AlbumOp), Targets(s.IncludeArtists, s.ArtistKeys, TransferSession.ArtistOp));
    }
}
