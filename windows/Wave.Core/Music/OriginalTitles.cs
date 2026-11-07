using System.Text.Json.Nodes;

namespace Wave.Core.Music;

/// <summary>
/// 曲名・アーティスト名・アルバム名を、YouTube が言語に合わせて書き換える前の表記（曲の言語のまま）にする（設定の「曲名を元の表記で」。Android の OriginalTitles と同じ）。
/// YouTube Music は、日本語で頼むと洋楽をカタカナに（テイラー・スウィフト）、日本語以外で頼むと邦楽の一部をローマ字に（Kenshi Yonezu）して返す。
/// どの言語で頼んでも両方が元の表記になる頼み方は無い（2026-11 に hl・gl を変えて確かめた）ので、日本語と英語の2つの応答を、
/// 行・カード・見出しごとに ID（videoId・browseId）で突き合わせ、元の表記の方を選ぶ。
///
/// 選び方（手がかりの強い順）:
/// - 英語の応答に日本の文字が残る（「夜の踊り子 - Yoru No Odoriko」）・アーティスト名の漢字・ひらがながローマ字になる → 日本の曲（日本語の方）
/// - カタカナが英字になる → 洋楽（英語の方）。カタカナ名の邦楽（サカナクション）もあるので、日本の曲の手がかりが無いときだけ
/// - 曲名の漢字がローマ字になるだけでは決めない（洋楽にも日本語の副題がある。「シェイク・イット・オフ～気にしてなんかいられないっ！！」）
/// 日本の曲と分かったアーティストは覚え（<see cref="Result.Learned"/>）、手がかりの無い行にも使う。YouTube が作ったプレイリスト・棚の見出し・ジャンル名は画面の言語のまま。
/// </summary>
public static class OriginalTitles
{
    /// <summary>画面の言語（hl）に対して、もう1つ頼む言語。</summary>
    public static string OtherLanguage(string hl) => IsJapanese(hl) ? "en" : "ja";

    public static bool IsJapanese(string hl) => hl.Split('-')[0].Equals("ja", StringComparison.OrdinalIgnoreCase);

    /// <param name="Json">突き合わせたあとの応答（書き換えるものがあれば primary を書き換えたもの）。</param>
    /// <param name="Learned">新しく分かったアーティスト（browseId → 日本の曲なら `ja`、洋楽なら `en`）。</param>
    public sealed record Result(JsonNode Json, IReadOnlyDictionary<string, string> Learned);

