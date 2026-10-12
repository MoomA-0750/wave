using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wave.Core.Net;

namespace Wave.Core.Music;

/// <summary>HLS のマスタープレイリストの1本（画質ごとの媒体プレイリスト）。</summary>
public sealed record HlsVariant(long Bandwidth, string? Codecs, string Url);

/// <summary>
/// ライブ配信（YouTube の生配信）の検索と、再生に使う HLS の解決（応答の読み取りは純粋な関数。Android の LiveParsers と同じ）。
/// - 検索は YouTube（www.youtube.com）の「ライブ」の絞り込み。YouTube Music の検索にはライブの絞り込みが無い
/// - 再生は ANDROID クライアントの /player の hlsManifestUrl。WEB_REMIX・WEB は匿名では再生できず、IOS は HLS を返さない（2026-10 に確かめた）
/// </summary>
public static class LiveParsers
{
    /// <summary>YouTube の検索（ライブで絞り込み）の結果と続き。ライブのバッジが付いた動画だけを取る。</summary>
    public static SongPage WebSearch(JsonNode root)
    {
        var songs = root.FindAll("videoRenderer").Select(video =>
        {
            if (!IsLiveNow(video)) return null;
            var videoId = video.Path("videoId").Str();
            var title = string.Concat(video.Path("title", "runs").Items().Select(r => r.Path("text").Str() ?? ""));
            if (videoId is null || string.IsNullOrWhiteSpace(title)) return null;
            var owner = video.Path("ownerText", "runs").FirstItem().Path("text").Str();
            return new Song(videoId, title, owner is null ? [] : [new ArtistRef(owner, null)], null, null,
                video.Path("thumbnail", "thumbnails").Items().LastOrDefault().Path("url").Str(), Song.KindUserVideo, IsLive: true);
        }).OfType<Song>().DistinctBy(s => s.VideoId).ToList();
        var continuation = root.FindAll("continuationItemRenderer").Select(c => c.Path("continuationEndpoint", "continuationCommand", "token").Str()).FirstOrDefault(t => t is not null);
        return new SongPage(songs, continuation);
    }

    private static bool IsLiveNow(JsonObject video) =>
        video.Path("badges").Items().Any(b => b.Path("metadataBadgeRenderer", "style").Str() == "BADGE_STYLE_TYPE_LIVE_NOW") ||
        video.Path("thumbnailOverlays").Items().Any(o => o.Path("thumbnailOverlayTimeStatusRenderer", "style").Str() == "LIVE");

    /// <summary>/player の応答から、再生できるライブの HLS のマスタープレイリストの URL。ライブでない・再生できないなら null。</summary>
    public static string? HlsManifestUrl(JsonNode player)
    {
        if (player.Path("playabilityStatus", "status").Str() != "OK") return null;
        var url = player.Path("streamingData", "hlsManifestUrl").Str();
        return url is not null && LiveUrls.IsAllowedManifest(url) ? url : null;
    }

    /// <summary>/player の応答の URL の寿命（秒）。</summary>
    public static long? ExpiresInSeconds(JsonNode player) => player.Path("streamingData", "expiresInSeconds").Long();

    /// <summary>/player の応答の videoDetails がライブ配信中を示すか。</summary>
    public static bool IsLive(JsonNode player) =>
        player.Path("videoDetails", "isLive") is JsonValue v && v.GetValueKind() == JsonValueKind.True;

    /// <summary>マスタープレイリストの画質の一覧。相対の URL はマスターの URL を基準に直す。</summary>
    public static List<HlsVariant> Variants(string master, string masterUrl)
    {
        var lines = master.Split('\n').Select(l => l.Trim()).ToList();
        var variants = new List<HlsVariant>();
        for (var index = 0; index < lines.Count; index++)
        {
            var line = lines[index];
            if (!line.StartsWith("#EXT-X-STREAM-INF:", StringComparison.Ordinal)) continue;
            var attributes = AttributesOf(line["#EXT-X-STREAM-INF:".Length..]);
            var uri = lines.Skip(index + 1).FirstOrDefault(l => l.Length > 0 && !l.StartsWith('#'));
            if (uri is not null && attributes.TryGetValue("BANDWIDTH", out var b) && long.TryParse(b, out var bandwidth))
                variants.Add(new HlsVariant(bandwidth, attributes.GetValueOrDefault("CODECS"), ResolveUrl(masterUrl, uri)));
        }
        return variants;
    }

