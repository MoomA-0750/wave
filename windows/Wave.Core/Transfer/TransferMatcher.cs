using System.Text;
using System.Text.RegularExpressions;
using Wave.Core.Music;

namespace Wave.Core.Transfer;

public enum MatchStatus { Pending, Matched, Review, NotFound }

/// <summary>
/// 突き合わせの候補（YouTube Music の曲・アルバム・アーティスト）。Score は 0〜1。
/// Sure はアーティストが比べられて合っている（または、比べられなくてもアルバム・長さが合っている）こと。Sure でなければ点が高くても「要確認」にする。
/// </summary>
public sealed record MatchCandidate(string Id, string Title, string Subtitle, string? ThumbnailUrl, double Score, string? Kind = null, int? DurationSeconds = null, bool Sure = true);

/// <summary>
/// Spotify の曲・アルバム・アーティストと YouTube Music の検索結果の突き合わせ（純粋な関数）。
/// 文字は NFKC・小文字・ひらがなとカタカナを同じにし、記号と空白を除いてから比べる。曲名の (feat. …)・「- Remastered」などは外して比べる。
/// 点は曲名・アーティスト・アルバム・長さ（分かるときだけ）の重み付きの平均を 0〜1 にしたもの。公式の音源（song）を MV・投稿動画より上にする。
/// 文字の種類が違う名前（漢字・かなとローマ字）は比べられないので、似ていないとはせず「分からない」（中くらいの点）にする。
/// </summary>
public static partial class TransferMatcher
{
    /// <summary>この点以上なら自動で選んだままにし、未満は「要確認」にする。</summary>
    public const double AutoThreshold = 0.72;
    public const int MaxCandidates = 5;

    // --- 文字の正規化 ---

    /// <summary>比べるための形（NFKC・小文字・カタカナをひらがなに・文字と数字だけ）。</summary>
    public static string Fold(string text)
    {
        var sb = new StringBuilder(text.Length);
        foreach (var ch in text.Normalize(NormalizationForm.FormKC).ToLowerInvariant())
        {
            var c = ch is >= 'ァ' and <= 'ヶ' ? (char)(ch - 0x60) : ch;
            if (char.IsLetterOrDigit(c)) sb.Append(c);
        }
        return sb.ToString();
    }

    [GeneratedRegex(@"\s*[\(\[（［【]\s*(?:feat\.?|ft\.?|featuring|with|\+)\s[^\)\]）］】]*[\)\]）］】]", RegexOptions.IgnoreCase)]
    private static partial Regex FeatInBrackets();

    [GeneratedRegex(@"\s+(?:feat\.?|ft\.?|featuring)\s.*$", RegexOptions.IgnoreCase)]
    private static partial Regex FeatTrailing();

    [GeneratedRegex(@"\s*[-–—]\s*(?:\d{4}\s+)?(?:digital(?:ly)?\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?\s*$", RegexOptions.IgnoreCase)]
    private static partial Regex RemasterDash();

    [GeneratedRegex(@"\s*[\(\[](?:\d{4}\s+)?(?:digital(?:ly)?\s+)?remaster(?:ed)?[^\)\]]*[\)\]]", RegexOptions.IgnoreCase)]
    private static partial Regex RemasterBrackets();

    /// <summary>曲名・アルバム名から、比べるときに邪魔な飾り（feat.・リマスター）を外す。外して空になるなら元のまま。</summary>
    public static string StripDecorations(string title)
    {
        var s = FeatInBrackets().Replace(title, "");
        s = RemasterBrackets().Replace(s, "");
        s = RemasterDash().Replace(s, "");
        s = FeatTrailing().Replace(s, "");
        s = s.Trim();
        return s.Length > 0 ? s : title.Trim();
    }

    [GeneratedRegex(@"\s+[-–—]\s+[A-Za-z0-9][A-Za-z0-9 '’.,!?&:\-]*$")]
    private static partial Regex RomanizationDash();

    /// <summary>YouTube Music の曲名の後ろに付いた読み（「月は美しく - Tsuki Ha Utsukushiku」）を外す。前が漢字・かなのときだけ。</summary>
    public static string StripRomanization(string title)
    {
        var m = RomanizationDash().Match(title);
        return m.Success && title[..m.Index].Any(IsCjk) ? title[..m.Index].Trim() : title;
    }