    /// <summary>
    /// primary（画面の言語 primaryLanguage の応答）を、other（もう1つの言語の同じ頼みの応答）と突き合わせて元の表記にする。
    /// primary はその場で書き換える。known は前に覚えたアーティスト（browseId → `ja`・`en`）。
    /// </summary>
    public static Result Merge(JsonNode primary, JsonNode other, string primaryLanguage, IReadOnlyDictionary<string, string>? known = null)
    {
        var primaryIsJa = IsJapanese(primaryLanguage);
        // もう1つの言語の応答の、行・カードの題と行き先のある文字（ID ごと）。言語で並びや中身が少し変わるので、同じ位置ではなく ID で引く
        var otherTitles = new Dictionary<string, JsonArray>();
        foreach (var (name, obj) in Groups(other))
        {
            if (GroupKey(name, obj) is not { } key) continue;
            if (TitleRuns(name, obj) is { } runs) otherTitles.TryAdd(key, runs);
        }
        var otherLinked = Linked(other, nested: true);

        var pageArtists = new HashSet<string>();
        var pairings = new List<Pairing>();
        foreach (var (name, obj) in Groups(primary))
        {
            if (GroupKey(name, obj) is not { } key || !IsMusicKey(key)) continue;
            var fields = new List<(string Primary, string Other, bool Artist)>();
            var title = TitleRuns(name, obj);
            if (title is not null && otherTitles.TryGetValue(key, out var otherTitle)) fields.Add((Text(title), Text(otherTitle), IsArtistKey(key)));
            var artists = new HashSet<string>();
            foreach (var (id, value) in Linked(obj))
            {
                if (IsArtistKey(id)) artists.Add(id[2..]);
                if (otherLinked.TryGetValue(id, out var otherValue)) fields.Add((value, otherValue, IsArtistKey(id)));
            }
            if (IsArtistKey(key)) artists.Add(key[2..]);
            if (Headers.Contains(name)) pageArtists.UnionWith(artists);

            Hint? HintOf((string Primary, string Other, bool Artist) f) => primaryIsJa ? HintFor(f.Primary, f.Other, f.Artist) : HintFor(f.Other, f.Primary, f.Artist);
            pairings.Add(new Pairing(
                name,
                key,
                artists,
                fields.Select(HintOf).OfType<Hint>().ToHashSet(),
                fields.Where(f => f.Artist).Select(HintOf).OfType<Hint>().ToHashSet()));
        }
        var learned = known is null ? new Dictionary<string, string>() : new Dictionary<string, string>(known);
        var newlyLearned = new Dictionary<string, string>();
        foreach (var p in pairings)
        {
            // アルバムの曲の行はアーティストを書かない（見出しのアーティストの曲）
            if (p.Artists.Count == 0 && p.Name == ListItem) p.Artists.UnionWith(pageArtists);
            if (!p.Hints.Contains(Hint.StrongJa)) continue;
            foreach (var a in p.Artists)
            {
                learned[a] = Ja;
                newlyLearned[a] = Ja;
            }
        }
        // カタカナが英字になったアーティストは洋楽として覚える（キューの動画の行のように、題しか違わない行に使う）。日本の曲と分かっていれば覚えない
        foreach (var p in pairings)
        {
            if (p.Hints.Contains(Hint.StrongJa) || !p.ArtistHints.Contains(Hint.WeakEn)) continue;
            foreach (var a in p.Artists.Where(a => learned.GetValueOrDefault(a) != Ja).ToList())
            {
                learned[a] = En;
                newlyLearned[a] = En;
            }
        }
        var replace = new HashSet<string>();
        foreach (var p in pairings)
        {
            string origin;
            if (p.Hints.Contains(Hint.StrongJa) || p.Artists.Any(a => learned.GetValueOrDefault(a) == Ja)) origin = Ja;
            else if (p.ArtistHints.Contains(Hint.WeakEn) || p.Hints.SetEquals([Hint.WeakEn]) || p.Artists.Any(a => learned.GetValueOrDefault(a) == En)) origin = En;
            else continue;
            if ((origin == Ja) != primaryIsJa) replace.Add(p.Key);
        }
        if (replace.Count > 0) Rewrite(primary, null, new Replacement(replace, otherTitles, otherLinked));
        return new Result(primary, newlyLearned);
    }

    private sealed record Pairing(string Name, string Key, HashSet<string> Artists, HashSet<Hint> Hints, HashSet<Hint> ArtistHints);

    private sealed record Replacement(HashSet<string> Keys, Dictionary<string, JsonArray> Titles, Dictionary<string, string> Linked);

    private enum Hint { StrongJa, WeakJa, WeakEn }

    /// <summary>ja・en の2つの表記の違いから、元の言語の手がかり。</summary>
    private static Hint? HintFor(string ja, string en, bool artist)
    {
        if (ja.Trim() == en.Trim()) return null;
        var j = Scripts.Of(ja);
        var e = Scripts.Of(en);
        if (e.Japanese || e.Katakana) return Hint.StrongJa;
        if (j.Japanese && e.Latin) return artist ? Hint.StrongJa : Hint.WeakJa;
        if (j.Katakana && e.Latin) return Hint.WeakEn;
        return null;
    }

