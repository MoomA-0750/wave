using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wave.Core.Auth;

namespace Wave.Core.Net;

public enum FailureKind
{
    /// <summary>ログインが要る（ログインしていない、または切れた）。</summary>
    NotLoggedIn,
    Network,
    Http,
    Parse,
}

/// <summary>InnerTube の呼び出しの結果。</summary>
public sealed class ApiResult<T>
{
    public T? Value { get; }
    public FailureKind? Failure { get; }
    public int? Status { get; }

    private ApiResult(T? value, FailureKind? failure, int? status)
    {
        Value = value;
        Failure = failure;
        Status = status;
    }

    public bool IsOk => Failure is null;

    public static ApiResult<T> Ok(T value) => new(value, null, null);

    public static ApiResult<T> Failed(FailureKind kind, int? status = null) => new(default, kind, status);

    public ApiResult<TOut> Map<TOut>(Func<T, TOut> map) => IsOk ? ApiResult<TOut>.Ok(map(Value!)) : ApiResult<TOut>.Failed(Failure!.Value, Status);
}

/// <summary>
/// YouTube Music の InnerTube（WEB_REMIX）への通信。認証のヘッダ（cookie・SAPISIDHASH）は music.youtube.com にだけ付ける。
/// 送った値・受けた値はログに出さない（呼び出し側が出すのは件数と状態だけ）。
/// </summary>
public sealed class InnerTubeClient
{
    public const string ClientName = "WEB_REMIX";
    public const string ClientId = "67";
    public const string ClientVersion = "1.20260707.12.00";
    public const string UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    public const string Origin = "https://music.youtube.com";
    private const string ApiBase = Origin + "/youtubei/v1/";
    private const int MaxResponseBytes = 8 * 1024 * 1024;

    private readonly HttpClient http;
    private volatile Credentials? credentials;
    private long generation;

    /// <summary>表示の言語（YouTube から届く文字の言語）。画面に合わせて日本語に固定する。</summary>
    public string Hl { get; }

    /// <summary>地域。端末の設定のまま。</summary>
    public string Gl { get; }

    public InnerTubeClient(HttpClient http, string hl = "ja", string? gl = null)
    {
        this.http = http;
        Hl = hl;
        Gl = gl ?? SafeRegion();
    }

    private static string SafeRegion()
    {
        try
        {
            var region = RegionInfo.CurrentRegion.TwoLetterISORegionName;
            return region.Length == 2 ? region.ToUpperInvariant() : "JP";
        }
        catch (ArgumentException)
        {
            return "JP";
        }
    }

    public bool IsLoggedIn => credentials is not null;

    /// <summary>ログインの世代。ログイン・ログアウトのたびに変わる（古い世代で始めた操作の結果を捨てるのに使う）。</summary>
    public long Generation => Interlocked.Read(ref generation);

    public void SetCredentials(Credentials? value)
    {
        credentials = value;
        Interlocked.Increment(ref generation);
    }

    internal Credentials? CurrentCredentials => credentials;

    /// <summary>youtubei/v1/{endpoint} に POST する。setLogin なら（ログイン中のとき）認証のヘッダを付ける。</summary>
    public async Task<ApiResult<JsonNode>> PostAsync(
        string endpoint,
        JsonObject body,
        bool setLogin = true,
        IReadOnlyDictionary<string, string>? query = null,
        CancellationToken cancel = default)
    {
        var creds = setLogin ? credentials : null;
        var loginGeneration = Generation;
        body["context"] = Context(creds);
        var url = ApiBase + endpoint + "?prettyPrint=false";
        if (query is not null)
        {
            foreach (var (name, value) in query) url += "&" + name + "=" + Uri.EscapeDataString(value);
        }
        using var request = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        AddHeaders(request, creds);
        try
        {
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            var status = (int)response.StatusCode;
            if (status is 401 or 403 && creds is not null) return ApiResult<JsonNode>.Failed(FailureKind.NotLoggedIn, status);
            if (status is < 200 or > 299) return ApiResult<JsonNode>.Failed(FailureKind.Http, status);
            // 送ったあとでログインが変わっていたら、別のアカウントの結果として使わない
            if (creds is not null && loginGeneration != Generation) return ApiResult<JsonNode>.Failed(FailureKind.NotLoggedIn);
            var text = await ReadLimitedAsync(response, MaxResponseBytes, cancel);
            return JsonNode.Parse(text) is { } root ? ApiResult<JsonNode>.Ok(root) : ApiResult<JsonNode>.Failed(FailureKind.Parse);
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (JsonException)
        {
            return ApiResult<JsonNode>.Failed(FailureKind.Parse);
        }
        catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException or InvalidDataException)
        {
            return ApiResult<JsonNode>.Failed(FailureKind.Network);
        }
    }

