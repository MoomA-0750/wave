using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Web;
using Wave.Core.Music;
using Wave.Core.Net;

namespace Wave.Core.Stream;

public enum StreamFailure
{
    NotLoggedIn,
    Network,
    Http,
    Parse,
    PlayerConfigUnavailable,
    Unplayable,
    NoAudioFormat,
    SabrOnly,
    DecipherFailed,
    RejectedUrl,
}

/// <summary>解決済みのストリーム。署名付き URL を含むので、ToString に出さない。</summary>
public sealed class ResolvedStream
{
    private readonly string url;
    private readonly string? statsUrl;

    internal ResolvedStream(string videoId, int itag, string mimeType, long? contentLength, string url, long expiresAtMs, string? statsUrl, long generation, DashInfo? dash = null)
    {
        Dash = dash;
        VideoId = videoId;
        Itag = itag;
        MimeType = mimeType;
        ContentLength = contentLength;
        this.url = url;
        ExpiresAtMs = expiresAtMs;
        this.statsUrl = statsUrl;
        Generation = generation;
    }

    public string VideoId { get; }
    public int Itag { get; }
    public string MimeType { get; }
    public long? ContentLength { get; }
    public long ExpiresAtMs { get; }
    public long Generation { get; }
    public bool HasPlaybackStats => statsUrl is not null;

    /// <summary>DASH の目次を作るための値（初期化と索引のバイト範囲など）。無ければ null。</summary>
    public DashInfo? Dash { get; }

    /// <summary>音声を取得するところだけが呼ぶ。ログ・例外文・画面に渡さない。</summary>
    public string RevealUrl() => url;

    internal string? RevealStatsUrl() => statsUrl;

    public override string ToString() => $"ResolvedStream(videoId={VideoId}, itag={Itag}, mimeType={MimeType}, contentLength={ContentLength})";
}

/// <summary>DASH（SegmentBase）の目次を作るための、形式ごとの値。</summary>
public sealed record DashInfo(long InitStart, long InitEnd, long IndexStart, long IndexEnd, long Bitrate, int SampleRate, long DurationMs, string Codecs,
    int Width = 0, int Height = 0, int FrameRate = 0);

/// <summary>取るもの。映像は音の無い映像だけ（曲に重ねる MV）。</summary>
public enum StreamKind { Audio, Video }

public sealed record ResolveResult(ResolvedStream? Stream, StreamFailure? Failure)
{
    public static ResolveResult Ok(ResolvedStream stream) => new(stream, null);
    public static ResolveResult Failed(StreamFailure failure) => new(null, failure);
}

/// <summary>WEB_REMIX の /player から音声のストリーム URL を解決する（Android 版の ADR-0001 と同じ経路。po_token は作らない）。</summary>
public sealed partial class StreamResolver(InnerTubeClient client, PlayerCipherProvider cipher, Func<long> nowMs)
{
    /// <summary>
    /// 優先順。Windows の標準の再生（Media Foundation）が確実に鳴らせる AAC（m4a）を先にし、opus は後にする。
    /// AAC 256kbps → AAC 128 → opus 約 256 → opus 160 → opus 70 → opus 50。
    /// </summary>
    public static readonly int[] AudioItagPreference = [141, 140, 774, 251, 250, 249];
    private static readonly HashSet<string> UnwantedParameters = ["pot", "ump"];
    private const long DefaultLifetimeSeconds = 60 * 60;
    private const long ExpirySafetyMs = 60 * 1000;

