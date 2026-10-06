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

    internal ResolvedStream(string videoId, int itag, string mimeType, long? contentLength, string url, long expiresAtMs, string? statsUrl, long generation)
    {
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

    /// <summary>音声を取得するところだけが呼ぶ。ログ・例外文・画面に渡さない。</summary>
    public string RevealUrl() => url;

    internal string? RevealStatsUrl() => statsUrl;

    public override string ToString() => $"ResolvedStream(videoId={VideoId}, itag={Itag}, mimeType={MimeType}, contentLength={ContentLength})";
}

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

    public async Task<ResolveResult> ResolveAsync(string videoId, CancellationToken cancel = default)
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
        var candidates = AudioCandidates(formats);
        if (candidates.Count == 0)
            return ResolveResult.Failed(formats.Count > 0 && formats.All(f => !HasSource(f)) ? StreamFailure.SabrOnly : StreamFailure.NoAudioFormat);

        foreach (var format in candidates)
        {
            var url = Decipher(format, config.Solver);
            if (url is null) continue;
            url = RemoveQueryParameters(url, UnwantedParameters);
            if (!IsAllowedMediaUrl(url)) return ResolveResult.Failed(StreamFailure.RejectedUrl);
            var lifetime = (streaming.Path("expiresInSeconds").Long() is { } s && s > 0 ? s : DefaultLifetimeSeconds) * 1000;
            var statsUrl = generation == client.Generation ? root.Path("playbackTracking", "videostatsPlaybackUrl", "baseUrl").Str() : null;
            return ResolveResult.Ok(new ResolvedStream(
                videoId,
                (int)format.Path("itag").Long()!.Value,
                format.Path("mimeType").Str() ?? "audio/mp4",
                format.Path("contentLength").Long(),
                url,
                requestedAt + lifetime - ExpirySafetyMs,
                statsUrl,
                generation));
        }
        cipher.Invalidate();
        return ResolveResult.Failed(StreamFailure.DecipherFailed);
    }

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