    /// <summary>
    /// 音を聴くための1本を選ぶ。AAC-LC（mp4a.40.2）の中で最も帯域の小さいもの（映像も同じ流れに入っているので、小さい画質で通信量を抑える）。
    /// AAC-LC が無ければ、全体で最も帯域の小さいもの。
    /// </summary>
    public static HlsVariant? ChooseAudioVariant(IReadOnlyList<HlsVariant> variants) =>
        variants.Where(v => v.Codecs?.Contains("mp4a.40.2", StringComparison.Ordinal) == true).MinBy(v => v.Bandwidth)
        ?? variants.MinBy(v => v.Bandwidth);

    private static Dictionary<string, string> AttributesOf(string text)
    {
        var result = new Dictionary<string, string>();
        var index = 0;
        while (index < text.Length)
        {
            var eq = text.IndexOf('=', index);
            if (eq < 0) break;
            var name = text[index..eq].Trim();
            int end;
            string value;
            if (eq + 1 < text.Length && text[eq + 1] == '"')
            {
                var close = text.IndexOf('"', eq + 2);
                if (close < 0) close = text.Length;
                value = text[(eq + 2)..close];
                end = close < text.Length ? text.IndexOf(',', close) : -1;
                if (end < 0) end = text.Length;
            }
            else
            {
                end = text.IndexOf(',', eq);
                if (end < 0) end = text.Length;
                value = text[(eq + 1)..end];
            }
            result[name] = value;
            index = end + 1;
        }
        return result;
    }

    private static string ResolveUrl(string baseUrl, string reference)
    {
        if (reference.StartsWith("https://", StringComparison.Ordinal) || reference.StartsWith("http://", StringComparison.Ordinal)) return reference;
        var scheme = baseUrl[..baseUrl.IndexOf("://", StringComparison.Ordinal)];
        var rest = baseUrl[(scheme.Length + 3)..];
        var slash = rest.IndexOf('/');
        var origin = scheme + "://" + (slash < 0 ? rest : rest[..slash]);
        if (reference.StartsWith('/')) return origin + reference;
        var path = baseUrl.Split('?')[0];
        return path[..path.LastIndexOf('/')] + "/" + reference;
    }
}

/// <summary>ライブの HLS の URL の決まり（Android の isAllowedLiveManifestUrl・isAllowedLiveSegmentUrl と同じ）。</summary>
public static class LiveUrls
{
    /// <summary>ライブの HLS のマスター・媒体プレイリストの URL として使ってよいか（https・443・manifest.googlevideo.com の /api/manifest/hls_ 配下）。</summary>
    public static bool IsAllowedManifest(string url) =>
        IsPlainHttps(url, out var uri) && uri.Host == "manifest.googlevideo.com" && uri.AbsolutePath.StartsWith("/api/manifest/hls_", StringComparison.Ordinal);

    /// <summary>ライブの HLS の区切り（googlevideo の /videoplayback/…）として取ってよいか。</summary>
    public static bool IsAllowedSegment(string url) =>
        IsPlainHttps(url, out var uri) && uri.Host.EndsWith(".googlevideo.com", StringComparison.Ordinal) &&
        (uri.AbsolutePath == "/videoplayback" || uri.AbsolutePath.StartsWith("/videoplayback/", StringComparison.Ordinal));

    private static bool IsPlainHttps(string url, out Uri uri) =>
        Uri.TryCreate(url, UriKind.Absolute, out uri!) && uri.Scheme == "https" && uri.IsDefaultPort && string.IsNullOrEmpty(uri.UserInfo);
}

/// <summary>解決したライブの再生先。URL は IP に結び付いた署名を含むので、ToString に出さない。</summary>
public sealed class LiveStream
{
    private readonly string url;

    internal LiveStream(string videoId, string url, long expiresAtMs)
    {
        VideoId = videoId;
        this.url = url;
        ExpiresAtMs = expiresAtMs;
    }

    public string VideoId { get; }

    /// <summary>呼び出し側の時計（nowMs）のミリ秒。</summary>
    public long ExpiresAtMs { get; }

    /// <summary>再生器にだけ渡す。ログ・例外文・画面に出さない。</summary>
    public string RevealUrl() => url;

    public override string ToString() => $"LiveStream(videoId={VideoId})";
}

/// <summary>ライブの解決の失敗の種類。</summary>
public enum LiveFailure { Network, NotLive, NoVariant }

public sealed record LiveResult(LiveStream? Stream, LiveFailure? Failure)
{
    public static LiveResult Ok(LiveStream stream) => new(stream, null);
    public static LiveResult Failed(LiveFailure failure) => new(null, failure);
}

