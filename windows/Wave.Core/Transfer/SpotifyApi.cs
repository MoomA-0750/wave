using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Wave.Core.Transfer;

/// <summary>Spotify の Web API のトークン（アプリが動いている間だけ持つ。保存しない）。</summary>
public sealed record SpotifyToken(string AccessToken, string? RefreshToken, long ExpiresAtMs);

/// <summary>
/// Spotify の Web API へのログイン（Authorization Code + PKCE。秘密の鍵は要らない）。
/// Client ID は本人が developer.spotify.com で作ったアプリのもの。戻り先はループバックの IP（ポートは開くたびに決める。Spotify はループバックだけ動的なポートを許す）。
/// </summary>
public static class SpotifyAuth
{
    public const string Scopes = "playlist-read-private playlist-read-collaborative user-library-read user-follow-read";
    /// <summary>Spotify のアプリの設定に登録してもらう戻り先（ポート無し）。</summary>
    public const string RegisteredRedirectUri = "http://127.0.0.1/callback";

    public static string CreateVerifier()
    {
        var bytes = RandomNumberGenerator.GetBytes(48);
        return Base64Url(bytes);
    }

    public static string Challenge(string verifier) => Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));

    public static string CreateState() => Base64Url(RandomNumberGenerator.GetBytes(16));

    public static string AuthorizeUrl(string clientId, string redirectUri, string challenge, string state) =>
        "https://accounts.spotify.com/authorize?" + string.Join("&",
            "response_type=code",
            "client_id=" + Uri.EscapeDataString(clientId),
            "scope=" + Uri.EscapeDataString(Scopes),
            "redirect_uri=" + Uri.EscapeDataString(redirectUri),
            "code_challenge_method=S256",
            "code_challenge=" + challenge,
            "state=" + Uri.EscapeDataString(state));

    public static async Task<SpotifyToken?> ExchangeCodeAsync(HttpClient http, string clientId, string code, string redirectUri, string verifier, long nowMs, CancellationToken cancel = default) =>
        await TokenAsync(http, new Dictionary<string, string>
        {
            ["grant_type"] = "authorization_code",
            ["code"] = code,
            ["redirect_uri"] = redirectUri,
            ["client_id"] = clientId,
            ["code_verifier"] = verifier,
        }, null, nowMs, cancel);

    public static async Task<SpotifyToken?> RefreshAsync(HttpClient http, string clientId, string refreshToken, long nowMs, CancellationToken cancel = default) =>
        await TokenAsync(http, new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["refresh_token"] = refreshToken,
            ["client_id"] = clientId,
        }, refreshToken, nowMs, cancel);

    private static async Task<SpotifyToken?> TokenAsync(HttpClient http, Dictionary<string, string> form, string? oldRefresh, long nowMs, CancellationToken cancel)
    {
        try
        {
            using var response = await http.PostAsync("https://accounts.spotify.com/api/token", new FormUrlEncodedContent(form), cancel);
            if (!response.IsSuccessStatusCode) return null;
            return ParseToken(JsonNode.Parse(await response.Content.ReadAsStringAsync(cancel)), oldRefresh, nowMs);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            return null;
        }
    }

    /// <summary>トークンの応答。リフレッシュの応答に refresh_token が無ければ前のものを使い続ける。</summary>
    public static SpotifyToken? ParseToken(JsonNode? root, string? oldRefresh, long nowMs)
    {
        if (root is not JsonObject o || SpotifyExportReader.Str(o, "access_token") is not { } access) return null;
        var expires = SpotifyExportReader.Int(o, "expires_in") ?? 3600;
        return new SpotifyToken(access, SpotifyExportReader.Str(o, "refresh_token") ?? oldRefresh, nowMs + expires * 1000L);
    }

    private static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}

/// <summary>ブラウザから戻ってくるのを 127.0.0.1 の空いたポートで1回だけ受ける（HttpListener は URL の予約が要ることがあるので、TCP で直に受ける）。</summary>
public sealed class LoopbackReceiver : IDisposable
{
    private readonly TcpListener listener = new(IPAddress.Loopback, 0);

    public LoopbackReceiver() => listener.Start();

    public int Port => ((IPEndPoint)listener.LocalEndpoint).Port;

    public string RedirectUri => $"http://127.0.0.1:{Port}/callback";

