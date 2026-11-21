using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wave.Core.Music;
using Wave.Core.Net;
using Wave.Core.Transfer;

namespace Wave.App.Services;

/// <summary>
/// 見直しの「Claude で探す」。要確認・見つからないものを1件ずつ、Claude の中継（ai-bridge の /v1/run）に頼んで YouTube Music から探してもらう。
/// Claude が使う検索は突き合わせと同じログインしない通信で、間を空け、断られたら（403）すぐ止める。YouTube の認証情報は中継へ送らない。
/// 選んだものは書き込まず、見直しの一覧に理由と一緒に出す（書くのは本人が確かめてから）。
/// </summary>
public sealed partial class SpotifyTransfer
{
    /// <summary>Claude の検索の間（ふだんの突き合わせより長めに空ける）。</summary>
    public static TimeSpan AiSearchGap = TimeSpan.FromSeconds(3);

    // 認証情報を持たないクライアント（cookie・リダイレクトなし）
    private static readonly HttpClient AiHttp = new(new SocketsHttpHandler { UseCookies = false, AllowAutoRedirect = false }) { Timeout = Timeout.InfiniteTimeSpan };

    public static bool AiConfigured => AppServices.Settings.AiUrl is { Length: > 0 } && AppServices.Settings.AiToken is { Length: > 0 };

    /// <summary>Claude で探せるもの（要確認・見つからない。本人が選んだもの・外したもの・もう Claude が見たものは除く）。</summary>
    public List<TransferItem> AiTargets() =>
        Session?.ItemsInScope().Where(i => i.Status is MatchStatus.Review or MatchStatus.NotFound && !i.Manual && !i.Excluded && !i.AiTried).ToList() ?? [];

    /// <summary>Claude で探す（items を順に。止めたところまでを保存する）。</summary>
    public async Task AskClaudeAsync(IReadOnlyList<TransferItem> items)
    {
        if (Busy is not null || Session is not { } session || items.Count == 0) return;
        if (!AiConfigured)
        {
            Set(null, L.T("Claude につながっていません。設定の「Claude」でつないでください", "Claude isn't connected. Connect it in Settings > Claude"));
            return;
        }
        cancel = new CancellationTokenSource();
        var token = cancel.Token;
        Set("ai");
        int picked = 0, sure = 0, none = 0, searches = 0;
        string? stopMessage = null;
        var writer = new MusicTransferWriter(AppServices.Music, AnonymousMusic.Value);
        var aliases = Aliases;
        var lastSearch = DateTimeOffset.MinValue;
        try
        {
            for (var n = 0; n < items.Count; n++)
            {
                var item = items[n];
                ui.TryEnqueue(() =>
                {
                    Progress = new TransferProgress("ai", n, items.Count, item.Title);
                    if (paceFrom is null) paceFrom = ("ai", n, DateTimeOffset.UtcNow);
                    Changed?.Invoke();
                });
                var seen = new Dictionary<string, MatchCandidate>();
                foreach (var c in item.Candidates) seen[c.Id] = c;
                var used = 0;
                (MatchCandidate Candidate, bool Sure, string Reason)? choice = null;
                string? notFound = null;
                var refused = false;

                async Task<(JsonNode? Result, string? Error)> Tool(string name, JsonObject args)
                {
                    if (name == "choose")
                    {
                        var id = args["id"]?.ToString() ?? "";
                        if (!seen.TryGetValue(id, out var c)) return (null, "unknown id (use an id from the tool results)");
                        choice = (c, args["sure"]?.GetValue<bool>() == true, args["reason"]?.ToString() ?? "");
                        return ("ok", null);
                    }
                    if (name == "not_found")
                    {
                        notFound = args["reason"]?.ToString() ?? "";
                        return ("ok", null);
                    }
                    if (++used > TransferAi.MaxSearches) return (null, "no more searches for this item; call choose or not_found now");
                    var wait = lastSearch + AiSearchGap - DateTimeOffset.UtcNow;
                    if (wait > TimeSpan.Zero) await Task.Delay(wait, token);
                    lastSearch = DateTimeOffset.UtcNow;
                    searches++;
                    var query = args["query"]?.ToString() ?? "";
                    List<MatchCandidate>? found = null;
                    FailureKind? failure = null;
                    int? status = null;
                    switch (name)
                    {
                        case "search_songs":
                        case "search_videos":
                        {
                            var r = name == "search_songs" ? await writer.SearchSongsAsync(query, token) : await writer.SearchTopSongsAsync(query, token);
                            if (r.IsOk) found = TransferMatcher.RankSongs(item.AsTrack(), r.Value!, aliases, TransferAi.CandidatesPerSearch);
                            else (failure, status) = (r.Failure, r.Status);
                            break;
                        }
                        case "album_songs":
                        {
                            var r = await writer.AlbumSongsAsync(args["album_id"]?.ToString() ?? "", token);
                            if (r.IsOk) found = TransferMatcher.RankSongs(item.AsTrack(), r.Value!, aliases, 50);
                            else (failure, status) = (r.Failure, r.Status);
                            break;
                        }
                        case "search_albums":
                        {
                            var r = await writer.SearchAlbumsAsync(query, token);
                            if (r.IsOk) found = TransferMatcher.RankAlbums(item.AsAlbum(), r.Value!, aliases, TransferAi.CandidatesPerSearch);
                            else (failure, status) = (r.Failure, r.Status);
                            break;
                        }
                        case "search_artists":
                        {
                            var r = await writer.SearchArtistsAsync(query, token);
                            if (r.IsOk) found = TransferMatcher.RankArtists(item.AsArtist(), r.Value!, aliases, TransferAi.CandidatesPerSearch);
                            else (failure, status) = (r.Failure, r.Status);
                            break;
                        }
                        default:
                            return (null, $"unknown tool: {name}");
                    }
                    if (found is null)
                    {
                        if (status == 403 || failure == FailureKind.NotLoggedIn)
                        {
                            refused = true;
                            cancel?.Cancel();
                        }
                        return (null, "search failed");
                    }
                    // 選べるのは、出てきた候補だけ（種類の違うもの、例えば曲の1件でアルバムを選ぶことはできない）
                    var selectable = name switch
                    {
                        "search_albums" => item.Kind == TransferKind.Album,
                        "search_artists" => item.Kind == TransferKind.Artist,
                        _ => item.Kind == TransferKind.Track,
                    };
                    if (selectable) foreach (var c in found) seen.TryAdd(c.Id, c);
                    return (TransferAi.CandidatesJson(found, name == "album_songs" ? 50 : TransferAi.CandidatesPerSearch), null);
                }

                var error = await Task.Run(() => OriginalAsync(() => RunBridgeAsync(TransferAi.Body(item, L.Ja ? "日本語" : "English", aliases), Tool, token)), token);
                if (refused)
                {
                    stopMessage = L.T("YouTube Music に検索を断られたので止めました。時間をおいてから続けてください", "YouTube Music refused searches, so this stopped. Try again later");
                    break;
                }
                if (error is not null)
                {
                    AppPaths.Log($"transfer ai failed error={error}");
                    stopMessage = L.T($"Claude が失敗しました: {error}", $"Claude failed: {error}");
                    break;
                }
                ui.TryEnqueue(() =>
                {
                    if (choice is { } c)
                    {
                        if (TransferAi.ApplyChoice(item, c.Candidate, c.Sure, c.Reason))
                        {
                            picked++;
                            if (c.Sure) sure++;
                        }
                    }
                    else
                    {
                        TransferAi.ApplyNotFound(item, notFound ?? "");
                        none++;
                    }
                    Save(session);
                    Changed?.Invoke();
                });
            }
        }
        catch (OperationCanceledException)
        {
            stopMessage ??= L.T("止めました", "Stopped");
        }
        catch (HttpRequestException)
        {
            stopMessage = L.T("Claude の中継につながりませんでした", "Couldn't reach the Claude bridge");
        }
        // 当てはめは UI のスレッドで行うので、それが済んでから終える
        var done = new TaskCompletionSource();
        ui.TryEnqueue(() => done.SetResult());
        await done.Task;
        AppPaths.Log($"transfer ai items={items.Count} picked={picked} sure={sure} not-found={none} searches={searches} stopped={stopMessage is not null}");
        Save(session);
        Set(null, stopMessage ?? L.T($"Claude が {picked} 件を選びました（確か {sure} 件）。{none} 件は見つからないと答えました", $"Claude picked {picked} ({sure} sure). It found nothing for {none}"));
        Finished?.Invoke("ai", TransferStop.None);
    }