    public async Task<ResolveResult> ResolveAsync(string videoId, CancellationToken cancel = default, StreamKind kind = StreamKind.Audio)
    {
        if (!VideoIdPattern().IsMatch(videoId)) return ResolveResult.Failed(StreamFailure.Parse);
        var config = await cipher.GetAsync(cancel);
        if (config is null) return ResolveResult.Failed(StreamFailure.PlayerConfigUnavailable);
        var requestedAt = nowMs();
        var generation = client.Generation;
        var body = new JsonObject
        {
            ["videoId"] = videoId,
            ["playbackContext"] = new JsonObject
            {
                ["contentPlaybackContext"] = new JsonObject { ["html5Preference"] = "HTML5_PREF_WANTS", ["signatureTimestamp"] = config.SignatureTimestamp },
            },
            ["contentCheckOk"] = true,
            ["racyCheckOk"] = true,
            ["videoCheckOk"] = true,
        };
        var response = await client.PostAsync("player", body, cancel: cancel);
        if (!response.IsOk)
        {
            return ResolveResult.Failed(response.Failure switch
            {
                FailureKind.NotLoggedIn => StreamFailure.NotLoggedIn,
                FailureKind.Network => StreamFailure.Network,
                FailureKind.Parse => StreamFailure.Parse,
                _ => StreamFailure.Http,
            });
        }
        var root = response.Value!;
        var status = root.Path("playabilityStatus", "status").Str();
        if (status == "LOGIN_REQUIRED") return ResolveResult.Failed(StreamFailure.NotLoggedIn);
        if (status != "OK") return ResolveResult.Failed(StreamFailure.Unplayable);
        var streaming = root.Path("streamingData");
        var formats = streaming.Path("adaptiveFormats").Items();
        var candidates = kind == StreamKind.Video ? VideoCandidates(formats) : AudioCandidates(formats);
        if (candidates.Count == 0)
            return ResolveResult.Failed(formats.Count > 0 && formats.All(f => !HasSource(f)) ? StreamFailure.SabrOnly : StreamFailure.NoAudioFormat);

        foreach (var format in candidates)
        {
            var url = Decipher(format, config.Solver);
            if (url is null) continue;
            url = RemoveQueryParameters(url, UnwantedParameters);
            if (!IsAllowedMediaUrl(url)) return ResolveResult.Failed(StreamFailure.RejectedUrl);
            var lifetime = (streaming.Path("expiresInSeconds").Long() is { } s && s > 0 ? s : DefaultLifetimeSeconds) * 1000;
            var statsUrl = generation == client.Generation && kind == StreamKind.Audio ? root.Path("playbackTracking", "videostatsPlaybackUrl", "baseUrl").Str() : null;
            return ResolveResult.Ok(new ResolvedStream(
                videoId,
                (int)format.Path("itag").Long()!.Value,
                format.Path("mimeType").Str() ?? "audio/mp4",
                format.Path("contentLength").Long(),
                url,
                requestedAt + lifetime - ExpirySafetyMs,
                statsUrl,
                generation,
                DashOf(format)));
        }
        cipher.Invalidate();
        return ResolveResult.Failed(StreamFailure.DecipherFailed);
    }

    internal static DashInfo? DashOf(JsonObject format)
    {
        var init = format.Path("initRange");
        var index = format.Path("indexRange");
        if (init.Path("start").Long() is not { } i0 || init.Path("end").Long() is not { } i1 ||
            index.Path("start").Long() is not { } x0 || index.Path("end").Long() is not { } x1) return null;
        var mime = format.Path("mimeType").Str() ?? "";
        var codecs = CodecsPattern().Match(mime) is { Success: true } m ? m.Groups[1].Value : "mp4a.40.2";
        return new DashInfo(i0, i1, x0, x1, format.Path("bitrate").Long() ?? 128000, (int)(format.Path("audioSampleRate").Long() ?? 44100),
            format.Path("approxDurationMs").Long() ?? 0, codecs,
            (int)(format.Path("width").Long() ?? 0), (int)(format.Path("height").Long() ?? 0), (int)(format.Path("fps").Long() ?? 0));
    }

    [GeneratedRegex("codecs=\"([^\"]+)\"")]
    private static partial Regex CodecsPattern();

    /// <summary>Premium 音質（AAC 256kbps・opus 約 256kbps）の形式の itag。</summary>
    public static readonly int[] PremiumItags = [141, 774];

    /// <summary>
    /// Premium 音質を使えるか（Android の premiumStatus と同じ）。決まった1曲で Premium の形式が返るかを見る。分からなければ null。
    /// </summary>
    public async Task<bool?> PremiumAudioAsync(CancellationToken cancel = default)
    {
        var config = await cipher.GetAsync(cancel);
        if (config is null) return null;
        var body = new JsonObject
        {
            ["videoId"] = PremiumProbeVideoId,
            ["playbackContext"] = new JsonObject { ["contentPlaybackContext"] = new JsonObject { ["signatureTimestamp"] = config.SignatureTimestamp } },
            ["contentCheckOk"] = true,
            ["racyCheckOk"] = true,
        };
        var response = await client.PostAsync("player", body, cancel: cancel);
        if (!response.IsOk || response.Value!.Path("playabilityStatus", "status").Str() != "OK") return null;
        return response.Value.Path("streamingData", "adaptiveFormats").Items().Any(f => f.Path("itag").Long() is { } itag && Array.IndexOf(PremiumItags, (int)itag) >= 0);
    }

    private const string PremiumProbeVideoId = "dQw4w9WgXcQ";

    /// <summary>解決した URL が拒否された（403 など）。次は解読の準備を取り直す。</summary>
    public void OnStreamRejected() => cipher.Invalidate();

    public bool IsFresh(ResolvedStream stream, long marginMs) => stream.ExpiresAtMs - nowMs() > marginMs;

