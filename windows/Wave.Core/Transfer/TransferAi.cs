using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;

namespace Wave.Core.Transfer;

/// <summary>
/// 見直しの「Claude で探す」。Spotify の1件と同じものを、Claude に YouTube Music で探して選んでもらう。
/// 中継（ai-bridge の /v1/run）へ送る頼み（システムプロンプト・道具・頼みの文）と、Claude の答えの当てはめ（純粋な関数）。
/// 道具を行うのはアプリ（検索はログインしない通信で、間を空ける）。中継はアプリのことを知らない。
/// </summary>
public static class TransferAi
{
    /// <summary>1件で Claude が検索してよい回数（尽きたら選ぶか見つからないかを答えてもらう）。</summary>
    public const int MaxSearches = 8;

    /// <summary>Claude に返す候補の数（1回の検索ごと）。</summary>
    public const int CandidatesPerSearch = 8;

    public const string System =
        "あなたは音楽プレイヤー wave の「Spotify から移す」を手伝います。<item> にある Spotify の1件（曲・アルバム・アーティスト）と同じものを YouTube Music から探し、" +
        "見つかれば choose で1つ選び、無ければ not_found を呼んで終えてください。どちらかを必ず1回だけ呼んでください。\n" +
        "選び方:\n" +
        "- 同じ録音・同じアーティスト・同じ版を選ぶ。カバー・カラオケ・Instrumental・ライブ・リミックス・別人の同じ題の曲は選ばない（Spotify 側がその版ならその版を選ぶ）\n" +
        "- 公式の音源（kind が song）を一番に、無ければ公式の MV（music_video）を選ぶ。投稿された動画（user_video）は、ほかに無く公式のものと分かるときだけ\n" +
        "- 長さ（秒）が大きく違うものは別の版の疑いがある\n" +
        "- Spotify のアーティスト名はローマ字のことがある（Kenshi Yonezu は 米津玄師、Haruka Tomatsu は 戸松遥）。本来の表記を知っていれば、その表記でも探す\n" +
        "- 曲が見つからないときは、アルバムを探して album_songs でアルバムの曲目を見る、曲名だけで探す、動画も含めて探す、などを試す\n" +
        "- sure は、アーティスト・題・版が合っていると確かめられたときだけ true にする。迷うなら false にして、本人が確かめる\n" +
        "- reason は、なぜそれを選んだか・なぜ無いと考えたかを、本人が読んで分かる一文で書く\n" +
        "- 選べるのは、道具の答えに出てきた id だけ。id を作らない";

    public static JsonArray Tools() =>
    [
        Tool("search_songs", "YouTube Music で曲（公式の音源）を探す。候補の id・題・補足（アーティスト・アルバム）・長さ（秒）・種類・自動の点（0〜1）を返す。",
            new JsonObject { ["query"] = Str("検索の語（「アーティスト 曲名」など）") }, "query"),
        Tool("search_videos", "YouTube Music で、動画（MV・投稿された動画）も含めた上位の曲を探す。答えの形は search_songs と同じ。",
            new JsonObject { ["query"] = Str("検索の語") }, "query"),
        Tool("search_albums", "YouTube Music でアルバム・シングルを探す。",
            new JsonObject { ["query"] = Str("検索の語（「アーティスト アルバム名」など）") }, "query"),
        Tool("search_artists", "YouTube Music でアーティストを探す。",
            new JsonObject { ["query"] = Str("アーティストの名前") }, "query"),
        Tool("album_songs", "アルバム（search_albums の id）の曲目を返す。",
            new JsonObject { ["album_id"] = Str("search_albums で出てきた id") }, "album_id"),
        Tool("choose", "見つけたものを選んで終える。",
            new JsonObject
            {
                ["id"] = Str("道具の答えに出てきた id"),
                ["sure"] = new JsonObject { ["type"] = "boolean", ["description"] = "アーティスト・題・版が合っていると確かめられたら true" },
                ["reason"] = Str("選んだ理由（一文）"),
            }, "id", "sure", "reason"),
        Tool("not_found", "YouTube Music に無いと判断して終える。",
            new JsonObject { ["reason"] = Str("無いと考えた理由（一文）") }, "reason"),
    ];

    private static JsonObject Str(string description) => new() { ["type"] = "string", ["description"] = description };

    private static JsonObject Tool(string name, string description, JsonObject properties, params string[] required) => new()
    {
        ["name"] = name,
        ["description"] = description,
        ["inputSchema"] = new JsonObject
        {
            ["type"] = "object",
            ["properties"] = properties,
            ["required"] = new JsonArray([.. required.Select(r => (JsonNode)r)]),
        },
    };

