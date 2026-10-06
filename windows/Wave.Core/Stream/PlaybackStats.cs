using System.Security.Cryptography;
using Wave.Core.Net;

namespace Wave.Core.Stream;

/// <summary>
/// 再生の統計（YouTube Music の再生履歴に載せる）の送信（Android 版の ADR-0006 と同じ）。
/// 統計の URL は /player の応答のもので、music.youtube.com の /api/stats/playback に書き換えてから送る。
/// </summary>
public sealed class PlaybackStats(InnerTubeClient client)
{
    private const string Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static readonly HashSet<string> SourceHosts = ["s.youtube.com", "music.youtube.com", "www.youtube.com"];

    /// <summary>再生1回ごとの ID（16文字）。</summary>
    public static string NewCpn() => new(Enumerable.Range(0, 16).Select(_ => Alphabet[RandomNumberGenerator.GetInt32(Alphabet.Length)]).ToArray());

    /// <summary>1回の再生を報告する。送れたら HTTP の状態、送らなかったら null。</summary>
    public async Task<int?> ReportAsync(ResolvedStream stream, string cpn, double positionSeconds, CancellationToken cancel = default)
    {
        if (stream.RevealStatsUrl() is not { } raw) return null;
        var target = Target(raw, cpn, Math.Max(0, Math.Floor(positionSeconds * 1000) / 1000));
        if (target is null) return null;
        return await client.GetStatsAsync(target, stream.Generation, cancel);
    }

    internal static string? Target(string statsUrl, string cpn, double cmt)
    {
        if (!Uri.TryCreate(statsUrl, UriKind.Absolute, out var uri) || uri.Scheme != "https" || !uri.IsDefaultPort || !string.IsNullOrEmpty(uri.UserInfo)) return null;
        if (!SourceHosts.Contains(uri.Host) || uri.AbsolutePath != "/api/stats/playback") return null;
        var kept = uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Where(p => p.Split('=')[0] is not ("list" or "cpn" or "cmt" or "ver" or "c" or "prettyPrint"));
        var query = string.Join("&", kept.Concat([
            "prettyPrint=false", "ver=2", "c=" + InnerTubeClient.ClientName, "cpn=" + cpn,
            "cmt=" + cmt.ToString(System.Globalization.CultureInfo.InvariantCulture),
        ]));
        return "https://music.youtube.com/api/stats/playback?" + query;
    }
}
