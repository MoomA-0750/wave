using System.Text.Json;
using System.Text.Json.Nodes;
using Wave.Core.Connect;

namespace Wave.Core.Music;

/// <summary>
/// RSS で登録した番組と、エピソードの聴いたところ（端末ごと、ADR-0012。Android の FeedLibraryState と同じ形の JSON で置く）。
/// 書き換えは新しい値を返す（元の値は変えない）。
/// </summary>
public sealed record FeedLibraryState(IReadOnlyList<FeedSubscription> Subscriptions, IReadOnlyDictionary<string, EpisodePosition> Positions)
{
    public const int MaxPositions = 1000;

    public static FeedLibraryState Empty { get; } = new([], new Dictionary<string, EpisodePosition>());

    /// <summary>外した番組と、外した時刻（ほかの端末へ「外した」を伝える印、ADR-0013）。</summary>
    public IReadOnlyDictionary<string, long> Removed { get; init; } = new Dictionary<string, long>();

    public bool IsSubscribed(string feedUrl) => Subscriptions.Any(s => s.FeedUrl == feedUrl);

    /// <summary>登録する（新しいものが先。同じフィードは先頭へ動かす。AddedAtMs が登録した時刻）。外した印は消す。</summary>
    public FeedLibraryState Subscribe(FeedSubscription subscription) =>
        this with
        {
            Subscriptions = [subscription, .. Subscriptions.Where(s => s.FeedUrl != subscription.FeedUrl)],
            Removed = Removed.Where(r => r.Key != subscription.FeedUrl).ToDictionary(r => r.Key, r => r.Value),
        };

    /// <summary>外す（atMs は外した時刻。印として残し、ほかの端末に伝える）。</summary>
    public FeedLibraryState Unsubscribe(string feedUrl, long atMs = 0) =>
        this with
        {
            Subscriptions = Subscriptions.Where(s => s.FeedUrl != feedUrl).ToList(),
            Removed = new Dictionary<string, long>(Removed) { [feedUrl] = atMs },
        };

    /// <summary>端末の間で合わせる形（ADR-0013。Kotlin の toBody と同じ）。</summary>
    public LibraryBody ToBody()
    {
        var feeds = new Dictionary<string, LibraryFeed>();
        foreach (var (url, at) in Removed) feeds[url] = new LibraryFeed { Title = "", Removed = true, UpdatedAtMs = at };
        foreach (var s in Subscriptions)
            feeds[s.FeedUrl] = new LibraryFeed { Title = s.Title, Author = s.Author, ThumbnailUrl = s.ThumbnailUrl, AddedAtMs = s.AddedAtMs, Removed = false, UpdatedAtMs = s.AddedAtMs };
        return new LibraryBody
        {
            Feeds = feeds,
            Positions = Positions.ToDictionary(p => p.Key, p => new LibraryPosition { PositionSeconds = p.Value.PositionSeconds, DurationSeconds = p.Value.DurationSeconds, Finished = p.Value.Finished, UpdatedAtMs = p.Value.UpdatedAtMs }),
        };
    }

    /// <summary>ほかの端末と合わせた結果を取り込む。番組・エピソードごとに、手元より新しいものだけを当てる。</summary>
    public FeedLibraryState Apply(LibraryBody body)
    {
        var next = this;
        foreach (var (url, feed) in body.Feeds)
        {
            if (!Feed.IsAllowedFeedUrl(url)) continue;
            long? localAt = next.Subscriptions.FirstOrDefault(s => s.FeedUrl == url)?.AddedAtMs ?? (next.Removed.TryGetValue(url, out var removedAt) ? (long?)removedAt : null);
            if (localAt is { } at && at >= feed.UpdatedAtMs) continue;
            next = feed.Removed
                ? next.Unsubscribe(url, feed.UpdatedAtMs)
                : next.Subscribe(new FeedSubscription(url, feed.Title, feed.Author, feed.ThumbnailUrl is { } thumbnail && Feed.IsAllowedFeedUrl(thumbnail) ? thumbnail : null, feed.UpdatedAtMs));
        }
        // 登録の並びは登録した時刻の新しい順
        next = next with { Subscriptions = next.Subscriptions.OrderByDescending(s => s.AddedAtMs).ToList() };
        var positions = new Dictionary<string, EpisodePosition>(next.Positions);
        foreach (var (url, p) in body.Positions)
        {
            if (positions.TryGetValue(url, out var local) && local.UpdatedAtMs >= p.UpdatedAtMs) continue;
            positions[url] = new EpisodePosition(p.PositionSeconds, p.DurationSeconds, p.Finished, p.UpdatedAtMs);
        }
        return next.Trimmed(positions);
    }