    /// <summary>頼みの文（Spotify の1件・今の候補・答えの言語）。</summary>
    public static string Prompt(TransferItem item, string replyLanguage, NameAliases? aliases = null)
    {
        var o = new StringBuilder("<item>\n");
        o.Append("種類: ").Append(item.Kind switch { TransferKind.Album => "アルバム", TransferKind.Artist => "アーティスト", _ => "曲" }).Append('\n');
        o.Append(item.Kind == TransferKind.Artist ? "名前: " : "題: ").Append(item.Title).Append('\n');
        if (item.Artists.Count > 0) o.Append("アーティスト: ").Append(string.Join(", ", item.Artists)).Append('\n');
        foreach (var a in item.Artists.Append(item.Kind == TransferKind.Artist ? item.Title : null).OfType<string>())
            if (aliases?.Native(a) is { } native) o.Append("（").Append(a).Append(" は YouTube Music では「").Append(native).Append("」と書かれている）\n");
        if (item.Album is { Length: > 0 } album) o.Append("アルバム: ").Append(album).Append('\n');
        if (item.DurationMs is { } ms) o.Append("長さ: ").Append((ms / 1000).ToString(CultureInfo.InvariantCulture)).Append(" 秒\n");
        if (item.Isrc is { Length: > 0 } isrc) o.Append("ISRC: ").Append(isrc).Append('\n');
        o.Append("</item>\n\n");
        if (item.Candidates.Count > 0)
        {
            o.Append("<automatic_candidates>\n自動の突き合わせで見つかった候補（合っているとは限らない）:\n");
            foreach (var c in item.Candidates) o.Append(Line(c)).Append('\n');
            o.Append("</automatic_candidates>\n\n");
        }
        o.Append("この1件と同じものを YouTube Music で探し、choose か not_found で答えてください。reason は ").Append(replyLanguage).Append(" で書いてください。");
        return o.ToString();
    }

    public static JsonObject Body(TransferItem item, string replyLanguage, NameAliases? aliases = null) => new()
    {
        ["system"] = System,
        ["prompt"] = Prompt(item, replyLanguage, aliases),
        ["tools"] = Tools(),
    };

    private static string Line(MatchCandidate c) =>
        $"- id={c.Id} 題={c.Title} 補足={c.Subtitle}" + (c.DurationSeconds is { } d ? $" 長さ={d}秒" : "") + (c.Kind is { } k ? $" 種類={KindName(k)}" : "") + $" 点={c.Score:F2}";

    /// <summary>道具の答えにする候補の一覧。</summary>
    public static JsonArray CandidatesJson(IEnumerable<MatchCandidate> candidates, int take = CandidatesPerSearch) =>
        [.. candidates.Take(take).Select(c => (JsonNode)new JsonObject
        {
            ["id"] = c.Id,
            ["title"] = c.Title,
            ["subtitle"] = c.Subtitle,
            ["duration_seconds"] = c.DurationSeconds,
            ["kind"] = c.Kind is { } k ? KindName(k) : null,
            ["score"] = Math.Round(c.Score, 2),
        })];

    private static string KindName(string kind) => kind switch
    {
        Wave.Core.Music.Song.KindSong => "song",
        Wave.Core.Music.Song.KindMusicVideo => "music_video",
        Wave.Core.Music.Song.KindUserVideo => "user_video",
        _ => kind,
    };

    /// <summary>Claude が選んだ。確かなら一致にし、迷うなら要確認のまま、その候補を選んでおく。本人が選んだもの・外したものは変えない。</summary>
    public static bool ApplyChoice(TransferItem item, MatchCandidate candidate, bool sure, string reason)
    {
        if (item.Manual || item.Excluded) return false;
        var rest = item.Candidates.Where(c => c.Id != candidate.Id);
        item.Candidates = [candidate with { Sure = sure }, .. rest];
        item.ChosenId = candidate.Id;
        item.Status = sure ? MatchStatus.Matched : MatchStatus.Review;
        item.AiPicked = true;
        item.AiTried = true;
        item.AiNote = reason;
        return true;
    }

    /// <summary>
    /// Claude が見つからないと答えた。自動で選んであった候補（多くは別の曲）を外して「見つからない」にし、そのまま書き込んで別の曲が入るのを防ぐ。
    /// 候補は残すので、本人が「選ぶ」から選び直せる。本人が選んだもの・外したものは理由だけ残して変えない。
    /// </summary>
    public static void ApplyNotFound(TransferItem item, string reason)
    {
        item.AiTried = true;
        item.AiPicked = false;
        item.AiNote = reason;
        if (item.Manual || item.Excluded) return;
        item.ChosenId = null;
        item.Status = MatchStatus.NotFound;
    }
}
