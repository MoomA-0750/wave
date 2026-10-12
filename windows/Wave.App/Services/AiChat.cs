using System.Collections.ObjectModel;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wave.Core.Ai;
using Wave.Core.Lyrics;
using Wave.Core.Music;

namespace Wave.App.Services;

/// <summary>会話の1つ分の表示（Android の AiMessage と同じ）。</summary>
public abstract record AiMessage
{
    public sealed record User(string Text) : AiMessage;
    /// <summary>Claude の答え。VideoId は時刻付きの歌詞を添えて聞いたときの曲（答えの中の [mm:ss] はこの曲が鳴っているときだけ押せる）。</summary>
    public sealed record Assistant(string Text, string? VideoId = null) : AiMessage;
    public sealed record Action(string Text) : AiMessage;
    public sealed record Songs(IReadOnlyList<Song> Items) : AiMessage;
    public sealed record Failure(string Text) : AiMessage;
}

/// <summary>
/// Claude との会話（Android の AiChat・AiTools と同じ）。家の PC などで動く中継 tools/ai-bridge 経由で Claude Code を使う。
/// 中継は状態を持たないので、毎回それまでのやりとりと、今の曲・歌詞を送る。道具の呼び出しは手元で行う。
/// 会話は端末の中の履歴（AiHistoryStore）に残し、「新しい会話」で次の会話へ移る。履歴から開いた会話はそのまま続けられる。
/// YouTube の認証情報は中継へ送らない（別の HttpClient を使う）。
/// </summary>
public sealed class AiChat
{
    public ObservableCollection<AiMessage> Messages { get; } = [];

    public bool Running { get; private set; }

    public event Action? Changed;

    /// <summary>履歴に残すときの id（新しい会話ごとに変わる。履歴から開けばその会話の id）。</summary>
    public string ConversationId { get; private set; } = Guid.NewGuid().ToString();

    private long createdAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    /// <summary>これまでの会話（%LOCALAPPDATA%\wave\ai_history.json）。</summary>
    public AiHistoryStore History { get; } = new();

    private CancellationTokenSource? cancel;
    private readonly Dictionary<string, Song> known = [];

    // 認証情報を持たないクライアント（cookie・リダイレクトなし）
    private static readonly HttpClient Http = new(new SocketsHttpHandler { UseCookies = false, AllowAutoRedirect = false }) { Timeout = Timeout.InfiniteTimeSpan };

    public bool Configured => AppServices.Settings.AiUrl is { Length: > 0 } && AppServices.Settings.AiToken is { Length: > 0 };

    /// <summary>新しい会話にする（今の会話は履歴に残す）。</summary>
    public void Clear()
    {
        Stop();
        Archive();
        Reset();
    }

    /// <summary>履歴の会話を開いて続ける。</summary>
    public void Open(string id)
    {
        if (History.Conversations.FirstOrDefault(c => c.Id == id) is not { } conversation) return;
        Stop();
        Archive();
        Messages.Clear();
        foreach (var saved in conversation.Messages)
        {
            if (MessageOf(saved) is not { } message) continue;
            if (message is AiMessage.Songs songs) foreach (var song in songs.Items) Remember(song);
            Messages.Add(message);
        }
        ConversationId = conversation.Id;
        createdAtMs = conversation.CreatedAtMs;
        Changed?.Invoke();
    }

    /// <summary>履歴から消す。開いている会話なら、新しい会話にする。</summary>
    public void Delete(string id)
    {
        if (ConversationId == id)
        {
            Stop();
            Reset();
        }
        History.Remove(id);
        Changed?.Invoke();
    }

    /// <summary>ログアウトのとき: 今の会話も履歴も消す。</summary>
    public void Wipe()
    {
        Stop();
        History.Clear();
        Reset();
    }

    private void Reset()
    {
        Messages.Clear();
        ConversationId = Guid.NewGuid().ToString();
        createdAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        Changed?.Invoke();
    }

    /// <summary>答えの中の時刻を押した: 話している曲がまだ鳴っていれば、その位置へ飛ぶ（ほかの端末で鳴っていればその端末へ）。</summary>
    public static bool Seek(string videoId, long positionMs)
    {
        var player = AppServices.Player;
        var ok = player.Current?.VideoId == videoId;
        if (ok) player.Seek(positionMs / 1000.0);
        AppPaths.Log($"ai seek ok={ok}");
        return ok;
    }