    private FeedLibraryState Trimmed(Dictionary<string, EpisodePosition> positions) =>
        this with { Positions = positions.Count <= MaxPositions ? positions : positions.OrderByDescending(p => p.Value.UpdatedAtMs).Take(MaxPositions).ToDictionary(p => p.Key, p => p.Value) };

    /// <summary>番組の名前・画像が変わっていたら、登録の表示を合わせる（登録の順と時刻は変えない）。</summary>
    public FeedLibraryState Refresh(FeedSubscription subscription) =>
        this with
        {
            Subscriptions = Subscriptions.Select(s => s.FeedUrl == subscription.FeedUrl && s with { AddedAtMs = 0 } != subscription with { AddedAtMs = 0 }
                ? subscription with { AddedAtMs = s.AddedAtMs }
                : s).ToList(),
        };

    /// <summary>位置を覚える。多くなりすぎたら古いものから捨てる。</summary>
    public FeedLibraryState Remember(string audioUrl, EpisodePosition position)
    {
        var next = new Dictionary<string, EpisodePosition>(Positions) { [audioUrl] = position };
        if (next.Count > MaxPositions)
            next = next.OrderByDescending(p => p.Value.UpdatedAtMs).Take(MaxPositions).ToDictionary(p => p.Key, p => p.Value);
        return this with { Positions = next };
    }

    /// <summary>中身が同じか（並びも含めて）。</summary>
    public bool SameAs(FeedLibraryState other) =>
        Subscriptions.SequenceEqual(other.Subscriptions) && Positions.Count == other.Positions.Count &&
        Positions.All(p => other.Positions.TryGetValue(p.Key, out var o) && o == p.Value) &&
        Removed.Count == other.Removed.Count && Removed.All(r => other.Removed.TryGetValue(r.Key, out var o) && o == r.Value);

    /// <summary>
    /// Android と同じ形: {"subscriptions":[{feedUrl,title,author,thumbnailUrl,addedAtMs}],"positions":{"URL":{positionSeconds,durationSeconds,finished,updatedAtMs}},"removed":{"URL":外した時刻}}
    /// （既定の値は書かない）。
    /// </summary>
    public static string Encode(FeedLibraryState state)
    {
        var root = new JsonObject();
        if (state.Subscriptions.Count > 0)
        {
            var subscriptions = new JsonArray();
            foreach (var s in state.Subscriptions)
            {
                var o = new JsonObject { ["feedUrl"] = s.FeedUrl, ["title"] = s.Title };
                if (s.Author is not null) o["author"] = s.Author;
                if (s.ThumbnailUrl is not null) o["thumbnailUrl"] = s.ThumbnailUrl;
                if (s.AddedAtMs != 0) o["addedAtMs"] = s.AddedAtMs;
                subscriptions.Add(o);
            }
            root["subscriptions"] = subscriptions;
        }
        if (state.Positions.Count > 0)
        {
            var positions = new JsonObject();
            foreach (var (url, p) in state.Positions)
            {
                var o = new JsonObject { ["positionSeconds"] = p.PositionSeconds };
                if (p.DurationSeconds is { } d) o["durationSeconds"] = d;
                if (p.Finished) o["finished"] = true;
                if (p.UpdatedAtMs != 0) o["updatedAtMs"] = p.UpdatedAtMs;
                positions[url] = o;
            }
            root["positions"] = positions;
        }
        if (state.Removed.Count > 0)
        {
            var removed = new JsonObject();
            foreach (var (url, at) in state.Removed) removed[url] = at;
            root["removed"] = removed;
        }
        return root.ToJsonString();
    }

