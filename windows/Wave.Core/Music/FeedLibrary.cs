using System.Text.Json;
using System.Text.Json.Nodes;

namespace Wave.Core.Music;

/// <summary>
/// RSS で登録した番組と、エピソードの聴いたところ（端末ごと、ADR-0012。Android の FeedLibraryState と同じ形の JSON で置く）。
/// 書き換えは新しい値を返す（元の値は変えない）。
/// </summary>
public sealed record FeedLibraryState(IReadOnlyList<FeedSubscription> Subscriptions, IReadOnlyDictionary<string, EpisodePosition> Positions)
{
    public const int MaxPositions = 1000;

    public static FeedLibraryState Empty { get; } = new([], new Dictionary<string, EpisodePosition>());

    public bool IsSubscribed(string feedUrl) => Subscriptions.Any(s => s.FeedUrl == feedUrl);

    /// <summary>登録する（新しいものが先。同じフィードは先頭へ動かす）。</summary>
    public FeedLibraryState Subscribe(FeedSubscription subscription) =>
        this with { Subscriptions = [subscription, .. Subscriptions.Where(s => s.FeedUrl != subscription.FeedUrl)] };

    public FeedLibraryState Unsubscribe(string feedUrl) => this with { Subscriptions = Subscriptions.Where(s => s.FeedUrl != feedUrl).ToList() };

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
        Positions.All(p => other.Positions.TryGetValue(p.Key, out var o) && o == p.Value);

    /// <summary>Android と同じ形: {"subscriptions":[{feedUrl,title,author,thumbnailUrl,addedAtMs}],"positions":{"URL":{positionSeconds,durationSeconds,finished,updatedAtMs}}}（既定の値は書かない）。</summary>
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
            return new FeedLibraryState(subscriptions, positions);
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

    public void Unsubscribe(string feedUrl) => Change(s => s.Unsubscribe(feedUrl));

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