/// <summary>
/// ライブ配信の検索と HLS の解決。www.youtube.com へ送るので、http には認証情報を持たないクライアントを渡す
/// （YouTube Music の cookie・SAPISIDHASH を送らない。InnerTubeClient とは別の HttpClient にする）。
/// </summary>
public sealed class LiveClient(HttpClient http, string hl, string gl, Func<long> nowMs)
{
    /// <summary>YouTube の検索の「ライブ」の絞り込み。</summary>
    public const string LiveFilter = "EgJAAQ%3D%3D";
    public const string WebClientVersion = "2.20261001.00.00";
    public const string AndroidClientVersion = "20.10.38";
    public const string AndroidUserAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
    private const long DefaultLifetimeSeconds = 6 * 60 * 60;
    private const long ExpirySafetyMs = 5 * 60 * 1000;
    private const int MaxResponseBytes = 8 * 1024 * 1024;

    /// <summary>YouTube の検索をライブに絞る。continuation を渡したら続きのページ。読めなければ null。</summary>
    public async Task<SongPage?> SearchAsync(string query, string? continuation = null, CancellationToken cancel = default)
    {
        var body = new JsonObject
        {
            ["context"] = new JsonObject { ["client"] = new JsonObject { ["clientName"] = "WEB", ["clientVersion"] = WebClientVersion, ["hl"] = hl, ["gl"] = gl } },
        };
        if (continuation is not null) body["continuation"] = continuation;
        else
        {
            body["query"] = query;
            body["params"] = LiveFilter;
        }
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("User-Agent", InnerTubeClient.UserAgent);
        request.Headers.TryAddWithoutValidation("Origin", "https://www.youtube.com");
        var root = await SendJsonAsync(request, cancel);
        return root is null ? null : LiveParsers.WebSearch(root);
    }

    /// <summary>ライブの HLS（音を聴くための1本の媒体プレイリスト）を解決する。</summary>
    public async Task<LiveResult> ResolveAsync(string videoId, CancellationToken cancel = default)
    {
        var requestedAt = nowMs();
        var body = new JsonObject
        {
            ["context"] = new JsonObject
            {
                ["client"] = new JsonObject
                {
                    ["clientName"] = "ANDROID",
                    ["clientVersion"] = AndroidClientVersion,
                    ["androidSdkVersion"] = 30,
                    ["osName"] = "Android",
                    ["osVersion"] = "11",
                    ["hl"] = hl,
                    ["gl"] = gl,
                },
            },
            ["videoId"] = videoId,
            ["contentCheckOk"] = true,
            ["racyCheckOk"] = true,
        };
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("User-Agent", AndroidUserAgent);
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Name", "3");
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Version", AndroidClientVersion);
        var player = await SendJsonAsync(request, cancel);
        if (player is null) return LiveResult.Failed(LiveFailure.Network);
        if (LiveParsers.HlsManifestUrl(player) is not { } masterUrl) return LiveResult.Failed(LiveFailure.NotLive);
        string master;
        try
        {
            using var response = await http.GetAsync(masterUrl, HttpCompletionOption.ResponseHeadersRead, cancel);
            if (!response.IsSuccessStatusCode) return LiveResult.Failed(LiveFailure.Network);
            master = await ReadLimitedAsync(response, cancel);
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException or InvalidDataException)
        {
            return LiveResult.Failed(LiveFailure.Network);
        }
        var variant = LiveParsers.ChooseAudioVariant(LiveParsers.Variants(master, masterUrl));
        if (variant is null || !LiveUrls.IsAllowedManifest(variant.Url)) return LiveResult.Failed(LiveFailure.NoVariant);
        var lifetimeMs = (LiveParsers.ExpiresInSeconds(player) is { } s && s > 0 ? s : DefaultLifetimeSeconds) * 1000;
        return LiveResult.Ok(new LiveStream(videoId, variant.Url, requestedAt + lifetimeMs - ExpirySafetyMs));
    }

    private async Task<JsonNode?> SendJsonAsync(HttpRequestMessage request, CancellationToken cancel)
    {
        // 認証情報を付けない経路であることを確かめる（cookie・Authorization は付けない）
        if (request.Headers.Contains("Cookie") || request.Headers.Contains("Authorization")) throw new InvalidOperationException("auth route violation");
        try
        {
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            if (!response.IsSuccessStatusCode) return null;
            return JsonNode.Parse(await ReadLimitedAsync(response, cancel));
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException or InvalidDataException or JsonException)
        {
            return null;
        }
    }

    private static async Task<string> ReadLimitedAsync(HttpResponseMessage response, CancellationToken cancel)
    {
        await using var stream = await response.Content.ReadAsStreamAsync(cancel);
        using var buffer = new MemoryStream();
        var chunk = new byte[81920];
        int read;
        while ((read = await stream.ReadAsync(chunk, cancel)) > 0)
        {
            if (buffer.Length + read > MaxResponseBytes) throw new InvalidDataException("response too large");
            buffer.Write(chunk, 0, read);
        }
        return Encoding.UTF8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length);
    }
}
