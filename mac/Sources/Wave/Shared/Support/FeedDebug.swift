#if WAVE_DEBUG
import Foundation
import WaveCore

/// 開発用（Mac の DebugCommands・iOS の IOSDebugCommands から）: RSS のポッドキャストを確かめる（Android の feed_probe と同じ）。
/// 中身（題など）は記録に書かず、件数・形・時間だけを書く。
@MainActor
enum FeedDebug {
    /// `feed <検索語>`: 目録で探し、最初の番組のフィードを読む。play なら最新のエピソードを流す（`feed-play <検索語>`）。
    static func run(_ query: String, play: Bool) async {
        let feeds = AppServices.feeds!
        let term = query.isEmpty ? "rebuild" : query
        var started = Date()
        let found = await feeds.client.search(term)
        AppPaths.log("debug feed search=\(found.map { String($0.count) } ?? "failed") rss=\(found?.filter { Feed.isFeedShow($0.browseId) }.count ?? 0) " +
            "images=\(found?.filter { $0.thumbnailUrl != nil }.count ?? 0) ms=\(ms(since: started))")
        guard let first = found?.first, let url = Feed.feedUrlOf(first.browseId) else { return }
        started = Date()
        switch await feeds.client.show(url) {
        case .ok(let show):
            let readMs = ms(since: started)
            started = Date()
            let all = show.allEpisodes
            let allMs = ms(since: started)
            let page = show.toPage(saved: feeds.isSubscribed(url)) { feeds.position($0) }
            let dated = all.compactMap(\.publishedEpochSeconds)
            let newestFirst = zip(dated, dated.dropFirst()).filter { $0 >= $1 }.count
            AppPaths.log("debug feed read ms=\(readMs) items=\(show.itemCount) episodes=\(all.count) parseAllMs=\(allMs) " +
                "page=\(page.episodes.count) more=\(page.continuation != nil) durations=\(all.filter { $0.durationSeconds != nil }.count) " +
                "dates=\(dated.count) newestFirstPairs=\(newestFirst)/\(max(0, dated.count - 1)) descriptions=\(all.filter { $0.description != nil }.count) " +
                "image=\(show.thumbnailUrl != nil) saved=\(page.saved ?? false)")
            if let more = page.continuation {
                let next = show.more(more)
                AppPaths.log("debug feed more=\(next.items.count) next=\(next.continuation ?? "nil")")
            }
            guard play, !page.episodes.isEmpty else { return }
            let first = page.episodes[0]
            let stored = Feed.audioUrlOf(first.song.videoId).flatMap(feeds.position)
            AppPaths.log("debug feed-play stored=\(stored.map { "\($0.positionSeconds)/\($0.durationSeconds.map(String.init) ?? "-") finished=\($0.finished)" } ?? "none")")
            AppServices.player.playList(page.episodes.map(\.song), start: 0, source: page.playlistId)
        case .failed(let failure):
            AppPaths.log("debug feed failed \(failure) ms=\(ms(since: started))")
        }
    }

    /// `feed-state`: 今のエピソードの聴いたところ（件数と数だけ）。
    static func state() {
        let feeds = AppServices.feeds!
        let player = AppServices.player!
        let current = player.current.flatMap { Feed.audioUrlOf($0.videoId) }.flatMap(feeds.position)
        AppPaths.log("debug feed-state subscriptions=\(feeds.subscriptions.count) positions=\(feeds.state.positions.count) external=\(player.isExternal) " +
            "playing=\(player.isPlaying) position=\(Int(player.position)) duration=\(Int(player.duration)) like=\(String(describing: player.like)) " +
            "lyrics=\(String(describing: player.lyrics)) video=\(player.video != nil) " +
            "stored=\(current.map { "\($0.positionSeconds) finished=\($0.finished) percent=\($0.percent.map(String.init) ?? "-")" } ?? "none")")
    }

    /// `feed-subscribe <検索語>`: 目録の最初の番組の登録を切り替える（ライブラリの確かめ用）。
    static func toggleSubscription(_ query: String) async {
        let feeds = AppServices.feeds!
        guard let first = await feeds.client.search(query.isEmpty ? "rebuild" : query)?.first, let url = Feed.feedUrlOf(first.browseId) else { return }
        if feeds.isSubscribed(url) {
            feeds.unsubscribe(url)
        } else {
            feeds.subscribe(FeedSubscription(feedUrl: url, title: first.title, author: first.author, thumbnailUrl: first.thumbnailUrl))
        }
        AppPaths.log("debug feed-subscribe subscribed=\(feeds.isSubscribed(url)) subscriptions=\(feeds.subscriptions.count) " +
            "libraryPodcasts=\(AppServices.library.allPodcasts.count)")
    }

    /// `feed-open <検索語>`: 目録の最初の番組のページを開く。
    static func open(_ query: String) async {
        guard let first = await AppServices.feeds.client.search(query.isEmpty ? "rebuild" : query)?.first else { return }
        AppServices.nav.podcast(first)
    }

    private static func ms(since date: Date) -> Int { Int(Date().timeIntervalSince(date) * 1000) }
}
#endif