    /// <param name="Japanese">漢字・ひらがな。</param>
    private readonly record struct Scripts(bool Japanese, bool Katakana, bool Latin)
    {
        public static Scripts Of(string s)
        {
            bool japanese = false, katakana = false, latin = false;
            foreach (var c in s)
            {
                switch ((int)c)
                {
                    case >= 0x3041 and <= 0x309F or >= 0x3400 and <= 0x4DBF or >= 0x4E00 and <= 0x9FFF or >= 0xF900 and <= 0xFAFF or >= 0xD840 and <= 0xD87F or 0x3005 or 0x3006:
                        japanese = true;
                        break;
                    case >= 0x30A0 and <= 0x30FF or >= 0x31F0 and <= 0x31FF or >= 0xFF66 and <= 0xFF9F:
                        katakana = true;
                        break;
                    default:
                        if (char.IsLetter(c) && (c < 0x2000 || c is >= (char)0xFF21 and <= (char)0xFF5A)) latin = true;
                        break;
                }
            }
            return new Scripts(japanese, katakana, latin);
        }
    }

    private const string Ja = "ja";
    private const string En = "en";
    private const string ListItem = "musicResponsiveListItemRenderer";
    private static readonly HashSet<string> Items = [ListItem, "musicTwoRowItemRenderer", "playlistPanelVideoRenderer", "musicCardShelfRenderer", "musicMultiRowListItemRenderer"];

    /// <summary>アーティストのページの見出し（アーティスト名）。</summary>
    private static readonly HashSet<string> ArtistHeaders = ["musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer"];

    /// <summary>アルバム・プレイリストのページの見出し。アーティストへのつながりがあるもの（アルバム）だけを扱う。</summary>
    private static readonly HashSet<string> AlbumHeaders = ["musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer"];
    private static readonly HashSet<string> Headers = [.. ArtistHeaders, .. AlbumHeaders];
    private static readonly HashSet<string> GroupNames = [.. Items, .. Headers];

    private static List<(string Name, JsonObject Obj)> Groups(JsonNode root)
    {
        var found = new List<(string, JsonObject)>();
        void Walk(JsonNode? node)
        {
            switch (node)
            {
                case JsonObject obj:
                    foreach (var (name, value) in obj)
                    {
                        if (GroupNames.Contains(name) && value is JsonObject g) found.Add((name, g));
                        Walk(value);
                    }
                    break;
                case JsonArray array:
                    foreach (var item in array) Walk(item);
                    break;
            }
        }
        Walk(root);
        return found;
    }

    /// <summary>文字の続き（run）・行の行き先の ID（`v:videoId`・`b:browseId`）。</summary>
    private static string? EndpointId(JsonNode? node)
    {
        var endpoint = node.Path("navigationEndpoint");
        if (endpoint.Path("watchEndpoint", "videoId").Str() is { Length: > 0 } videoId) return "v:" + videoId;
        if (endpoint.Path("browseEndpoint", "browseId").Str() is { Length: > 0 } browseId) return "b:" + browseId;
        return null;
    }

    private static bool IsArtistKey(string key) => key.StartsWith("b:UC", StringComparison.Ordinal);

    /// <summary>曲・動画・アルバム・アーティスト（YouTube のプレイリスト・気分の棚などは画面の言語のまま）。</summary>
    private static bool IsMusicKey(string key) =>
        key.StartsWith("v:", StringComparison.Ordinal) || key.StartsWith("b:MPRE", StringComparison.Ordinal) || IsArtistKey(key) || key == AlbumPage;

    private const string AlbumPage = "h:album";

    /// <summary>行・カードの題の run を持つオブジェクト（行は1列目の text、ほかは title）。</summary>
    private static JsonObject? TitleHolder(string name, JsonObject g) =>
        name == ListItem
            ? (g["flexColumns"] as JsonArray)?.FirstOrDefault().Path("musicResponsiveListItemFlexColumnRenderer", "text").Obj()
            : g["title"].Obj();

    private static JsonArray? TitleRuns(string name, JsonObject g) => TitleHolder(name, g)?["runs"] as JsonArray;

