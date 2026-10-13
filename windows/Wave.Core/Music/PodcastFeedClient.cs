using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Wave.Core.Music;

/// <summary>
/// RSS のポッドキャスト（ADR-0012。Android の PodcastFeedClient と同じ）。番組を Apple の目録で探し、フィードを読む。
/// http には認証情報を持たないクライアント（CreateHttpClient）を渡す（ADR-0003）。検索語のほかは何も送らない。
/// </summary>
public sealed class PodcastFeedClient(HttpClient http, string country, string userAgent)
{
    public const string DirectoryUrl = "https://itunes.apple.com/search";

    /// <summary>長く続く番組のフィードは数 MB になる。これを超えたら読まない。</summary>
    public const long MaxFeedBytes = 40L * 1024 * 1024;

    public string UserAgent => userAgent;

    /// <summary>
    /// フィードと音声の取得用。cookie を持たず、リダイレクトは追う（計測用の転送 podtrac などを通るため）。
    /// .NET は https から http へのリダイレクトを追わないので、追うのは https の間だけになる。
    /// </summary>
    public static HttpClient CreateHttpClient() =>
        new(new SocketsHttpHandler
        {
            UseCookies = false,
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 10,
            AutomaticDecompression = DecompressionMethods.All,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            ConnectTimeout = TimeSpan.FromSeconds(15),
        })
        { Timeout = TimeSpan.FromSeconds(60) };

    /// <summary>番組を探す。読めなければ null。</summary>
    public async Task<List<PodcastSummary>?> SearchAsync(string term, CancellationToken cancel = default)
    {
        if (string.IsNullOrWhiteSpace(term)) return [];
        var trimmed = term.Trim();
        // URL を打ち込まれたら、そのフィードを読んで1件にする
        if (trimmed.Contains("://", StringComparison.Ordinal) || trimmed.StartsWith("feed:", StringComparison.Ordinal))
        {
            if (Feed.NormalizeFeedUrl(trimmed) is not { } url) return [];
            return await ShowAsync(url, cancel) is FeedResult.Ok ok ? [ok.Show.Subscription.Summary] : [];
        }
        try
        {
            var query = $"term={Uri.EscapeDataString(trimmed)}&media=podcast&entity=podcast&limit=25&country={Uri.EscapeDataString(country)}";
            using var request = new HttpRequestMessage(HttpMethod.Get, DirectoryUrl + "?" + query);
            request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
            using var response = await http.SendAsync(request, cancel);
            if (!response.IsSuccessStatusCode) return null;
            return DirectoryResults(await response.Content.ReadAsStringAsync(cancel));
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or OperationCanceledException)
        {
            return null;
        }
    }

    /// <summary>フィードを読む。</summary>
    public async Task<FeedResult> ShowAsync(string feedUrl, CancellationToken cancel = default)
    {
        if (!Feed.IsAllowedFeedUrl(feedUrl)) return new FeedResult.Failed(FeedFailure.NotAllowed);
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, feedUrl);
            request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            var status = (int)response.StatusCode;
            if (status is < 200 or > 299) return new FeedResult.Failed(FeedFailure.Http, status);
            if (response.RequestMessage?.RequestUri is { } final && final.Scheme != Uri.UriSchemeHttps) return new FeedResult.Failed(FeedFailure.NotAllowed);
            if (response.Content.Headers.ContentLength > MaxFeedBytes) return new FeedResult.Failed(FeedFailure.TooLarge);
            var bytes = await ReadLimitedAsync(response.Content, cancel);
            if (bytes is null) return new FeedResult.Failed(FeedFailure.TooLarge);
            // 長く続く番組のフィードは数 MB・数百回分ある。画面の流れを止めないよう、読むのは別のスレッドで
            var show = await Task.Run(() => FeedParser.Parse(feedUrl, Decode(bytes)), cancel);
            return show is null ? new FeedResult.Failed(FeedFailure.NotAFeed) : new FeedResult.Ok(show);
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or OperationCanceledException)
        {
            return new FeedResult.Failed(FeedFailure.Network);
        }
    }

    /// <summary>本文を上限＋1バイトまで読む（超えたら null）。</summary>
    private static async Task<byte[]?> ReadLimitedAsync(HttpContent content, CancellationToken cancel)
    {
        await using var stream = await content.ReadAsStreamAsync(cancel);
        using var buffer = new MemoryStream();
        var chunk = new byte[81920];
        while (true)
        {
            var n = await stream.ReadAsync(chunk, cancel);
            if (n == 0) break;
            buffer.Write(chunk, 0, n);
            if (buffer.Length > MaxFeedBytes) return null;
        }
        return buffer.ToArray();
    }

    /// <summary>UTF-8 で読む（Android と同じ）。先頭の BOM は落とす。</summary>
    private static string Decode(byte[] bytes)
    {
        var text = Encoding.UTF8.GetString(bytes);
        return text.Length > 0 && text[0] == '\uFEFF' ? text[1..] : text;
    }

    /// <summary>目録の応答（results[]）。フィードの URL の無い番組は落とす。</summary>
    public static List<PodcastSummary> DirectoryResults(string body)
    {
        JsonNode? root;
        try
        {
            root = JsonNode.Parse(body);
        }
        catch (JsonException)
        {
            return [];
        }
        if (root?["results"] is not JsonArray results) return [];
        var output = new List<PodcastSummary>();
        foreach (var element in results)
        {
            if (element is not JsonObject item) continue;
            string? Str(string key) => item[key] is JsonValue v && v.GetValueKind() == JsonValueKind.String && v.GetValue<string>() is { } s && !string.IsNullOrWhiteSpace(s) ? s : null;
            if (Str("feedUrl") is not { } raw || Feed.NormalizeFeedUrl(raw) is not { } feed) continue;
            if ((Str("collectionName") ?? Str("trackName")) is not { } title) continue;
            var art = Str("artworkUrl600") ?? Str("artworkUrl100");
            output.Add(new PodcastSummary(Feed.ShowBrowseId(feed), title, Str("artistName"), art is not null && Feed.IsAllowedFeedUrl(art) ? art : null));
        }
        return output.DistinctBy(p => p.BrowseId).ToList();
    }
}

public abstract record FeedResult
{
    public sealed record Ok(FeedShow Show) : FeedResult;

    /// <summary>Status は Http のときの状態コード。</summary>
    public sealed record Failed(FeedFailure Failure, int Status = 0) : FeedResult;
}

public enum FeedFailure
{
    /// <summary>https でない・形のおかしい URL。</summary>
    NotAllowed,
    Network,
    Http,
    TooLarge,
    /// <summary>RSS として読めなかった。</summary>
    NotAFeed,
}