    private static bool IsCjk(char c) =>
        c is >= '぀' and <= 'ヿ' or >= '㐀' and <= '鿿' or >= '豈' and <= '﫿' or >= '가' and <= '힯' or >= 'ｦ' and <= 'ﾟ';

    private static bool IsLatin(char c) => c is >= 'a' and <= 'z' || (c > '¿' && c < 'ɐ');

    /// <summary>片方が漢字・かな（・ハングル）だけ、もう片方がローマ字だけで、文字の種類が違う。</summary>
    public static bool DifferentScripts(string a, string b)
    {
        var fa = Fold(a);
        var fb = Fold(b);
        bool cjkA = fa.Any(IsCjk), cjkB = fb.Any(IsCjk), latA = fa.Any(IsLatin), latB = fb.Any(IsLatin);
        return (cjkA && !latA && latB && !cjkB) || (cjkB && !latB && latA && !cjkA);
    }

    /// <summary>折りたたんだ文字の似ている度合い（0〜1。編集距離の比）。</summary>
    public static double Similarity(string a, string b)
    {
        var fa = Fold(a);
        var fb = Fold(b);
        if (fa.Length == 0 || fb.Length == 0) return fa == fb ? 1 : 0;
        if (fa == fb) return 1;
        var distance = Levenshtein(fa, fb);
        return Math.Max(0, 1 - (double)distance / Math.Max(fa.Length, fb.Length));
    }

    /// <summary>名前の似ている度合い。文字の種類が違えば null（分からない）。</summary>
    public static double? NameSimilarity(string a, string b, NameAliases? aliases = null)
    {
        if (aliases?.Same(a, b) == true) return 1;
        if (DifferentScripts(a, b)) return null;
        var sim = Similarity(a, b);
        // 「The Beatles」と「Beatles」、「緑黄色社会」と「緑黄色社会 (Ryokuoushoku Shakai)」のように片方が他方を含むなら近いとみなす
        var fa = Fold(a);
        var fb = Fold(b);
        if (fa.Length >= 2 && fb.Length >= 2 && (fa.Contains(fb) || fb.Contains(fa))) sim = Math.Max(sim, 0.85);
        return sim;
    }

