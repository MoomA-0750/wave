import Foundation
import Observation
import WaveCore

/// RSS のポッドキャスト（ADR-0012。Android の FeedStore・PodcastViewModel の RSS の分と同じ）。
/// 登録と聴いたところはこの端末の Application Support/wave/podcast-feeds.json に置く（アカウントに結びつけない。ログアウトでも消さない）。
@MainActor @Observable
final class FeedStore {
    private(set) var state: FeedLibraryState
    /// 目録の検索とフィードの取得（認証情報を持たない通信）。
    @ObservationIgnored let client: PodcastFeedClient
    /// 読んだ番組（続きのエピソードを、読み直さずに出す）。新しく読んだものを後ろに、数件だけ持つ。
    @ObservationIgnored private var shows: [(feedUrl: String, show: FeedShow)] = []
    @ObservationIgnored private let file = AppPaths.file("podcast-feeds.json")
    @ObservationIgnored private let writer = DispatchQueue(label: "wave.feeds.save")

    private static let keptShows = 4

    init() {
        state = (try? Data(contentsOf: AppPaths.file("podcast-feeds.json"))).flatMap(FeedLibraryState.decode) ?? FeedLibraryState()
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0"
        let country = Locale.current.region?.identifier ?? "JP"
        client = PodcastFeedClient(country: country.isEmpty ? "JP" : country, userAgent: "wave/\(version)")
    }

    var subscriptions: [FeedSubscription] { state.subscriptions }

    func isSubscribed(_ feedUrl: String) -> Bool { state.isSubscribed(feedUrl) }

    func position(_ audioUrl: String) -> EpisodePosition? { state.positions[audioUrl] }

    /// エピソードの聴いたところ（%）。RSS のエピソードでなければ、渡されたもの（YouTube の値）のまま。
    func progress(_ episode: PodcastEpisode) -> Int? {
        guard let url = Feed.audioUrlOf(episode.song.videoId) else { return episode.progressPercent }
        return state.positions[url]?.percent
    }

    func subscribe(_ subscription: FeedSubscription) {
        var s = subscription
        s.addedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
        change(state.subscribe(s))
    }

    func unsubscribe(_ feedUrl: String) { change(state.unsubscribe(feedUrl, atMs: Int64(Date().timeIntervalSince1970 * 1000))) }

    // MARK: 家の端末の間で合わせる（ADR-0013。Android の WaveApplication と同じ間隔）

    /// 聴いたところだけが変わったときは、この間隔に1回まで書く（再生中は15秒ごとに変わる）。
    private static let positionsEveryMs: Int64 = 30_000
    @ObservationIgnored private var sentState: FeedLibraryState?
    @ObservationIgnored private var sentAtMs: Int64 = 0
    @ObservationIgnored private var pendingPublish: DispatchWorkItem?

    /// 連携が使えるようになった（起動の終わり）。今の中身を中継と合わせる。
    func startSync() { publish() }

    /// ほかの端末と合わせた結果を取り込む。
    func apply(_ body: LibraryBody) { change(state.apply(body)) }