    /// <summary>今の会話を履歴に残す（自分の発言が無ければ残さない）。</summary>
    private void Archive()
    {
        var saved = Messages.Select(SavedOf).ToList();
        if (!saved.Any(m => m.Kind == AiSavedMessage.User)) return;
        History.Save(new AiConversation(ConversationId, AiHistory.Title(saved), createdAtMs, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), saved));
    }

    private static AiSavedMessage SavedOf(AiMessage message) => message switch
    {
        AiMessage.User u => new(AiSavedMessage.User, u.Text),
        AiMessage.Assistant a => new(AiSavedMessage.Assistant, a.Text, a.VideoId),
        AiMessage.Action a => new(AiSavedMessage.Action, a.Text),
        AiMessage.Songs s => new(AiSavedMessage.SongsKind, Songs: [.. s.Items]),
        AiMessage.Failure f => new(AiSavedMessage.Failure, f.Text),
        _ => new(AiSavedMessage.Action, ""),
    };

    private static AiMessage? MessageOf(AiSavedMessage saved) => saved.Kind switch
    {
        AiSavedMessage.User => new AiMessage.User(saved.Text),
        AiSavedMessage.Assistant => new AiMessage.Assistant(saved.Text, saved.VideoId),
        AiSavedMessage.Action => new AiMessage.Action(saved.Text),
        AiSavedMessage.SongsKind when saved.Songs is { Count: > 0 } songs => new AiMessage.Songs(songs),
        AiSavedMessage.Failure => new AiMessage.Failure(saved.Text),
        _ => null,
    };

    public void Stop()
    {
        cancel?.Cancel();
        cancel = null;
        Running = false;
        Changed?.Invoke();
    }

    /// <summary>接続の文字列（QR の中身 waveai://pair?u=&amp;t=&amp;n=）を読んで保存する。形が違えば false。</summary>
    public static bool Pair(string text)
    {
        if (!Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != "waveai" || uri.Host != "pair") return false;
        var query = System.Web.HttpUtility.ParseQueryString(uri.Query);
        var url = query["u"]?.TrimEnd('/');
        var token = query["t"];
        if (string.IsNullOrEmpty(url) || string.IsNullOrEmpty(token)) return false;
        // 接続先は https か、この PC の中（127.0.0.1）だけ受ける
        if (!url.StartsWith("https://", StringComparison.Ordinal) && !url.StartsWith("http://127.0.0.1:", StringComparison.Ordinal) && !url.StartsWith("http://localhost:", StringComparison.Ordinal)) return false;
        AppServices.Settings.AiUrl = url;
        AppServices.Settings.AiToken = token;
        AppServices.Settings.AiName = query["n"];
        AppServices.Settings.Save();
        return true;
    }

    public static void Unpair()
    {
        AppServices.Settings.AiUrl = null;
        AppServices.Settings.AiToken = null;
        AppServices.Settings.AiName = null;
        AppServices.Settings.Save();
    }

    /// <summary>頼みを送る。答えは Messages に流れてくる（UI スレッドから呼ぶ）。</summary>
    public async void Send(string task)
    {
        task = task.Trim();
        if (task.Length == 0 || Running) return;
        var history = Messages.Select(m => m switch
        {
            AiMessage.User u => new JsonObject { ["role"] = "user", ["content"] = u.Text },
            AiMessage.Assistant a => new JsonObject { ["role"] = "assistant", ["content"] = a.Text },
            _ => null,
        }).OfType<JsonObject>().ToList();
        Messages.Add(new AiMessage.User(task));
        Running = true;
        Archive();
        Changed?.Invoke();
        var conversationId = ConversationId;
        var source = cancel = new CancellationTokenSource();
        try
        {
            if (!Configured)
            {
                Messages.Add(new AiMessage.Failure(L.T("Claude につないでいません。設定の「Claude」でつないでください。", "Claude isn't connected yet. Connect it in Settings → Claude.")));
                return;
            }
            await RunAsync(task, history, source.Token);
        }
        catch (OperationCanceledException)
        {
        }
        catch (HttpRequestException)
        {
            Messages.Add(new AiMessage.Failure(L.T("Claude の中継に届きませんでした。中継を動かしている PC が起きているか確かめてください。", "Couldn't reach the Claude bridge. Is the PC running it?")));
        }
        finally
        {
            if (cancel == source) cancel = null;
            // 止めたあとに別の会話へ移っていれば、そちらには触れない
            if (ConversationId == conversationId)
            {
                Running = false;
                Archive();
                Changed?.Invoke();
            }
        }
    }

    private async Task RunAsync(string task, List<JsonObject> history, CancellationToken token)
    {
        var settings = AppServices.Settings;
        var body = new JsonObject { ["task"] = task, ["messages"] = new JsonArray([.. history]) };
        string? syncedVideoId = null;
        if (AppServices.Player.Current is { } song)
        {
            Remember(song);
            body["now_playing"] = new JsonObject { ["title"] = song.Title, ["artists"] = song.ArtistsText, ["album"] = song.Album?.Title ?? "", ["video_id"] = song.VideoId };
            if (await LyricsOfAsync(song, token) is { } lyrics)
            {
                // 時刻付きなら行ごとに [mm:ss] を付けて送る（Claude が歌詞を引くときに時刻を添える）
                body["lyrics"] = AiText.LyricsText(lyrics);
                if (lyrics is SongLyrics.Synced) syncedVideoId = song.VideoId;
            }
        }
        using var request = new HttpRequestMessage(HttpMethod.Post, settings.AiUrl + "/v1/agent") { Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json") };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.AiToken);
        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            Messages.Add(new AiMessage.Failure(L.T("Claude の中継にトークンを断られました。設定でつなぎ直してください。", "The Claude bridge refused the token. Connect again in Settings.")));
            return;
        }
        response.EnsureSuccessStatusCode();
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync(token));
        string? session = null;
        var appending = false;
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
            else if (ev["delta"] is { } d)
            {
                var text = d.GetValue<string>();
                if (string.IsNullOrWhiteSpace(text) && !appending) continue;
                if (appending && Messages.LastOrDefault() is AiMessage.Assistant last) Messages[^1] = last with { Text = last.Text + text };
                else Messages.Add(new AiMessage.Assistant(text.TrimStart(), syncedVideoId));
                appending = text != "\n\n";
                Changed?.Invoke();
            }
            else if (ev["tool_call"] is JsonObject call)
            {
                appending = false;
                var (result, error, shown) = await CallToolAsync(call["name"]?.GetValue<string>() ?? "", call["arguments"] as JsonObject ?? [], token);
                if (shown is not null) Messages.Add(shown);
                Changed?.Invoke();
                var reply = new JsonObject { ["session"] = session ?? "", ["id"] = call["id"]?.GetValue<string>() ?? "" };
                if (error is not null) reply["error"] = error;
                else reply["result"] = result;
                using var post = new HttpRequestMessage(HttpMethod.Post, settings.AiUrl + "/v1/agent/result") { Content = new StringContent(reply.ToJsonString(), Encoding.UTF8, "application/json") };
                post.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.AiToken);
                (await Http.SendAsync(post, token)).Dispose();
            }
            else if (ev["error"] is { } e) Messages.Add(new AiMessage.Failure(L.T($"Claude が失敗しました: {e}", $"Claude failed: {e}")));
            else if (ev["done"] is not null) break;
        }
    }

    private static async Task<SongLyrics?> LyricsOfAsync(Song song, CancellationToken token) =>
        await AppServices.Lyrics.LyricsAsync(song, token) is LyricsService.Result.Found found ? found.Lyrics : null;

    private void Remember(Song song) => known[song.VideoId] = song;

    private static JsonObject SongJson(Song s)
    {
        var o = new JsonObject { ["video_id"] = s.VideoId, ["title"] = s.Title, ["artists"] = s.ArtistsText };
        if (s.Album is { } album) o["album"] = album.Title;
        if (s.DurationSeconds is { } d) o["duration_seconds"] = d;
        if (s.Kind is { } k) o["kind"] = k;
        return o;
    }

    private (List<Song> Songs, List<string> Missing) SongsOf(JsonObject args)
    {
        var ids = (args["video_ids"] as JsonArray ?? []).Select(n => n?.ToString()).OfType<string>().ToList();
        return (ids.Where(known.ContainsKey).Select(id => known[id]).ToList(), ids.Where(id => !known.ContainsKey(id)).ToList());
    }

    private static (JsonNode?, string?, AiMessage?) Unknown(List<string> missing) => (null, $"unknown video_ids (use search_songs first): {string.Join(",", missing)}", null);

    /// <summary>道具を手元で行う（tools/ai-bridge/wave_agent_mcp.py の道具）。この会話で出てきた曲の id だけを受ける。</summary>
    private async Task<(JsonNode? Result, string? Error, AiMessage? Shown)> CallToolAsync(string name, JsonObject args, CancellationToken token)
    {
        AppPaths.Log($"ai tool={name}");
        var player = AppServices.Player;
        var music = AppServices.Music;
        switch (name)
        {
            case "now_playing":
            {
                if (player.Current is not { } song) return ("nothing is playing", null, null);
                Remember(song);
                var o = new JsonObject { ["song"] = SongJson(song) };
                if (await LyricsOfAsync(song, token) is { } lyrics) o["lyrics"] = AiText.LyricsText(lyrics);
                return (o, null, null);
            }
            case "get_queue":
            {
                var songs = player.Queue.ToList();
                songs.ForEach(Remember);
                return (new JsonObject { ["current_index"] = player.Index, ["songs"] = new JsonArray([.. songs.Skip(Math.Max(0, player.Index - 20)).Take(100).Select(SongJson)]) }, null, null);
            }
            case "search_songs":
            {
                var query = args["query"]?.ToString() ?? "";
                var limit = Math.Clamp(args["limit"]?.GetValue<int>() ?? 5, 1, 20);
                var result = await music.SearchSongsAsync(query, cancel: token);
                if (!result.IsOk) return (null, "search failed", null);
                var songs = result.Value!.Songs.Take(limit).ToList();
                songs.ForEach(Remember);
                return (new JsonArray([.. songs.Select(SongJson)]), null, new AiMessage.Action(L.T($"「{query}」を探しました", $"Searched for “{query}”")));
            }
            case "liked_songs":
            {
                var limit = Math.Clamp(args["limit"]?.GetValue<int>() ?? 50, 1, 200);
                var (songs, _) = await AppServices.Library.LikedSongsAsync();
                var picked = songs.Take(limit).ToList();
                picked.ForEach(Remember);
                return (new JsonArray([.. picked.Select(SongJson)]), null, new AiMessage.Action(L.T("高く評価した曲を見ました", "Looked at your liked songs")));
            }
            case "show_songs":
            {
                var (songs, missing) = SongsOf(args);
                return songs.Count == 0 ? Unknown(missing) : ($"shown {songs.Count}", null, new AiMessage.Songs(songs));
            }
            case "play_songs":
            {
                var (songs, missing) = SongsOf(args);
                if (songs.Count == 0) return Unknown(missing);
                player.PlayList(songs);
                return ($"playing {songs.Count} songs", null, new AiMessage.Action(L.T($"{songs.Count} 曲を再生しました", $"Playing {L.Songs(songs.Count)}")));
            }
            case "add_to_queue":
            {
                var (songs, missing) = SongsOf(args);
                if (songs.Count == 0) return Unknown(missing);
                player.QueueAll(songs, args["next"]?.GetValue<bool>() == true);
                return ($"queued {songs.Count} songs", null, new AiMessage.Action(L.T($"{songs.Count} 曲をキューに足しました", $"Added {L.Songs(songs.Count)} to the queue")));
            }
            case "list_playlists":
                return (new JsonArray([.. AppServices.Library.Editable.Select(p => new JsonObject { ["playlist_id"] = p.PlaylistId, ["title"] = p.Title })]), null, null);
            case "create_playlist":
            {
                var title = args["title"]?.ToString() ?? "";
                if (title.Length == 0) return (null, "title is empty", null);
                var (songs, _) = SongsOf(args);
                var created = await music.CreatePlaylistAsync(title, token);
                if (!created.IsOk) return (null, "create failed", null);
                var playlist = new PlaylistSummary(created.Value!, title, L.T("非公開", "Private"), null, Editable: true);
                AppServices.Library.AddCreated(playlist);
                var added = 0;
                for (var i = 0; i < songs.Count; i++)
                    if (await music.AddToPlaylistAsync(playlist.PlaylistId, songs[i].VideoId, knownEmpty: i == 0, cancel: token) == AddToPlaylistResult.Added) added++;
                return (new JsonObject { ["playlist_id"] = playlist.PlaylistId, ["added"] = added }, null, new AiMessage.Action(L.T($"「{title}」を作って {added} 曲入れました", $"Created “{title}” with {L.Songs(added)}")));
            }
            case "add_to_playlist":
            {
                var id = args["playlist_id"]?.ToString() ?? "";
                if (AppServices.Library.Editable.FirstOrDefault(p => p.PlaylistId == id) is not { } playlist) return (null, "unknown playlist_id", null);
                var (songs, missing) = SongsOf(args);
                if (songs.Count == 0) return Unknown(missing);
                int added = 0, already = 0;
                foreach (var s in songs)
                {
                    var r = await music.AddToPlaylistAsync(id, s.VideoId, cancel: token);
                    if (r == AddToPlaylistResult.Added)
                    {
                        added++;
                        AppServices.Library.Membership.NoteAdded(id, s.VideoId);
                    }
                    else if (r == AddToPlaylistResult.AlreadyInPlaylist) already++;
                }
                return (new JsonObject { ["added"] = added, ["already_in_playlist"] = already }, null, new AiMessage.Action(L.T($"「{playlist.Title}」に {added} 曲足しました", $"Added {L.Songs(added)} to “{playlist.Title}”")));
            }
            default:
                return (null, $"unknown tool: {name}", null);
        }
    }
}
