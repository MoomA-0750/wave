namespace Wave.Core.Music;

/// <summary>画面に依らない曲の表現。</summary>
public sealed record Song(
    string VideoId,
    string Title,
    IReadOnlyList<ArtistRef> Artists,
    AlbumRef? Album,
    int? DurationSeconds,
    string? ThumbnailUrl,
    // 曲の種類（musicVideoType の値。MUSIC_VIDEO_TYPE_ATV は音源、_OMV は公式の MV、_UGC は投稿者の動画）。応答に無ければ null
    string? Kind = null)
{
    public const string KindSong = "MUSIC_VIDEO_TYPE_ATV";
    public const string KindMusicVideo = "MUSIC_VIDEO_TYPE_OMV";
    public const string KindUserVideo = "MUSIC_VIDEO_TYPE_UGC";

    public string ArtistsText => string.Join(", ", Artists.Select(a => a.Name));

    /// <summary>「アーティスト • アルバム」の1行。</summary>
    public string Subtitle => string.Join(" • ", new[] { ArtistsText, Album?.Title }.Where(s => !string.IsNullOrEmpty(s)));
}

public sealed record ArtistRef(string Name, string? BrowseId);

public sealed record AlbumRef(string Title, string? BrowseId);

public sealed record SongPage(IReadOnlyList<Song> Songs, string? Continuation);

public sealed record UpNextPage(IReadOnlyList<Song> Songs, string? Continuation, string? PlaylistId);

public sealed record PlaylistSummary(string PlaylistId, string Title, string? Subtitle, string? ThumbnailUrl, bool Editable = false);

public sealed record PlaylistPage(
    string? Title,
    IReadOnlyList<Song> Songs,
    string? Continuation,
    IReadOnlyList<string?> SetVideoIds,
    bool Editable,
    bool EndConfirmed,
    string? Subtitle = null,
    string? ThumbnailUrl = null,
    /// <summary>サーバーで並べ替えられる（自分のプレイリストなど。並べ替えのメニューがある）。</summary>
    bool Sortable = false);

public sealed record HistorySection(string? Title, IReadOnlyList<Song> Songs);

public sealed record HistoryPage(IReadOnlyList<HistorySection> Sections, string? Continuation);

public enum LikeStatus { Like, Dislike, Indifferent }

public sealed record AlbumSummary(string BrowseId, string Title, string? Subtitle, string? ThumbnailUrl);

public sealed record AlbumPage(
    string BrowseId,
    string Title,
    string? Subtitle,
    IReadOnlyList<ArtistRef> Artists,
    string? ThumbnailUrl,
    string? PlaylistId,
    IReadOnlyList<Song> Songs);

public sealed record ArtistPage(
    string Name,
    string? ThumbnailUrl,
    IReadOnlyList<Song> TopSongs,
    string? AllSongsPlaylistId,
    IReadOnlyList<AlbumShelf> AlbumShelves);

public sealed record AlbumShelf(string Title, IReadOnlyList<AlbumSummary> Albums);

public sealed record ArtistSummary(string BrowseId, string Name, string? Subtitle, string? ThumbnailUrl);

public sealed record SearchPage<T>(IReadOnlyList<T> Items, string? Continuation);

public sealed record SearchSuggestions(
    IReadOnlyList<string> Queries,
    IReadOnlyList<Song> Songs,
    IReadOnlyList<AlbumSummary> Albums,
    IReadOnlyList<ArtistSummary> Artists)
{
    public bool IsEmpty => Queries.Count == 0 && Songs.Count == 0 && Albums.Count == 0 && Artists.Count == 0;
}

/// <summary>ホームの棚の1枚。曲・アルバム・プレイリスト・アーティストが混ざる。</summary>
public abstract record HomeItem
{
    public sealed record SongItem(Song Song) : HomeItem;
    public sealed record AlbumItem(AlbumSummary Album) : HomeItem;
    public sealed record PlaylistItem(PlaylistSummary Playlist) : HomeItem;
    public sealed record ArtistItem(ArtistSummary Artist) : HomeItem;

    public string Title => this switch
    {
        SongItem s => s.Song.Title,
        AlbumItem a => a.Album.Title,
        PlaylistItem p => p.Playlist.Title,
        ArtistItem a => a.Artist.Name,
        _ => "",
    };

    public string? Subtitle => this switch
    {
        SongItem s => s.Song.Subtitle,
        AlbumItem a => a.Album.Subtitle,
        PlaylistItem p => p.Playlist.Subtitle,
        ArtistItem a => a.Artist.Subtitle,
        _ => null,
    };

    public string? ThumbnailUrl => this switch
    {
        SongItem s => s.Song.ThumbnailUrl,
        AlbumItem a => a.Album.ThumbnailUrl,
        PlaylistItem p => p.Playlist.ThumbnailUrl,
        ArtistItem a => a.Artist.ThumbnailUrl,
        _ => null,
    };

    public bool IsArtist => this is ArtistItem;

    /// <summary>同じものを見分けるための値（重複を除くのに使う）。</summary>
    public string Key => this switch
    {
        SongItem s => "s:" + s.Song.VideoId,
        AlbumItem a => "a:" + a.Album.BrowseId,
        PlaylistItem p => "p:" + p.Playlist.PlaylistId,
        ArtistItem a => "r:" + a.Artist.BrowseId,
        _ => "",
    };
}

/// <summary>ホームの棚。見出しは応答の表示のまま（言語に依るので判定に使わない）。</summary>
public sealed record HomeShelf(string Title, string? Strapline, IReadOnlyList<HomeItem> Items);

public sealed record HomeChip(string Title, string Params);

public sealed record HomePage(IReadOnlyList<HomeShelf> Shelves, string? Continuation, IReadOnlyList<HomeChip> Chips);

public sealed record MoodCategory(string Title, string Params, long? Color);

public sealed record MoodSection(string Title, IReadOnlyList<MoodCategory> Categories);

/// <summary>next の最初の応答から分かる、曲の評価と歌詞のタブと、曲に重ねる映像（無ければ null）。</summary>
public sealed record SongInfo(LikeStatus? Like, string? LyricsBrowseId, MusicVideo? Video = null);

/// <summary>音源と MV の時刻の対応の1区間（MV には前奏や間奏が足されていて、同じ時刻にはならない）。</summary>
public sealed record TimeSegment(long PrimaryStartMs, long CounterpartStartMs, long DurationMs);

/// <summary>曲に見せる映像（Android の MusicVideo と同じ）。</summary>
public sealed record MusicVideo(string VideoId, IReadOnlyList<TimeSegment> Segments)
{
    /// <summary>曲の positionMs に当たる映像の時刻。対応する区間の外（MV に無い部分）なら null。</summary>
    public long? VideoPositionOf(long positionMs)
    {
        if (Segments.Count == 0) return positionMs;
        var segment = Segments.FirstOrDefault(s => positionMs >= s.PrimaryStartMs && positionMs < s.PrimaryStartMs + s.DurationMs);
        return segment is null ? null : segment.CounterpartStartMs + (positionMs - segment.PrimaryStartMs);
    }
}

/// <summary>確かめたアカウント（DATASYNC_ID）と、確かめたときのログインの世代。Id はログ・画面に出さない。</summary>
public sealed record VerifiedAccount(long Generation, string Id)
{
    public override string ToString() => $"VerifiedAccount(Generation={Generation})";
}