    /// <summary>再生の統計（再生履歴）の GET。URL は music.youtube.com の /api/stats/playback に限る（呼ぶ側で検査済み）。</summary>
    internal async Task<int?> GetStatsAsync(string url, long expectedGeneration, CancellationToken cancel)
    {
        var creds = credentials;
        if (creds is null || expectedGeneration != Generation) return null;
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        AddHeaders(request, creds);
        request.Headers.TryAddWithoutValidation("Cache-Control", "no-cache");
        try
        {
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            return (int)response.StatusCode;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException)
        {
            return null;
        }
    }

    private JsonObject Context(Credentials? creds)
    {
        var client = new JsonObject
        {
            ["clientName"] = ClientName,
            ["clientVersion"] = ClientVersion,
            ["gl"] = Gl,
            ["hl"] = Hl,
        };
        var visitor = creds?.VisitorData ?? credentials?.VisitorData;
        if (visitor is not null) client["visitorData"] = visitor;
        var user = new JsonObject { ["lockedSafetyMode"] = false };
        if (creds?.RevealDataSyncId() is { } dsid) user["onBehalfOfUser"] = dsid;
        return new JsonObject
        {
            ["client"] = client,
            ["request"] = new JsonObject { ["internalExperimentFlags"] = new JsonArray(), ["useSsl"] = true },
            ["user"] = user,
        };
    }

    private void AddHeaders(HttpRequestMessage request, Credentials? creds)
    {
        // 認証のヘッダは music.youtube.com への https にだけ付ける
        if (request.RequestUri is not { Scheme: "https", Host: "music.youtube.com", IsDefaultPort: true })
            throw new InvalidOperationException("auth route violation");
        var h = request.Headers;
        h.TryAddWithoutValidation("User-Agent", UserAgent);
        h.TryAddWithoutValidation("X-Goog-Api-Format-Version", "1");
        h.TryAddWithoutValidation("X-YouTube-Client-Name", ClientId);
        h.TryAddWithoutValidation("X-YouTube-Client-Version", ClientVersion);
        h.TryAddWithoutValidation("Origin", Origin);
        h.TryAddWithoutValidation("X-Origin", Origin);
        h.TryAddWithoutValidation("Referer", Origin + "/");
        h.TryAddWithoutValidation("Accept-Language", Hl);
        var visitor = creds?.VisitorData ?? credentials?.VisitorData;
        if (visitor is not null) h.TryAddWithoutValidation("X-Goog-Visitor-Id", visitor);
        h.TryAddWithoutValidation("Cookie", WithPref(creds?.RevealCookie()));
        if (creds?.RevealSapisid() is { } sapisid)
        {
            h.TryAddWithoutValidation("X-Goog-AuthUser", "0");
            h.TryAddWithoutValidation("Authorization", SapisidHash(sapisid, DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
        }
    }

    internal static string SapisidHash(string sapisid, long unixSeconds)
    {
        var digest = SHA1.HashData(Encoding.UTF8.GetBytes($"{unixSeconds} {sapisid} {Origin}"));
        return $"SAPISIDHASH {unixSeconds}_{Convert.ToHexStringLower(digest)}";
    }

    /// <summary>cookie の PREF に表示の言語と地域を入れる（無ければ PREF だけ）。</summary>
    internal string WithPref(string? cookie)
    {
        if (string.IsNullOrWhiteSpace(cookie)) return $"PREF=hl={Hl}&gl={Gl}";
        var parts = cookie.Split(';').Select(p => p.Trim()).Where(p => p.Length > 0).ToList();
        var prefParts = new List<string>();
        var others = new List<string>();
        foreach (var part in parts)
        {
            if (part.StartsWith("PREF=", StringComparison.OrdinalIgnoreCase))
                prefParts.AddRange(part[5..].Split('&').Where(p => !p.StartsWith("hl=") && !p.StartsWith("gl=")));
            else others.Add(part);
        }
        prefParts.Insert(0, $"hl={Hl}");
        prefParts.Insert(1, $"gl={Gl}");
        others.Add("PREF=" + string.Join("&", prefParts));
        return string.Join("; ", others);
    }

    private static async Task<string> ReadLimitedAsync(HttpResponseMessage response, int maxBytes, CancellationToken cancel)
    {
        await using var stream = await response.Content.ReadAsStreamAsync(cancel);
        using var buffer = new MemoryStream();
        var chunk = new byte[81920];
        int read;
        while ((read = await stream.ReadAsync(chunk, cancel)) > 0)
        {
            if (buffer.Length + read > maxBytes) throw new InvalidDataException("response too large");
            buffer.Write(chunk, 0, read);
        }
        return Encoding.UTF8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length);
    }

    /// <summary>cookie を持たない HttpClient（自動のリダイレクト・cookie の保存をしない）。</summary>
    public static HttpClient CreateHttpClient() =>
        new(new SocketsHttpHandler
        {
            UseCookies = false,
            AllowAutoRedirect = false,
            AutomaticDecompression = DecompressionMethods.All,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        })
        { Timeout = TimeSpan.FromSeconds(30) };
}