    private static string Text(JsonArray runs) => string.Concat(runs.Select(r => r.Path("text").Str() ?? ""));

    private static string? GroupKey(string name, JsonObject g)
    {
        if (ArtistHeaders.Contains(name)) return FirstString(g, "channelId") is { } channelId ? "b:" + channelId : null;
        if (AlbumHeaders.Contains(name)) return RunsIn(g).Any(r => PageType(r) == "MUSIC_PAGE_TYPE_ARTIST") ? AlbumPage : null;
        if ((g.Path("playlistItemData", "videoId").Str() ?? g["videoId"].Str()) is { Length: > 0 } videoId) return "v:" + videoId;
        if (EndpointId(g) is { } id) return id;
        return TitleRuns(name, g)?.FirstOrDefault() is { } first ? EndpointId(first) : null;
    }

    private static string? PageType(JsonNode? run) =>
        run.Path("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").Str();

    private static string? FirstString(JsonNode? node, string key) => node switch
    {
        JsonObject obj => obj[key].Str() ?? obj.Select(p => FirstString(p.Value, key)).FirstOrDefault(s => s is not null),
        JsonArray array => array.Select(item => FirstString(item, key)).FirstOrDefault(s => s is not null),
        _ => null,
    };

    /// <summary>群の中の run（nested でなければ入れ子の群の中は除く）。</summary>
    private static List<JsonNode?> RunsIn(JsonNode g, bool nested = false)
    {
        var found = new List<JsonNode?>();
        void Walk(JsonNode? node)
        {
            switch (node)
            {
                case JsonObject obj:
                    foreach (var (name, value) in obj)
                    {
                        if (GroupNames.Contains(name) && !nested) continue;
                        if (name == "runs" && value is JsonArray runs) found.AddRange(runs);
                        else Walk(value);
                    }
                    break;
                case JsonArray array:
                    foreach (var item in array) Walk(item);
                    break;
            }
        }
        Walk(g);
        return found;
    }

    /// <summary>群の中（nested なら入れ子の群の中も）の、行き先のある run の ID → 文字（出てくる順）。</summary>
    private static Dictionary<string, string> Linked(JsonNode g, bool nested = false)
    {
        var found = new Dictionary<string, string>();
        foreach (var run in RunsIn(g, nested))
        {
            if (EndpointId(run) is not { } id) continue;
            found.TryAdd(id, run.Path("text").Str() ?? "");
        }
        return found;
    }

    /// <summary>replace の群を、もう1つの言語の題・行き先のある文字に置き換える（その場で）。scope は今いる置き換える群の、もう1つの言語の文字。</summary>
    private static void Rewrite(JsonNode? node, Dictionary<string, string>? scope, Replacement replace)
    {
        switch (node)
        {
            case JsonObject obj:
                var id = scope is not null && obj["text"] is JsonValue ? EndpointId(obj) : null;
                var replacement = id is not null && IsMusicKey(id) ? scope!.GetValueOrDefault(id) : null;
                foreach (var (name, value) in obj.ToList())
                {
                    if (name == "text" && replacement is not null) obj["text"] = replacement;
                    else if (GroupNames.Contains(name) && value is JsonObject g) Group(name, g, replace);
                    else Rewrite(value, scope, replace);
                }
                break;
            case JsonArray array:
                foreach (var item in array) Rewrite(item, scope, replace);
                break;
        }
    }

    private static void Group(string name, JsonObject g, Replacement replace)
    {
        if (GroupKey(name, g) is not { } key || !replace.Keys.Contains(key))
        {
            Rewrite(g, null, replace);
            return;
        }
        // 題は、もう1つの言語の run をそのまま写す（その応答の木の一部なので複製して入れる）
        if (replace.Titles.TryGetValue(key, out var otherTitle) && TitleHolder(name, g) is { } holder && holder["runs"] is JsonArray)
            holder["runs"] = otherTitle.DeepClone();
        Rewrite(g, replace.Linked, replace);
    }
}