    /// <summary>読めなければ null（知らない項目は無視する）。</summary>
    public static FeedLibraryState? Decode(string text)
    {
        try
        {
            if (JsonNode.Parse(text) is not JsonObject root) return null;
            var subscriptions = new List<FeedSubscription>();
            if (root["subscriptions"] is JsonArray list)
            {
                foreach (var node in list)
                {
                    if (node is not JsonObject o || Str(o, "feedUrl") is not { } feed || Str(o, "title") is not { } title) return null;
                    subscriptions.Add(new FeedSubscription(feed, title, Str(o, "author"), Str(o, "thumbnailUrl"), Long(o, "addedAtMs") ?? 0));
                }
            }
            var positions = new Dictionary<string, EpisodePosition>();
            if (root["positions"] is JsonObject map)
            {
                foreach (var (url, node) in map)
                {
                    if (node is not JsonObject o || Long(o, "positionSeconds") is not { } position) return null;
                    positions[url] = new EpisodePosition((int)position, (int?)Long(o, "durationSeconds"),
                        o["finished"] is JsonValue f && f.GetValueKind() == JsonValueKind.True, Long(o, "updatedAtMs") ?? 0);
                }
            }
            var removed = new Dictionary<string, long>();
            if (root["removed"] is JsonObject marks)
            {
                foreach (var (url, node) in marks)
                {
                    if (node is not JsonValue v || v.GetValueKind() != JsonValueKind.Number) return null;
                    removed[url] = v.GetValue<long>();
                }
            }
            return new FeedLibraryState(subscriptions, positions) { Removed = removed };
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException or FormatException or OverflowException)
        {
            return null;
        }
    }

    private static string? Str(JsonObject o, string key) => o[key] is JsonValue v && v.GetValueKind() == JsonValueKind.String ? v.GetValue<string>() : null;

    private static long? Long(JsonObject o, string key) => o[key] is JsonValue v && v.GetValueKind() == JsonValueKind.Number ? v.GetValue<long>() : null;
}

/// <summary>
/// FeedLibraryState を持ち、変わるたびに save で書く（Android の FeedLibrary と同じ）。どのスレッドから呼んでもよい。
/// Changed は変わったスレッドで呼ばれる。
/// </summary>
public sealed class FeedLibrary(FeedLibraryState initial, Func<long> nowMs, Action<FeedLibraryState> save)
{
    private readonly object gate = new();
    private FeedLibraryState state = initial;

    public FeedLibraryState State
    {
        get
        {
            lock (gate) return state;
        }
    }

    /// <summary>登録・聴いたところが変わった。</summary>
    public event Action<FeedLibraryState>? Changed;

    public void Subscribe(FeedSubscription show) => Change(s => s.Subscribe(show with { AddedAtMs = nowMs() }));

    public void Unsubscribe(string feedUrl) => Change(s => s.Unsubscribe(feedUrl, nowMs()));

    /// <summary>ほかの端末と合わせた結果を取り込む（ADR-0013）。</summary>
    public void Apply(LibraryBody body) => Change(s => s.Apply(body));

    public void Refresh(FeedShow show)
    {
        if (State.IsSubscribed(show.FeedUrl)) Change(s => s.Refresh(show.Subscription));
    }

    public EpisodePosition? Position(string audioUrl) => State.Positions.GetValueOrDefault(audioUrl);

    /// <summary>聴いたところを覚える（durationSeconds が分からなければ null）。</summary>
    public void Remember(string audioUrl, int positionSeconds, int? durationSeconds) =>
        Change(s => s.Remember(audioUrl, EpisodePosition.Of(positionSeconds, durationSeconds, nowMs())));

    private void Change(Func<FeedLibraryState, FeedLibraryState> block)
    {
        FeedLibraryState after;
        lock (gate)
        {
            var before = state;
            after = block(before);
            if (after.SameAs(before)) return;
            state = after;
            save(after);
        }
        Changed?.Invoke(after);
    }
}
