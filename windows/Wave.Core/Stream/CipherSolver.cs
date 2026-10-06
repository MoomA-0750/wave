using System.Text.RegularExpressions;
using Jint;

namespace Wave.Core.Stream;

/// <summary>
/// faraday の表（プレイヤーごとの署名の式と n のクラス名）を使い、YouTube のプレイヤー JS を Jint の中で動かして
/// signatureCipher と n を解く（Android 版の innertubex の ZemerCipherSolver と同じやり方）。
/// Jint にはネットワーク・ファイル・アプリの値へ触れる手段を渡さない（CLR への出入りを許さない既定の設定のまま）。
/// </summary>
public sealed partial class CipherSolver
{
    private const string PlayerIifeTrailer = "})(_yt_player);";
    private const string NProbeInput = "KdrqFlzJXl9EcCwlmEy";
    private const int MaxInputLength = 64 * 1024;

    private readonly Engine engine;
    private readonly object gate = new();

    private CipherSolver(Engine engine) => this.engine = engine;

    /// <summary>プレイヤー JS を読み込み、n の変換が働くことを確かめてから返す。働かなければ例外。</summary>
    public static CipherSolver Create(string playerCode, PlayerCipherConfig config)
    {
        var engine = new Engine(options =>
        {
            options.TimeoutInterval(TimeSpan.FromSeconds(30));
            options.LimitMemory(512L * 1024 * 1024);
            options.MaxStatements(0);
            options.LimitRecursion(4096);
            options.Strict(false);
        });
        engine.Execute(GlobalsPreamble);
        engine.Execute("globalThis._yt_player = globalThis._yt_player || {};");
        engine.Execute(BuildModifiedPlayerScript(playerCode, config));
        var solver = new CipherSolver(engine);
        var probe = solver.Call("_nTransformFunc", NProbeInput);
        if (probe is null || probe == NProbeInput || probe.Length < 5 || !ValidNResult().IsMatch(probe))
            throw new InvalidOperationException("n-transform probe failed");
        return solver;
    }

    public string? SolveSignature(string input) => Call("_cipherSigFunc", input) is { Length: > 0 } s && !string.IsNullOrWhiteSpace(s) ? s : null;

    public string? SolveN(string input) => Call("_nTransformFunc", input) is { } s && !string.IsNullOrWhiteSpace(s) && s != input ? s : null;

    private string? Call(string function, string input)
    {
        if (input.Length > MaxInputLength) return null;
        try
        {
            lock (gate)
            {
                var result = engine.Invoke(function, input);
                return result.IsString() ? result.AsString() : null;
            }
        }
        catch (Exception)
        {
            return null;
        }
    }

    internal static string BuildModifiedPlayerScript(string playerCode, PlayerCipherConfig config)
    {
        var sigExpression = config.SigExpression.Replace("INPUT", "sig");
        var nExpression = NExpression(config.NClass).Replace("INPUT", "n");
        var exports =
            $"; window._cipherSigFunc=function(sig){{try{{return {sigExpression};}}catch(e){{return null;}}}};" +
            $"window._nTransformFunc=function(n){{try{{return {nExpression};}}catch(e){{return n;}}}}; ";
        var at = playerCode.LastIndexOf(PlayerIifeTrailer, StringComparison.Ordinal);
        return at >= 0 ? playerCode[..at] + exports + " " + playerCode[at..] : playerCode + "\n" + exports;
    }

    private static string NExpression(string nClass) =>
        "(function(n){try{var u=new g." + nClass + "('https://x.googlevideo.com/videoplayback?n='+n,true);" +
        "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)";

    [GeneratedRegex("^[A-Za-z0-9_-]+$")]
    private static partial Regex ValidNResult();

    /// <summary>プレイヤー JS がブラウザの外で動くための最小の置き換え（innertubex の setupYoutubeGlobals と同じ）。</summary>
    private const string GlobalsPreamble = """
        if (typeof globalThis.XMLHttpRequest === "undefined") { globalThis.XMLHttpRequest = { prototype: {} }; }
        globalThis.location = { hash: "", host: "www.youtube.com", hostname: "www.youtube.com", href: "https://www.youtube.com/watch?v=yt-dlp-wins",
            origin: "https://www.youtube.com", password: "", pathname: "/watch", port: "", protocol: "https:", search: "?v=yt-dlp-wins", username: "" };
        if (typeof globalThis.document === "undefined") { globalThis.document = Object.create(null); }
        if (typeof globalThis.navigator === "undefined") { globalThis.navigator = Object.create(null); }
        if (typeof globalThis.self === "undefined") { globalThis.self = globalThis; }
        if (typeof globalThis.window === "undefined") { globalThis.window = globalThis; }
        if (typeof globalThis.Intl === "undefined") {
            const NumberFormat = function(locale, options) { this.options = options || {}; };
            NumberFormat.supportedLocalesOf = function(locales) { return Array.isArray(locales) ? locales : [locales]; };
            NumberFormat.prototype.format = function(value) { let f = String(value); const m = this.options.minimumIntegerDigits || 0; while (f.length < m) f = "0" + f; return f; };
            const DateTimeFormat = function() {};
            DateTimeFormat.prototype.resolvedOptions = function() { return { timeZone: "UTC" }; };
            DateTimeFormat.prototype.format = function(value) { return String(value); };
            globalThis.Intl = { NumberFormat, DateTimeFormat };
        }
        """;
}

/// <summary>faraday の表の1行（プレイヤーのハッシュごと）。</summary>
public sealed record PlayerCipherConfig(string SigExpression, string NClass, int SignatureTimestamp);