    /// 登録の変化はすぐ、聴いたところだけの変化は30秒に1回まで、中継に書く。
    private func publish() {
        guard let connect = AppServices.connect else { return }
        let subscriptionsChanged = sentState == nil || sentState?.subscriptions != state.subscriptions || sentState?.removed != state.removed
        let now = AppServices.clockMs()
        pendingPublish?.cancel()
        pendingPublish = nil
        if subscriptionsChanged || now - sentAtMs >= FeedStore.positionsEveryMs {
            sentState = state
            sentAtMs = now
            connect.publishLibrary(state.toBody())
        } else {
            let work = DispatchWorkItem { [weak self] in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.pendingPublish = nil
                    self.sentState = self.state
                    self.sentAtMs = AppServices.clockMs()
                    AppServices.connect?.publishLibrary(self.state.toBody())
                }
            }
            pendingPublish = work
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(FeedStore.positionsEveryMs - (now - sentAtMs))), execute: work)
        }
    }

    /// 聴いたところを覚える（長さが分からなければ nil）。
    func remember(_ audioUrl: String, positionSeconds: Int, durationSeconds: Int?) {
        change(state.remember(audioUrl, .of(positionSeconds: positionSeconds, durationSeconds: durationSeconds, nowMs: Int64(Date().timeIntervalSince1970 * 1000))))
    }

    private func change(_ next: FeedLibraryState) {
        guard next != state else { return }
        state = next
        guard let data = FeedLibraryState.encode(next) else { return }
        let file = self.file
        // 書く順は1本の流れに任せる（後に書いたものが残る）
        writer.async { try? data.write(to: file, options: .atomic) }
        publish()
    }

    // MARK: 番組のページ

    /// `rss:` の番組を読んで、番組のページの形にする。読めなければ失敗の理由。
    func page(_ browseId: String) async -> Result<PodcastPage, FeedFailure> {
        guard let feedUrl = Feed.feedUrlOf(browseId) else { return .failure(.notAllowed) }
        let started = Date()
        switch await client.show(feedUrl) {
        case .ok(let show):
            remember(show, feedUrl)
            // 番組の名前・画像が変わっていたら、登録の表示を合わせる
            if state.isSubscribed(show.feedUrl) { change(state.refresh(show.subscription)) }
            let positions = state.positions
            let page = show.toPage(saved: state.isSubscribed(feedUrl)) { positions[$0] }
            AppPaths.log("podcast feed items=\(show.itemCount) episodes=\(page.episodes.count) more=\(page.continuation != nil) " +
                "saved=\(page.saved ?? false) ms=\(Int(Date().timeIntervalSince(started) * 1000))")
            return .success(page)
        case .failed(let failure):
            AppPaths.log("podcast feed failed \(failure)")
            return .failure(failure)
        }
    }

    /// 読んだ番組の続きのエピソード。番組を覚えていなければ読み直す。
    func more(_ browseId: String, _ token: String) async -> SearchPage<PodcastEpisode>? {
        guard let feedUrl = Feed.feedUrlOf(browseId) else { return nil }
        var show = shows.first { $0.feedUrl == feedUrl }?.show
        if show == nil, case .ok(let read) = await client.show(feedUrl) {
            remember(read, feedUrl)
            show = read
        }
        let positions = state.positions
        return show?.more(token) { positions[$0] }
    }

    /// 番組のページの「保存」: この端末の登録を切り替える。
    func toggle(_ page: PodcastPage) {
        guard let feedUrl = Feed.feedUrlOf(page.browseId) else { return }
        if state.isSubscribed(feedUrl) {
            unsubscribe(feedUrl)
        } else {
            subscribe(FeedSubscription(feedUrl: feedUrl, title: page.title, author: page.author, thumbnailUrl: page.thumbnailUrl))
        }
    }

    private func remember(_ show: FeedShow, _ feedUrl: String) {
        shows.removeAll { $0.feedUrl == feedUrl }
        shows.append((feedUrl, show))
        if shows.count > FeedStore.keptShows { shows.removeFirst(shows.count - FeedStore.keptShows) }
    }

    // MARK: 新しいエピソード

    /// ライブラリの「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（Android の mergeFeedEpisodes と同じ）。
    /// 番組のフィードは4つずつ並べて読み、読めなかった番組は飛ばす。登録が無ければ渡したまま。
    func mergeNewEpisodes(_ youtube: [PodcastEpisode]) async -> [PodcastEpisode] {
        let subscriptions = state.subscriptions
        if subscriptions.isEmpty { return youtube }
        let client = self.client
        let shows: [FeedShow] = await withTaskGroup(of: (Int, FeedShow?).self) { group in
            var results: [(Int, FeedShow?)] = []
            var next = 0
            func add() {
                let i = next
                let url = subscriptions[i].feedUrl
                group.addTask {
                    if case .ok(let show) = await client.show(url) { return (i, show) }
                    return (i, nil)
                }
                next += 1
            }
            while next < min(4, subscriptions.count) { add() }
            for await result in group {
                results.append(result)
                if next < subscriptions.count { add() }
            }
            // 登録の順にそろえる
            return results.sorted { $0.0 < $1.0 }.compactMap(\.1)
        }
        for show in shows { remember(show, show.feedUrl) }
        let positions = state.positions
        let merged = NewEpisodes.merge(youtube, shows, nowEpochSeconds: Int64(Date().timeIntervalSince1970)) { positions[$0] }
        AppPaths.log("podcast new episodes feeds=\(shows.count)/\(subscriptions.count) youtube=\(youtube.count) merged=\(merged.count)")
        return merged
    }

    // MARK: 検索

    /// 検索の「ポッドキャスト」の最初のページ: Apple の目録の番組（RSS）を先に、YouTube の番組を後に並べる。
    /// どちらかが読めれば、読めた分を出す（Android の podcastsFirstPage と同じ）。
    /// 目録に無く、YouTube の番組も読めなかったときは、その失敗を返す（Android と同じ）。
    func searchFirstPage(_ query: String) async -> ApiResult<SearchPage<PodcastSummary>> {
        async let directory = client.search(query)
        let result = (try? await AppServices.music.searchPodcasts(query)) ?? .failed(.network)
        let youtube = result.value
        let rss = await directory
        AppPaths.log("search podcast directory=\(rss.map { String($0.count) } ?? "failed") youtube=\(youtube.map { String($0.items.count) } ?? "failed")")
        guard let rss, !rss.isEmpty else { return result }
        let known = Set(rss.map(\.browseId))
        return .ok(SearchPage(items: rss + (youtube?.items ?? []).filter { !known.contains($0.browseId) }, continuation: youtube?.continuation))
    }
}
