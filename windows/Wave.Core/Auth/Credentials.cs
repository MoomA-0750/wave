namespace Wave.Core.Auth;

/// <summary>
/// ログインで得た認証情報。値は ToString・ログ・例外文に出さない。値を取り出す場所は Reveal で探せる。
/// </summary>
public sealed class Credentials
{
    private static readonly string[] SapisidCookieNames = ["SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"];

    private readonly string cookie;
    private readonly string? dataSyncId;

    /// <summary>追跡用の ID で、認証情報ではない。</summary>
    public string? VisitorData { get; }

    public long ObtainedAtEpochMs { get; }

    private Credentials(string cookie, string? dataSyncId, string? visitorData, long obtainedAtEpochMs)
    {
        this.cookie = cookie;
        this.dataSyncId = dataSyncId;
        VisitorData = visitorData;
        ObtainedAtEpochMs = obtainedAtEpochMs;
    }

    public string RevealCookie() => cookie;

    public string? RevealDataSyncId() => dataSyncId;

    /// <summary>SAPISIDHASH に使う値。無ければ null。</summary>
    internal string? RevealSapisid()
    {
        var map = ParseCookie(cookie);
        foreach (var name in SapisidCookieNames)
        {
            if (map.TryGetValue(name, out var value) && value.Length > 0) return value;
        }
        return null;
    }

    public override string ToString() => $"Credentials(cookie=present, dataSyncId={dataSyncId is not null}, visitorData={VisitorData is not null})";

    /// <summary>ブラウザから取った生の値で作る。制御文字を含むときと、SAPISID 系の cookie が無いときは null。</summary>
    public static Credentials? FromLogin(string rawCookie, string? visitorData, string? rawDataSyncId, long nowEpochMs)
    {
        if (new[] { rawCookie, visitorData, rawDataSyncId }.Any(v => v is not null && v.Any(char.IsControl))) return null;
        var cookie = rawCookie.Trim();
        var map = ParseCookie(cookie);
        if (!SapisidCookieNames.Any(n => map.TryGetValue(n, out var v) && v.Length > 0)) return null;
        return new Credentials(cookie, NormalizeDataSyncId(rawDataSyncId), string.IsNullOrWhiteSpace(visitorData) ? null : visitorData, nowEpochMs);
    }

    /// <summary>DATASYNC_ID は "||" の前だけを onBehalfOfUser に使う（丸ごと渡すと 500 になる）。</summary>
    internal static string? NormalizeDataSyncId(string? raw)
    {
        if (raw is null) return null;
        var at = raw.IndexOf("||", StringComparison.Ordinal);
        var value = (at >= 0 ? raw[..at] : raw).Trim();
        return value.Length == 0 ? null : value;
    }

    internal static Dictionary<string, string> ParseCookie(string cookie)
    {
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var part in cookie.Split(';'))
        {
            var eq = part.IndexOf('=');
            if (eq <= 0) continue;
            map[part[..eq].Trim()] = part[(eq + 1)..].Trim();
        }
        return map;
    }

    /// <summary>保存用の形（暗号化してから保存する）。</summary>
    public string Serialize() =>
        System.Text.Json.JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["cookie"] = cookie,
            ["dataSyncId"] = dataSyncId,
            ["visitorData"] = VisitorData,
            ["obtainedAt"] = ObtainedAtEpochMs,
        });

    public static Credentials? Deserialize(string text)
    {
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(text);
            var root = doc.RootElement;
            string? Get(string name) => root.TryGetProperty(name, out var v) && v.ValueKind == System.Text.Json.JsonValueKind.String ? v.GetString() : null;
            var cookie = Get("cookie");
            var obtained = root.TryGetProperty("obtainedAt", out var o) && o.TryGetInt64(out var t) ? t : 0;
            return cookie is null ? null : FromLogin(cookie, Get("visitorData"), Get("dataSyncId"), obtained);
        }
        catch (System.Text.Json.JsonException)
        {
            return null;
        }
    }
}
