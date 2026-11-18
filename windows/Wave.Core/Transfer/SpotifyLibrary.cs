namespace Wave.Core.Transfer;

/// <summary>Spotify の曲（データのダウンロードには長さ・ISRC が無い。Web API なら有る）。</summary>
public sealed record SpotifyTrack(string Title, IReadOnlyList<string> Artists, string? Album, int? DurationMs = null, string? Isrc = null, string? Uri = null)
{
    public string ArtistsText => string.Join(", ", Artists);
}

public sealed record SpotifyAlbum(string Title, IReadOnlyList<string> Artists, string? Uri = null)
{
    public string ArtistsText => string.Join(", ", Artists);
}

public sealed record SpotifyArtist(string Name, string? Uri = null);

/// <summary>プレイリスト。Readable が false のものは名前だけ分かり中身を読めない（Web API で、他人が作ったプレイリスト）。</summary>
public sealed record SpotifyPlaylist(string Name, IReadOnlyList<SpotifyTrack> Tracks, bool Readable = true);

/// <summary>読めなかったもの（画面とログに出す）。File はファイルの名前（Web API では読んだ先の名前）。</summary>
public sealed record ImportProblem(string File, ImportProblemKind Kind, int Count = 1);

public enum ImportProblemKind
{
    /// <summary>JSON として読めなかった。</summary>
    Unreadable,
    /// <summary>YourLibrary・Playlist の名前のファイルなのに、知っている形の項目が無かった。</summary>
    UnknownShape,
    /// <summary>曲名などが無く読み飛ばした項目。</summary>
    SkippedItems,
    /// <summary>ポッドキャストのエピソード・オーディオブック（YouTube Music へは移さない）。</summary>
    SkippedEpisodes,
    /// <summary>Web API で読めなかった（通信の失敗など）。</summary>
    ApiFailed,
}

/// <summary>Spotify から読んだもの（データのダウンロード・Web API のどちらからでも同じ形）。</summary>
public sealed record SpotifyLibrary(
    IReadOnlyList<SpotifyPlaylist> Playlists,
    IReadOnlyList<SpotifyTrack> LikedTracks,
    IReadOnlyList<SpotifyAlbum> Albums,
    IReadOnlyList<SpotifyArtist> Artists,
    IReadOnlyList<ImportProblem> Problems)
{
    public bool IsEmpty => Playlists.Count == 0 && LikedTracks.Count == 0 && Albums.Count == 0 && Artists.Count == 0;
}