    /// <summary>/callback への要求の問い合わせの部分（code・state・error）。ほかの道（favicon など）は 404 を返して待ち続ける。</summary>
    public async Task<Dictionary<string, string>> WaitAsync(string doneHtml, CancellationToken cancel)
    {
        while (true)
        {
            using var client = await listener.AcceptTcpClientAsync(cancel);
            await using var stream = client.GetStream();
            var line = await ReadLineAsync(stream, cancel);
            // 残りの見出しを読み捨てる（空行まで）
            while ((await ReadLineAsync(stream, cancel)).Length > 0) { }
            var target = line.Split(' ') is { Length: >= 2 } parts ? parts[1] : "";
            if (!target.StartsWith("/callback", StringComparison.Ordinal))
            {
                await WriteAsync(stream, "404 Not Found", "", cancel);
                continue;
            }
            await WriteAsync(stream, "200 OK", doneHtml, cancel);
            return ParseQuery(target);
        }
    }

    public static Dictionary<string, string> ParseQuery(string target)
    {
        var result = new Dictionary<string, string>();
        var q = target.IndexOf('?');
        if (q < 0) return result;
        foreach (var pair in target[(q + 1)..].Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = pair.IndexOf('=');
            var key = Uri.UnescapeDataString((eq < 0 ? pair : pair[..eq]).Replace('+', ' '));
            result[key] = eq < 0 ? "" : Uri.UnescapeDataString(pair[(eq + 1)..].Replace('+', ' '));
        }
        return result;
    }

    private static async Task<string> ReadLineAsync(NetworkStream stream, CancellationToken cancel)
    {
        var bytes = new List<byte>();
        var one = new byte[1];
        while (bytes.Count < 8192)
        {
            if (await stream.ReadAsync(one, cancel) == 0) break;
            if (one[0] == '\n') break;
            if (one[0] != '\r') bytes.Add(one[0]);
        }
        return Encoding.ASCII.GetString(bytes.ToArray());
    }

    private static async Task WriteAsync(NetworkStream stream, string status, string html, CancellationToken cancel)
    {
        var body = Encoding.UTF8.GetBytes(html);
        var head = Encoding.ASCII.GetBytes($"HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(head, cancel);
        await stream.WriteAsync(body, cancel);
    }

    public void Dispose() => listener.Stop();
}

/// <summary>Spotify の Web API の応答を読む（純粋な関数。試験で見本の JSON を読む）。</summary>
public static class SpotifyApiParsers
{
    public sealed record PlaylistRef(string Id, string Name, string? OwnerId, bool Collaborative);

    /// <summary>GET /me の id。</summary>
    public static string? UserId(JsonNode? root) => root is JsonObject o ? SpotifyExportReader.Str(o, "id") : null;

    /// <summary>GET /me/playlists の1ページ。</summary>
    public static (List<PlaylistRef> Items, string? Next) Playlists(JsonNode? root)
    {
        var list = new List<PlaylistRef>();
        if (root is not JsonObject o) return (list, null);
        foreach (var p in SpotifyExportReader.Get(o, "items") as JsonArray ?? [])
        {
            if (p is not JsonObject po || SpotifyExportReader.Str(po, "id") is not { } id) continue;
            var owner = SpotifyExportReader.Get(po, "owner") is JsonObject ow ? SpotifyExportReader.Str(ow, "id") : null;
            var collaborative = SpotifyExportReader.Get(po, "collaborative") is JsonValue c && c.TryGetValue<bool>(out var b) && b;
            list.Add(new PlaylistRef(id, SpotifyExportReader.Str(po, "name") ?? id, owner, collaborative));
        }
        return (list, Next(o));
    }

    /// <summary>GET /playlists/{id}/items の1ページ（曲は items[].item。前の形の items[].track も読む）。</summary>
    public static (List<SpotifyTrack> Tracks, int Episodes, int Skipped, string? Next) PlaylistItems(JsonNode? root) => TrackPage(root, "item", "track");

    /// <summary>GET /me/tracks の1ページ（items[].track）。</summary>
    public static (List<SpotifyTrack> Tracks, int Episodes, int Skipped, string? Next) SavedTracks(JsonNode? root) => TrackPage(root, "track", "item");

    private static (List<SpotifyTrack>, int, int, string?) TrackPage(JsonNode? root, string key, string altKey)
    {
        var tracks = new List<SpotifyTrack>();
        int episodes = 0, skipped = 0;
        if (root is not JsonObject o) return (tracks, 0, 0, null);
        foreach (var it in SpotifyExportReader.Get(o, "items") as JsonArray ?? [])
        {
            if (it is not JsonObject io) { skipped++; continue; }
            var node = SpotifyExportReader.Get(io, key) ?? SpotifyExportReader.Get(io, altKey);
            if (node is not JsonObject to) { skipped++; continue; }
            if (SpotifyExportReader.Str(to, "type") is "episode") { episodes++; continue; }
            if (SpotifyExportReader.ReadTrack(to) is { } t) tracks.Add(t);
            else skipped++;
        }
        return (tracks, episodes, skipped, Next(o));
    }

