using System.Text.RegularExpressions;

namespace Wave.Core.Transfer;

/// <summary>
/// アーティストの別の書き方（Spotify のローマ字の名前 → YouTube Music の漢字・かなの名前）。
/// Spotify は「Kenshi Yonezu」「Haruka Tomatsu」のようにローマ字で返し、YouTube Music は「米津玄師」「戸松　遥」と書くので、
/// 文字の種類が違って比べられない。同じ本人のライブラリの中で、曲名が同じで一致した曲のアーティストの組から学ぶ。
/// 1回だけの組は（カバーや客演の取り違えがあるので）使わず、2回以上で、ほかの組より十分に多いものだけを使う。
/// </summary>
public sealed partial class NameAliases
{
    public static readonly NameAliases Empty = new([]);

    // 折りたたんだ Spotify の名前 → YouTube Music の名前（元の表記）
    private readonly Dictionary<string, string> native;

    private NameAliases(Dictionary<string, string> native) => this.native = native;

    public int Count => native.Count;

    /// <summary>折りたたんだ形で、2つの名前が同じアーティストの別の書き方だと分かっている。</summary>
    public bool Same(string a, string b)
    {
        var fa = TransferMatcher.Fold(a);
        var fb = TransferMatcher.Fold(b);
        return native.TryGetValue(fa, out var na) && TransferMatcher.Fold(na) == fb
            || native.TryGetValue(fb, out var nb) && TransferMatcher.Fold(nb) == fa;
    }

    /// <summary>YouTube Music での書き方（分からなければ null）。検索の語に使う。</summary>
    public string? Native(string name) => native.GetValueOrDefault(TransferMatcher.Fold(name));

    /// <summary>手で組を与える（試験用）。</summary>
    public static NameAliases Of(params (string Spotify, string YouTube)[] pairs) =>
        new(pairs.ToDictionary(p => TransferMatcher.Fold(p.Spotify), p => p.YouTube));

    [GeneratedRegex(@",\s*|\s+&\s+")]
    private static partial Regex ArtistSeparator();

    /// <summary>候補の補足（「アーティスト, アーティスト • アルバム」）からアーティストの名前を取り出す。</summary>
    public static List<string> ArtistsOf(string subtitle) =>
        [.. ArtistSeparator().Split(subtitle.Split('•')[0]).Select(s => s.Trim()).Where(s => s.Length > 0)];

    /// <summary>一致した曲（選んだ候補と曲名が同じもの）のアーティストの組から学ぶ。</summary>
    public static NameAliases Learn(IEnumerable<TransferItem> items)
    {
        var counts = new Dictionary<string, Dictionary<string, int>>();
        foreach (var item in items)
        {
            if (item.Kind != TransferKind.Track || item.Status != MatchStatus.Matched || item.Chosen is not { } chosen) continue;
            if (TransferMatcher.Fold(TransferMatcher.StripDecorations(item.Title)) != TransferMatcher.Fold(TransferMatcher.StripDecorations(TransferMatcher.StripRomanization(chosen.Title)))) continue;
            var theirs = ArtistsOf(chosen.Subtitle);
            // どちらの側でも、相手のどの名前とも文字の種類が違う名前が1つずつだけのときに組にする
            var ours = item.Artists.Where(a => theirs.All(b => TransferMatcher.DifferentScripts(a, b))).ToList();
            var other = theirs.Where(b => item.Artists.All(a => TransferMatcher.DifferentScripts(a, b))).ToList();
            if (ours.Count != 1 || other.Count != 1) continue;
            var key = TransferMatcher.Fold(ours[0]);
            if (!counts.TryGetValue(key, out var byName)) counts[key] = byName = [];
            byName[other[0]] = byName.GetValueOrDefault(other[0]) + 1;
        }
        var native = new Dictionary<string, string>();
        foreach (var (key, byName) in counts)
        {
            var ranked = byName.OrderByDescending(p => p.Value).ToList();
            var second = ranked.Count > 1 ? ranked[1].Value : 0;
            if (ranked[0].Value >= 2 && ranked[0].Value >= second * 3) native[key] = ranked[0].Key;
        }
        return new NameAliases(native);
    }
}
