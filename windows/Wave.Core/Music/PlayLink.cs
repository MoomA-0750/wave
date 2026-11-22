namespace Wave.Core.Music;

/// <summary>
/// YouTube / YouTube Music のリンクか ID（貼り付けたもの）。Android・Mac の PlayLink と同じ見分け方に、アーティスト（チャンネル）を足したもの。
/// - 動画: <c>music.youtube.com/watch?v=</c>・<c>youtube.com/watch?v=</c>・<c>youtu.be/&lt;id&gt;</c>・<c>youtube.com/live/&lt;id&gt;</c>・<c>youtube.com/shorts/&lt;id&gt;</c>、ID だけなら 11 文字の videoId
/// - プレイリスト: <c>v=</c> の無い <c>list=</c>（<c>PL…</c>・<c>OLAK5uy_…</c>（アルバムの共有のリンク）・<c>RD…</c> など）、ID だけなら <c>PL</c>・<c>OLAK5uy_</c>・<c>RD</c> で始まるもの
/// - アルバム: <c>browse/MPREb_…</c>、ID だけなら <c>MPREb_</c> で始まるもの
/// - アーティスト: <c>channel/UC…</c>・<c>browse/UC…</c>、ID だけなら <c>UC</c> で始まる 24 文字
/// </summary>
public abstract record PlayLink
{
    public sealed record Video(string VideoId) : PlayLink;

    public sealed record Playlist(string PlaylistId) : PlayLink;

    public sealed record Album(string BrowseId) : PlayLink;

    public sealed record Artist(string ChannelId) : PlayLink;

    private static readonly HashSet<string> Hosts = ["youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be"];
    private static readonly string[] PlaylistPrefixes = ["PL", "OLAK5uy_", "RD"];
    private const string AlbumPrefix = "MPREb_";
    private const int VideoIdLength = 11;
    private const int ChannelIdLength = 24;

    /// <summary>読めなければ null（YouTube 以外のリンク、形の合わない ID、ふつうの検索の語）。</summary>
    public static PlayLink? Parse(string text)
    {
        var trimmed = text.Trim();
        if (trimmed.Length == 0) return null;
        return trimmed.Contains("://") || trimmed.Contains('/') || trimmed.Contains('?') ? Url(trimmed) : Id(trimmed);
    }

    private static PlayLink? Url(string text)
    {
        var withoutScheme = text.Contains("://") ? text[(text.IndexOf("://", StringComparison.Ordinal) + 3)..] : text;
        var hostEnd = withoutScheme.IndexOfAny(['/', '?', '#']);
        if (hostEnd < 0) hostEnd = withoutScheme.Length;
        var host = withoutScheme[..hostEnd];
        host = host[(host.LastIndexOf('@') + 1)..];
        if (host.IndexOf(':') is var colon and >= 0) host = host[..colon];
        host = host.ToLowerInvariant();
        if (!Hosts.Contains(host)) return null;
        var rest = withoutScheme[hostEnd..];
        if (rest.IndexOf('#') is var hash and >= 0) rest = rest[..hash];
        var question = rest.IndexOf('?');
        var path = question >= 0 ? rest[..question] : rest;
        var query = question >= 0 ? rest[(question + 1)..] : "";
        var segments = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var parameters = new Dictionary<string, string>();
        foreach (var pair in query.Split('&'))
        {
            var eq = pair.IndexOf('=');
            if (eq > 0) parameters.TryAdd(pair[..eq], pair[(eq + 1)..]);
        }
        if (host is "youtu.be" or "www.youtu.be") return segments.Length > 0 ? AsVideo(segments[0]) : null;
        if (segments.Length > 0 && segments[0] == "watch" && parameters.TryGetValue("v", out var v) && AsVideo(v) is { } video) return video;
        if (segments.Length >= 2 && segments[0] is "live" or "shorts") return AsVideo(segments[1]);
        if (segments.Length >= 2 && segments[0] == "browse" && segments[1].StartsWith(AlbumPrefix, StringComparison.Ordinal) && IsIdChars(segments[1])) return new Album(segments[1]);
        if (segments.Length >= 2 && segments[0] is "channel" or "browse" && AsArtist(segments[1]) is { } artist) return artist;
        return parameters.TryGetValue("list", out var list) && IsIdChars(list) ? new Playlist(list) : null;
    }

    private static PlayLink? Id(string text)
    {
        if (!IsIdChars(text)) return null;
        if (text.StartsWith(AlbumPrefix, StringComparison.Ordinal)) return new Album(text);
        if (AsArtist(text) is { } artist) return artist;
        if (text.Length == VideoIdLength) return new Video(text);
        return PlaylistPrefixes.Any(p => text.StartsWith(p, StringComparison.Ordinal)) ? new Playlist(text) : null;
    }

    private static Video? AsVideo(string id) => id.Length == VideoIdLength && IsIdChars(id) ? new Video(id) : null;

    private static Artist? AsArtist(string id) => id.Length == ChannelIdLength && id.StartsWith("UC", StringComparison.Ordinal) && IsIdChars(id) ? new Artist(id) : null;

    private static bool IsIdChars(string text) => text.Length > 0 && text.All(c => c < 128 && char.IsLetterOrDigit(c) || c is '_' or '-');
}