    /// <summary>signatureCipher を解いて n を変換した URL。解けなければ null。</summary>
    internal static string? Decipher(JsonObject format, CipherSolver solver)
    {
        var url = format.Path("url").Str();
        var cipherText = format.Path("signatureCipher").Str() ?? format.Path("cipher").Str();
        if (url is null && cipherText is not null)
        {
            var p = HttpUtility.ParseQueryString(cipherText);
            var s = p["s"];
            var baseUrl = p["url"];
            var sp = p["sp"] ?? "signature";
            if (s is null || baseUrl is null || !IsAllowedMediaUrl(baseUrl) || !QueryNamePattern().IsMatch(sp)) return null;
            var solved = solver.SolveSignature(s);
            if (solved is null) return null;
            url = baseUrl + (baseUrl.Contains('?') ? "&" : "?") + sp + "=" + Uri.EscapeDataString(solved);
        }
        if (url is null) return null;
        var n = NParameter().Match(url);
        if (n.Success)
        {
            var original = Uri.UnescapeDataString(n.Groups[2].Value);
            var transformed = solver.SolveN(original);
            // n を解けない URL は遅い（または拒否される）ので使わない
            if (transformed is null) return null;
            url = url[..n.Groups[2].Index] + Uri.EscapeDataString(transformed) + url[(n.Groups[2].Index + n.Groups[2].Length)..];
        }
        return url;
    }

    /// <summary>映像の高さの上限（右の欄・全画面でも足りる高さで、通信量を抑える）。</summary>
    public const int MaxVideoHeight = 720;

    /// <summary>
    /// 映像だけのフォーマット。Windows の標準の再生が DASH で確実に鳴らせる H.264（mp4・avc1）だけを、高い方から。
    /// </summary>
    public static List<JsonObject> VideoCandidates(IEnumerable<JsonObject> formats) =>
        formats
            .Where(f => f.Path("mimeType").Str() is { } m && m.StartsWith("video/mp4", StringComparison.Ordinal) && m.Contains("avc1", StringComparison.Ordinal))
            .Where(f => (f.Path("height").Long() ?? long.MaxValue) <= MaxVideoHeight)
            .Where(HasSource)
            .OrderByDescending(f => f.Path("height").Long() ?? 0)
            .ToList();

    public static List<JsonObject> AudioCandidates(IEnumerable<JsonObject> formats) =>
        formats
            .Where(f => f.Path("mimeType").Str()?.StartsWith("audio/", StringComparison.Ordinal) == true)
            .Where(f => !IsTrue(f.Path("isDrc")))
            .Where(f => f.Path("itag").Long() is { } itag && Array.IndexOf(AudioItagPreference, (int)itag) >= 0)
            // 吹き替えなど音声トラックが複数ある曲では、既定のトラックだけを使う
            .Where(f => f.Path("audioTrack") is null || IsTrue(f.Path("audioTrack", "audioIsDefault")))
            .Where(HasSource)
            .OrderBy(f => Array.IndexOf(AudioItagPreference, (int)f.Path("itag").Long()!.Value))
            .ToList();

    private static bool IsTrue(JsonNode? node) => node is JsonValue v && v.GetValueKind() == System.Text.Json.JsonValueKind.True;

    private static bool HasSource(JsonObject f) => f.Path("url") is not null || f.Path("signatureCipher") is not null || f.Path("cipher") is not null;

    /// <summary>googlevideo の /videoplayback への https だけを使う。</summary>
    public static bool IsAllowedMediaUrl(string url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Scheme == "https" && uri.IsDefaultPort &&
        string.IsNullOrEmpty(uri.UserInfo) &&
        (uri.Host == "googlevideo.com" || uri.Host.EndsWith(".googlevideo.com", StringComparison.Ordinal)) &&
        uri.AbsolutePath == "/videoplayback";

    /// <summary>クエリの並びと他の値の符号化を変えずに、指定した名前のパラメータだけを外す。</summary>
    public static string RemoveQueryParameters(string url, ISet<string> names)
    {
        var queryStart = url.IndexOf('?');
        if (queryStart < 0) return url;
        var fragmentStart = url.IndexOf('#', queryStart);
        if (fragmentStart < 0) fragmentStart = url.Length;
        var kept = url[(queryStart + 1)..fragmentStart].Split('&').Where(p => p.Length > 0 && !names.Contains(p.Split('=')[0])).ToList();
        var baseUrl = url[..queryStart];
        return (kept.Count == 0 ? baseUrl : baseUrl + "?" + string.Join("&", kept)) + url[fragmentStart..];
    }

    [GeneratedRegex("^[A-Za-z0-9_-]{11}$")]
    private static partial Regex VideoIdPattern();

    [GeneratedRegex("^[A-Za-z0-9_]{1,32}$")]
    private static partial Regex QueryNamePattern();

    [GeneratedRegex("([?&])n=([^&]+)")]
    private static partial Regex NParameter();
}