    /// <summary>GET /me/albums の1ページ（items[].album）。</summary>
    public static (List<SpotifyAlbum> Albums, string? Next) SavedAlbums(JsonNode? root)
    {
        var list = new List<SpotifyAlbum>();
        if (root is not JsonObject o) return (list, null);
        foreach (var it in SpotifyExportReader.Get(o, "items") as JsonArray ?? [])
        {
            if (it is not JsonObject io || SpotifyExportReader.Get(io, "album") is not JsonObject a || SpotifyExportReader.Str(a, "name") is not { } name) continue;
            list.Add(new SpotifyAlbum(name, SpotifyExportReader.Names(a, "artists"), SpotifyExportReader.Str(a, "uri")));
        }
        return (list, Next(o));
    }

    /// <summary>GET /me/following?type=artist の1ページ（artists.items、続きは artists.next）。</summary>
    public static (List<SpotifyArtist> Artists, string? Next) FollowedArtists(JsonNode? root)
    {
        var list = new List<SpotifyArtist>();
        if (root is not JsonObject o || SpotifyExportReader.Get(o, "artists") is not JsonObject artists) return (list, null);
        foreach (var it in SpotifyExportReader.Get(artists, "items") as JsonArray ?? [])
            if (it is JsonObject a && SpotifyExportReader.Str(a, "name") is { } name) list.Add(new SpotifyArtist(name, SpotifyExportReader.Str(a, "uri")));
        return (list, Next(artists));
    }

    private static string? Next(JsonObject o) => SpotifyExportReader.Str(o, "next");

    /// <summary>429 の Retry-After（秒）。無い・読めなければ 5 秒、長すぎれば 60 秒まで。</summary>
    public static TimeSpan RetryAfter(string? header) =>
        TimeSpan.FromSeconds(int.TryParse(header, out var s) && s > 0 ? Math.Min(s, 60) : 5);
}

/// <summary>Spotify の Web API から移すものを読む。429 は Retry-After だけ待ち、401 は1回だけトークンを更新し直す。</summary>
public sealed class SpotifyApiClient(HttpClient http, string clientId, SpotifyToken token, Func<long> nowMs)
{
    private const string Base = "https://api.spotify.com/v1";
    private SpotifyToken token = token;

    /// <summary>テストで待たないよう差し替える。</summary>
    internal Func<TimeSpan, CancellationToken, Task> Delay { get; init; } = Task.Delay;

    public sealed record Progress(string Stage, int Count);