    /// <summary>中継に頼み、道具の呼び出しに答えながら終わりまで読む。失敗したら理由を返す。</summary>
    private static async Task<string?> RunBridgeAsync(JsonObject body, Func<string, JsonObject, Task<(JsonNode? Result, string? Error)>> tool, CancellationToken token)
    {
        var settings = AppServices.Settings;
        using var request = new HttpRequestMessage(HttpMethod.Post, settings.AiUrl + "/v1/run") { Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json") };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.AiToken);
        using var response = await AiHttp.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized) return L.T("中継にトークンを断られました。設定でつなぎ直してください", "The bridge refused the token. Connect again in Settings");
        if (!response.IsSuccessStatusCode) return $"HTTP {(int)response.StatusCode}";
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync(token));
        string? session = null;
        while (await reader.ReadLineAsync(token) is { } line)
        {
            JsonObject? ev;
            try
            {
                ev = JsonNode.Parse(line) as JsonObject;
            }
            catch (JsonException)
            {
                continue;
            }
            if (ev is null) continue;
            if (ev["session"] is { } s) session = s.GetValue<string>();
            else if (ev["tool_call"] is JsonObject call)
            {
                var (result, error) = await tool(call["name"]?.GetValue<string>() ?? "", call["arguments"] as JsonObject ?? []);
                var reply = new JsonObject { ["session"] = session ?? "", ["id"] = call["id"]?.GetValue<string>() ?? "" };
                if (error is not null) reply["error"] = error;
                else reply["result"] = result;
                using var post = new HttpRequestMessage(HttpMethod.Post, settings.AiUrl + "/v1/run/result") { Content = new StringContent(reply.ToJsonString(), Encoding.UTF8, "application/json") };
                post.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.AiToken);
                (await AiHttp.SendAsync(post, token)).Dispose();
                token.ThrowIfCancellationRequested();
            }
            else if (ev["error"] is { } e) return e.ToString();
            else if (ev["done"] is not null) break;
        }
        return null;
    }
}
