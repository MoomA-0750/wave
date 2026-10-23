using System.Text;
using System.Text.Json.Nodes;

namespace Wave.Core.Lyrics;

/// <summary>
/// YouTube Music の時刻付きの歌詞（Android 版の YouTubeTimedLyricsClient と同じ）。歌詞のタブ（next で見つけた MPLY…）を
/// ANDROID_MUSIC として読むと、行ごとの時刻が付いて返る（WEB_REMIX では時刻なしだけ）。ログインは要らないので、
/// http には認証情報を持たないクライアントを渡し、匿名で送る（ADR-0003。cookie を WEB_REMIX 以外の名乗りで送らない）。
/// 送るのは歌詞のタブの ID だけ。
/// </summary>
public sealed class YouTubeTimedLyricsClient(HttpClient http, string hl, string gl)
{
    /// <summary>6.x では時刻付きの形にならない（2026-10 に確かめた）。7.27.52・8.40.54 は返る。</summary>
    public const string ClientVersion = "7.27.52";
    public const string UserAgent = $"com.google.android.apps.youtube.music/{ClientVersion} (Linux; U; Android 11) gzip";

    public abstract record Result
    {
        public sealed record Found(SongLyrics.Synced Lyrics) : Result;

        /// <summary>時刻付きが無い（MV など時刻の無い歌詞・歌詞の無い曲）。</summary>
        public sealed record NotFound : Result;

        /// <summary>通信・応答の失敗。</summary>
        public sealed record Failed : Result;
    }

    public async Task<Result> TimedAsync(string browseId, CancellationToken cancel = default)
    {
        try
        {
            var body = new JsonObject
            {
                ["context"] = new JsonObject
                {
                    ["client"] = new JsonObject { ["clientName"] = "ANDROID_MUSIC", ["clientVersion"] = ClientVersion, ["hl"] = hl, ["gl"] = gl },
                },
                ["browseId"] = browseId,
            };
            using var request = new HttpRequestMessage(HttpMethod.Post, "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            {
                Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
            };
            request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
            using var response = await http.SendAsync(request, cancel);
            if (!response.IsSuccessStatusCode) return new Result.Failed();
            var root = JsonNode.Parse(await response.Content.ReadAsStringAsync(cancel)) as JsonObject;
            if (root is null) return new Result.Failed();
            return Parse(root) is { } lyrics ? new Result.Found(lyrics) : new Result.NotFound();
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return new Result.Failed();
        }
    }

    /// <summary>
    /// 応答の <c>…timedLyricsModel.lyricsData</c> の行と出典。時刻（cueRange）の無い行ばかり（MV の歌詞）・行が無いなら null。
    /// 間奏の「♪」の行は空の行にする（画面では行の区切りとして出す）。
    /// </summary>
    public static SongLyrics.Synced? Parse(JsonNode root)
    {
        if (Find(root, "timedLyricsModel")?["lyricsData"] is not JsonObject data) return null;
        var lines = new List<LyricLine>();
        if (data["timedLyricsData"] is JsonArray rows)
        {
            foreach (var row in rows)
            {
                if (row is not JsonObject line) continue;
                if ((line["cueRange"] as JsonObject)?["startTimeMilliseconds"] is not JsonValue startValue) continue;
                if (!long.TryParse(startValue.ToString(), out var start)) continue;
                var text = (line["lyricLine"] as JsonValue)?.ToString().Trim() ?? "";
                lines.Add(new LyricLine(start, text == "♪" ? "" : text));
            }
        }
        if (lines.Count == 0) return null;
        var source = (data["sourceMessage"] as JsonValue)?.ToString();
        return new SongLyrics.Synced(lines.OrderBy(l => l.StartMs).ToList(), source);
    }

    /// <summary>key の値が object のものを、深さ優先で最初に見つかったものを返す。</summary>
    private static JsonObject? Find(JsonNode? node, string key)
    {
        switch (node)
        {
            case JsonObject obj:
                if (obj[key] is JsonObject hit) return hit;
                foreach (var (_, value) in obj)
                {
                    if (Find(value, key) is { } found) return found;
                }
                return null;
            case JsonArray array:
                foreach (var item in array)
                {
                    if (Find(item, key) is { } found) return found;
                }
                return null;
            default:
                return null;
        }
    }
}