    private static int Levenshtein(string a, string b)
    {
        var prev = new int[b.Length + 1];
        var cur = new int[b.Length + 1];
        for (var j = 0; j <= b.Length; j++) prev[j] = j;
        for (var i = 1; i <= a.Length; i++)
        {
            cur[0] = i;
            for (var j = 1; j <= b.Length; j++)
                cur[j] = Math.Min(Math.Min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1));
            (prev, cur) = (cur, prev);
        }
        return prev[b.Length];
    }

    /// <summary>アーティストの組のうち一番似ているもの。どれも比べられなければ null。</summary>
    private static double? BestName(IEnumerable<string> ours, IEnumerable<string> theirs, NameAliases? aliases)
    {
        double? best = null;
        foreach (var a in ours)
            foreach (var b in theirs)
                if (NameSimilarity(a, b, aliases) is { } s && (best is null || s > best)) best = s;
        return best;
    }

    /// <summary>比べられない値（文字の種類が違う名前）の代わりの点。合っている候補（1）より下、違う候補（0 近く）より上。</summary>
    private const double Unknown = 0.4;

    /// <summary>重み付きの平均。重みが 0 のもの（無い値）は入れない。</summary>
    private static double Weighted(params (double Value, double Weight)[] parts)
    {
        double sum = 0, weights = 0;
        foreach (var (value, weight) in parts)
        {
            if (weight <= 0) continue;
            sum += value * weight;
            weights += weight;
        }
        return weights == 0 ? 0 : sum / weights;
    }

    /// <summary>検索の順の小さな加点（YouTube の検索の上位ほど少し上げる）。</summary>
    private static double RankBonus(int rank) => 0.03 * Math.Max(0, MaxCandidates - rank) / MaxCandidates;

    // --- 曲 ---

    /// <summary>曲を探す検索の語（「アーティスト 曲名」。曲名は飾りを外す）。</summary>
    public static string SongQuery(SpotifyTrack track, NameAliases? aliases = null) =>
        (track.Artists.Count > 0 ? ArtistQuery(track.Artists[0], aliases) + " " : "") + StripDecorations(track.Title);

    /// <summary>検索に使うアーティストの名前（YouTube Music での書き方が分かればそちら）。</summary>
    public static string ArtistQuery(string name, NameAliases? aliases = null) => aliases?.Native(name) ?? name;

    /// <summary>曲の長さの近さ（sigma67/spotify_to_ytmusic と同じ 1-|d-D|*2/D）。どちらかが分からなければ null。</summary>
    public static double? DurationScore(int? spotifyMs, int? youtubeSeconds)
    {
        if (spotifyMs is not { } ms || ms <= 0 || youtubeSeconds is not { } ys || ys <= 0) return null;
        var d = ms / 1000.0;
        return Math.Clamp(1 - Math.Abs(d - ys) * 2 / d, 0, 1);
    }

    public static double ScoreSong(SpotifyTrack track, Song song, int rank, NameAliases? aliases = null) => ScoreSongSure(track, song, rank, aliases).Score;

    /// <summary>曲の点と、確かと言えるか（アーティストが合う、または比べられなくてもアルバムか長さが合う）。</summary>
    public static (double Score, bool Sure) ScoreSongSure(SpotifyTrack track, Song song, int rank, NameAliases? aliases = null)
    {
        var plain = StripRomanization(song.Title);
        var title = new[] { Similarity(track.Title, song.Title), Similarity(StripDecorations(track.Title), StripDecorations(song.Title)), Similarity(track.Title, plain), Similarity(StripDecorations(track.Title), StripDecorations(plain)) }.Max();
        var artist = track.Artists.Count == 0 || song.Artists.Count == 0 ? null : BestName(track.Artists, song.Artists.Select(a => a.Name), aliases);
        double? album = track.Album is { } a && song.Album?.Title is { } b
            ? Math.Max(Similarity(a, b), Similarity(StripDecorations(a), StripDecorations(b)))
            : null;
        // アルバム名の文字の種類が違えば比べない
        if (track.Album is { } a2 && song.Album?.Title is { } b2 && DifferentScripts(a2, b2)) album = null;
        var duration = DurationScore(track.DurationMs, song.DurationSeconds);
        var score = Weighted((title, 0.45), (artist ?? Unknown, 0.30), (album ?? 0, album is null ? 0 : 0.10), (duration ?? 0, duration is null ? 0 : 0.25));
        // 曲名が違えば、ほかが合っていても低くする（同じアーティストの別の曲を選ばない）
        if (title < 0.5) score *= 0.6;
        var sure = artist is { } known ? known >= 0.6 : album >= 0.8 || duration >= 0.8;
        var kind = song.Kind switch
        {
            Song.KindSong => 1.0,
            Song.KindMusicVideo => 0.92,
            Song.KindUserVideo => 0.8,
            _ => 0.9,
        };
        return (Math.Clamp(score * kind + RankBonus(rank), 0, 1), sure);
    }

    public static List<MatchCandidate> RankSongs(SpotifyTrack track, IEnumerable<Song> results, NameAliases? aliases = null)
    {
        var seen = new HashSet<string>();
        return results.Where(s => !s.IsLive && seen.Add(s.VideoId))
            .Select((s, i) =>
            {
                var (score, sure) = ScoreSongSure(track, s, i, aliases);
                return new MatchCandidate(s.VideoId, s.Title, s.Subtitle, s.ThumbnailUrl, score, s.Kind, s.DurationSeconds, sure);
            })
            .OrderByDescending(c => c.Score)
            .Take(MaxCandidates)
            .ToList();
    }

    // --- アルバム ---

    public static string AlbumQuery(SpotifyAlbum album, NameAliases? aliases = null) =>
        (album.Artists.Count > 0 ? ArtistQuery(album.Artists[0], aliases) + " " : "") + StripDecorations(album.Title);

    /// <summary>アルバムの点。アーティストは検索結果の補足（「アルバム • アーティスト • 年」）の各部分と比べる。</summary>
    public static double ScoreAlbum(SpotifyAlbum album, AlbumSummary result, int rank, NameAliases? aliases = null) => ScoreAlbumSure(album, result, rank, aliases).Score;

    public static (double Score, bool Sure) ScoreAlbumSure(SpotifyAlbum album, AlbumSummary result, int rank, NameAliases? aliases = null)
    {
        var title = DifferentScripts(album.Title, result.Title)
            ? (double?)null
            : Math.Max(Similarity(album.Title, result.Title), Similarity(StripDecorations(album.Title), StripDecorations(result.Title)));
        // 種類（アルバム・シングル）と年は名前ではないので比べない
        var parts = (result.Subtitle ?? "").Split('•', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(p => p.Any(char.IsLetter) && !KindWords.Contains(p)).ToArray();
        var artist = album.Artists.Count == 0 || parts.Length == 0 ? null : BestName(album.Artists, parts, aliases);
        var score = Weighted((title ?? Unknown, 0.65), (artist ?? Unknown, 0.35));
        if (title is < 0.5) score *= 0.6;
        // アーティストが合うか、アーティストの分からないアルバムで題がほぼ同じなら確か
        var sure = artist is { } a ? a >= 0.6 && title is not null : album.Artists.Count == 0 && title >= 0.9;
        return (Math.Clamp(score + RankBonus(rank), 0, 1), sure);
    }

    private static readonly HashSet<string> KindWords = new(StringComparer.OrdinalIgnoreCase) { "アルバム", "シングル", "EP", "Album", "Single" };

    public static List<MatchCandidate> RankAlbums(SpotifyAlbum album, IEnumerable<AlbumSummary> results, NameAliases? aliases = null)
    {
        var seen = new HashSet<string>();
        return results.Where(a => seen.Add(a.BrowseId))
            .Select((a, i) =>
            {
                var (score, sure) = ScoreAlbumSure(album, a, i, aliases);
                return new MatchCandidate(a.BrowseId, a.Title, a.Subtitle ?? "", a.ThumbnailUrl, score, Sure: sure);
            })
            .OrderByDescending(c => c.Score)
            .Take(MaxCandidates)
            .ToList();
    }

    // --- アーティスト ---

    /// <summary>アーティストの点。名前の文字の種類が違えば 0.55 と検索の順の加点（「要確認」になる）。</summary>
    public static double ScoreArtist(SpotifyArtist artist, ArtistSummary result, int rank, NameAliases? aliases = null)
    {
        var name = NameSimilarity(artist.Name, result.Name, aliases);
        return Math.Clamp((name ?? 0.55) + (name is null ? RankBonus(rank) * 3 : RankBonus(rank)), 0, 1);
    }

    public static List<MatchCandidate> RankArtists(SpotifyArtist artist, IEnumerable<ArtistSummary> results, NameAliases? aliases = null)
    {
        var seen = new HashSet<string>();
        return results.Where(a => seen.Add(a.BrowseId))
            .Select((a, i) => new MatchCandidate(a.BrowseId, a.Name, a.Subtitle ?? "", a.ThumbnailUrl, ScoreArtist(artist, a, i, aliases), Sure: aliases?.Same(artist.Name, a.Name) == true || !DifferentScripts(artist.Name, a.Name)))
            .OrderByDescending(c => c.Score)
            .Take(MaxCandidates)
            .ToList();
    }

    /// <summary>候補から状態を決める（0件は見つからない、一番上が閾値未満は要確認）。</summary>
    public static MatchStatus StatusOf(IReadOnlyList<MatchCandidate> candidates) =>
        candidates.Count == 0 ? MatchStatus.NotFound
        : candidates[0].Score >= AutoThreshold && candidates[0].Sure ? MatchStatus.Matched
        : MatchStatus.Review;

    /// <summary>2つの候補の一覧を合わせ、点の高い順に上位だけ残す（同じ ID は点の高い方）。</summary>
    public static List<MatchCandidate> Merge(IEnumerable<MatchCandidate> a, IEnumerable<MatchCandidate> b) =>
        a.Concat(b).GroupBy(c => c.Id).Select(g => g.MaxBy(c => c.Score)!).OrderByDescending(c => c.Score).Take(MaxCandidates).ToList();
}
