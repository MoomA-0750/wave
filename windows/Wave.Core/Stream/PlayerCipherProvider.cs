using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Wave.Core.Music;

namespace Wave.Core.Stream;

/// <summary>
/// 今の YouTube のプレイヤー（ハッシュ）と、それに合う faraday の表の行、解読器を用意する。
/// どれも公開データで、cookie は送らない。1時間ごとに取り直す。
/// </summary>
public sealed partial class PlayerCipherProvider(HttpClient http, Func<long> nowMs)
{
    public const string FaradayConfigUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json";
    private const long TtlMs = 60 * 60 * 1000;
    private const int MaxPlayerBytes = 8 * 1024 * 1024;
    private const string BrowserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private readonly SemaphoreSlim gate = new(1, 1);
    private Ready? ready;

    /// <summary>解読の準備ができた状態（プレイヤーのハッシュ・STS・解読器）。</summary>
    public sealed record Ready(string PlayerHash, int SignatureTimestamp, CipherSolver Solver, long FetchedAtMs);

    /// <summary>解読器を返す。取れなければ null。</summary>
    public async Task<Ready?> GetAsync(CancellationToken cancel)
    {
        await gate.WaitAsync(cancel);
        try
        {
            if (ready is { } r && nowMs() - r.FetchedAtMs < TtlMs) return r;
            var hash = await CurrentPlayerHashAsync(cancel);
            if (hash is null) return null;
            if (ready is { } same && same.PlayerHash == hash)
            {
                ready = same with { FetchedAtMs = nowMs() };
                return ready;
            }
            var config = await FaradayConfigAsync(hash, cancel);
            if (config is null) return null;
            var code = await GetTextAsync($"https://www.youtube.com/s/player/{hash}/player_ias.vflset/en_GB/base.js", MaxPlayerBytes, cancel);
            if (code is null) return null;
            var solver = await Task.Run(() => CipherSolver.Create(code, config), cancel);
            ready = new Ready(hash, config.SignatureTimestamp, solver, nowMs());
            return ready;
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return null;
        }
        finally
        {
            gate.Release();
        }
    }

    /// <summary>解いた URL が拒否された。次は取り直す。</summary>
    public void Invalidate() => ready = null;

    private async Task<string?> CurrentPlayerHashAsync(CancellationToken cancel)
    {
        var api = await GetTextAsync("https://www.youtube.com/iframe_api", 256 * 1024, cancel);
        if (api is null) return null;
        var match = PlayerHashPattern().Match(api.Replace("\\/", "/"));
        return match.Success ? match.Groups[1].Value : null;
    }

    private async Task<PlayerCipherConfig?> FaradayConfigAsync(string hash, CancellationToken cancel)
    {
        var text = await GetTextAsync(FaradayConfigUrl, 4 * 1024 * 1024, cancel);
        return text is null ? null : ParseFaraday(text, hash);
    }

    /// <summary>faraday の表から、ハッシュ（または別名）に合う行を読む。形が合わない行は使わない。</summary>
    public static PlayerCipherConfig? ParseFaraday(string text, string hash)
    {
        if (JsonNode.Parse(text) is not JsonObject root || root.Path("schemaVersion").Long() is not 1) return null;
        if (root.Path("players") is not JsonObject players) return null;
        JsonNode? entry = players[hash];
        entry ??= players.Select(p => p.Value).FirstOrDefault(v => v.Path("aliases") is JsonArray aliases && aliases.Any(a => a.Str() == hash));
        var sig = entry.Path("sig").Str();
        var nClass = entry.Path("nClass").Str();
        var sts = entry.Path("sts").Long();
        if (sig is null || nClass is null || sts is not > 0) return null;
        if (!SigPattern().IsMatch(sig) || !NClassPattern().IsMatch(nClass)) return null;
        return new PlayerCipherConfig(sig, nClass, (int)sts);
    }

    private async Task<string?> GetTextAsync(string url, int maxBytes, CancellationToken cancel)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", BrowserUserAgent);
        request.Headers.TryAddWithoutValidation("Referer", "https://www.youtube.com/");
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
        if (!response.IsSuccessStatusCode) return null;
        if (response.Content.Headers.ContentLength > maxBytes) return null;
        var text = await response.Content.ReadAsStringAsync(cancel);
        return text.Length > maxBytes ? null : text;
    }

    [GeneratedRegex("/s/player/([a-f0-9]{8})/")]
    private static partial Regex PlayerHashPattern();

    [GeneratedRegex(@"^[A-Za-z0-9$_]{1,8}\(\d+,\d+,INPUT\)$")]
    private static partial Regex SigPattern();

    [GeneratedRegex(@"^[A-Za-z0-9$_]{1,8}$")]
    private static partial Regex NClassPattern();
}