    /// <summary>GET して JSON を返す。読めなければ (null, 状態)。</summary>
    public async Task<(JsonNode? Root, int Status)> GetAsync(string url, CancellationToken cancel)
    {
        var refreshed = false;
        for (var attempt = 0; attempt < 6; attempt++)
        {
            if (token.RefreshToken is { } r && nowMs() > token.ExpiresAtMs - 60_000 && !refreshed)
            {
                refreshed = true;
                if (await SpotifyAuth.RefreshAsync(http, clientId, r, nowMs(), cancel) is { } fresh) token = fresh;
            }
            HttpResponseMessage response;
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, url.StartsWith("http", StringComparison.Ordinal) ? url : Base + url);
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token.AccessToken);
                response = await http.SendAsync(request, cancel);
            }
            catch (HttpRequestException)
            {
                await Delay(TimeSpan.FromSeconds(2 << attempt), cancel);
                continue;
            }
            using (response)
            {
                var status = (int)response.StatusCode;
                if (status == 429)
                {
                    await Delay(SpotifyApiParsers.RetryAfter(response.Headers.TryGetValues("Retry-After", out var v) ? v.FirstOrDefault() : null), cancel);
                    continue;
                }
                if (status == 401 && !refreshed && token.RefreshToken is { } rt)
                {
                    refreshed = true;
                    if (await SpotifyAuth.RefreshAsync(http, clientId, rt, nowMs(), cancel) is { } fresh) { token = fresh; continue; }
                    return (null, status);
                }
                if (status >= 500)
                {
                    await Delay(TimeSpan.FromSeconds(2 << attempt), cancel);
                    continue;
                }
                if (!response.IsSuccessStatusCode) return (null, status);
                try
                {
                    return (JsonNode.Parse(await response.Content.ReadAsStringAsync(cancel)), status);
                }
                catch (System.Text.Json.JsonException)
                {
                    return (null, status);
                }
            }
        }
        return (null, 0);
    }

    /// <summary>
    /// 選んだ種類を読む。他人のプレイリスト（自分が持たず共同編集でもないもの）は中身を読めないので、名前だけ Readable=false で返す。
    /// </summary>
    public async Task<SpotifyLibrary?> ReadLibraryAsync(bool playlists, bool liked, bool albums, bool artists, Action<Progress>? progress, CancellationToken cancel)
    {
        var problems = new List<ImportProblem>();
        var me = await GetAsync("/me", cancel);
        if (SpotifyApiParsers.UserId(me.Root) is not { } userId) return null;

        var playlistList = new List<SpotifyPlaylist>();
        if (playlists)
        {
            var refs = new List<SpotifyApiParsers.PlaylistRef>();
            string? next = "/me/playlists?limit=50";
            while (next is not null)
            {
                var page = await GetAsync(next, cancel);
                if (page.Root is null) { problems.Add(new ImportProblem("/me/playlists", ImportProblemKind.ApiFailed)); break; }
                var (items, n) = SpotifyApiParsers.Playlists(page.Root);
                refs.AddRange(items);
                next = n;
                progress?.Invoke(new Progress("playlists", refs.Count));
            }
            foreach (var p in refs)
            {
                if (p.OwnerId != userId && !p.Collaborative)
                {
                    playlistList.Add(new SpotifyPlaylist(p.Name, [], Readable: false));
                    continue;
                }
                var tracks = new List<SpotifyTrack>();
                int episodes = 0, skipped = 0;
                var readable = true;
                next = $"/playlists/{Uri.EscapeDataString(p.Id)}/items?limit=50";
                while (next is not null)
                {
                    var page = await GetAsync(next, cancel);
                    if (page.Root is null)
                    {
                        // 403・404 は中身を読めないプレイリスト（他人のもの）。ほかは読めなかったこととして残す
                        if (page.Status is 403 or 404) readable = false;
                        else problems.Add(new ImportProblem(p.Name, ImportProblemKind.ApiFailed));
                        break;
                    }
                    var (t, e, s, n) = SpotifyApiParsers.PlaylistItems(page.Root);
                    tracks.AddRange(t);
                    episodes += e;
                    skipped += s;
                    next = n;
                }
                if (episodes > 0) problems.Add(new ImportProblem(p.Name, ImportProblemKind.SkippedEpisodes, episodes));
                if (skipped > 0) problems.Add(new ImportProblem(p.Name, ImportProblemKind.SkippedItems, skipped));
                playlistList.Add(new SpotifyPlaylist(p.Name, readable ? tracks : [], readable));
                progress?.Invoke(new Progress("playlist-items", playlistList.Count));
            }
        }

        var likedList = new List<SpotifyTrack>();
        if (liked)
        {
            string? next = "/me/tracks?limit=50";
            while (next is not null)
            {
                var page = await GetAsync(next, cancel);
                if (page.Root is null) { problems.Add(new ImportProblem("/me/tracks", ImportProblemKind.ApiFailed)); break; }
                var (t, _, s, n) = SpotifyApiParsers.SavedTracks(page.Root);
                likedList.AddRange(t);
                if (s > 0) problems.Add(new ImportProblem("/me/tracks", ImportProblemKind.SkippedItems, s));
                next = n;
                progress?.Invoke(new Progress("liked", likedList.Count));
            }
        }

        var albumList = new List<SpotifyAlbum>();
        if (albums)
        {
            string? next = "/me/albums?limit=50";
            while (next is not null)
            {
                var page = await GetAsync(next, cancel);
                if (page.Root is null) { problems.Add(new ImportProblem("/me/albums", ImportProblemKind.ApiFailed)); break; }
                var (a, n) = SpotifyApiParsers.SavedAlbums(page.Root);
                albumList.AddRange(a);
                next = n;
                progress?.Invoke(new Progress("albums", albumList.Count));
            }
        }

        var artistList = new List<SpotifyArtist>();
        if (artists)
        {
            string? next = "/me/following?type=artist&limit=50";
            while (next is not null)
            {
                var page = await GetAsync(next, cancel);
                if (page.Root is null) { problems.Add(new ImportProblem("/me/following", ImportProblemKind.ApiFailed)); break; }
                var (a, n) = SpotifyApiParsers.FollowedArtists(page.Root);
                artistList.AddRange(a);
                next = n;
                progress?.Invoke(new Progress("artists", artistList.Count));
            }
        }
        return new SpotifyLibrary(playlistList, likedList, albumList, artistList, problems);
    }
}
