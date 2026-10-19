import SwiftUI
import WaveCore

/// ポッドキャストの番組のページの状態（Mac・iPhone で同じ読み方。Android の PodcastScreen と同じ）。
/// エピソードを押すと、読み込んだエピソードの一覧をその位置から流す（曲の一覧と同じ）。
@MainActor @Observable
final class PodcastModel {
    let summary: PodcastSummary
    var page: PodcastPage?
    var episodes: [PodcastEpisode] = []
    var loading = true
    var loadingMore = false
    var failed = false
    var saving = false
    var gradient: Color?
    @ObservationIgnored private var continuation: String?

    init(summary: PodcastSummary) {
        self.summary = summary
    }

    /// 「新しいエピソード」は、番組を保存していないと番組のページの形で返らない（エピソードが無いだけとして出す）。
    var isNewEpisodes: Bool { summary.browseId == MusicRepository.newEpisodesBrowse }

    /// RSS の番組（`rss:…`。ADR-0012）。保存はこの端末の登録になる。
    var isFeed: Bool { Feed.isFeedShow(summary.browseId) }

    /// 保存しているか。RSS の番組はこの端末の登録を見る（分からなければ nil）。
    var saved: Bool? {
        guard page != nil else { return nil }
        if isFeed, let url = Feed.feedUrlOf(summary.browseId) { return AppServices.feeds.isSubscribed(url) }
        return page?.saved
    }

    var isPlayingHere: Bool { AppServices.player.sourceId == summary.playlistId && AppServices.player.current != nil }

    func load() async {
        loading = true
        failed = false
        let value: PodcastPage?
        if isFeed {
            // RSS の番組は、フィードを読んで同じ形にする
            value = try? await AppServices.feeds.page(summary.browseId).get()
        } else {
            let result = (try? await AppServices.music.podcast(summary.browseId)) ?? .failed(.network)
            // 保存した番組が無いアカウントの「新しいエピソード」は番組のページの形で返らない。空として出し、RSS の回を混ぜる（Android と同じ）
            value = result.value ?? (isNewEpisodes && result.failure == .parse
                ? PodcastPage(browseId: summary.browseId, title: summary.title, author: nil, description: nil, thumbnailUrl: nil, episodes: [],
                              continuation: nil, saved: nil)
                : nil)
        }
        loading = false
        guard let value else {
            failed = true
            AppPaths.log("podcast failed")
            return
        }
        page = value
        episodes = value.episodes
        continuation = value.continuation
        // 「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012）
        if isNewEpisodes {
            let merged = await AppServices.feeds.mergeNewEpisodes(value.episodes)
            episodes = merged
            page?.episodes = merged
        }
        AppPaths.log("podcast episodes=\(value.episodes.count) more=\(value.continuation != nil) saved=\(value.saved.map { "\($0)" } ?? "nil")")
        gradient = await Artwork.dominantColor(value.thumbnailUrl)
    }

    /// 一覧の最後まで来たら続きのエピソードを読む。
    func loadMore() {
        guard !loadingMore, let token = continuation, let page else { return }
        loadingMore = true
        Task {
            defer { loadingMore = false }
            let more: SearchPage<PodcastEpisode>?
            if isFeed {
                more = await AppServices.feeds.more(page.browseId, token)
            } else {
                more = (try? await AppServices.music.podcastEpisodes(continuation: token, show: ArtistRef(name: page.title, browseId: nil), showBrowseId: page.browseId))?.value
            }
            guard let more else { return }
            let known = Set(episodes.map(\.id))
            let fresh = more.items.filter { !known.contains($0.id) }
            episodes += fresh
            continuation = more.continuation != token ? more.continuation : nil
            // この番組を流しているなら、読めた分を後ろに足す
            let player = AppServices.player!
            if player.sourceId == summary.playlistId { player.appendFrom(fresh.map(\.song)) }
        }
    }

    /// その位置から、読み込んだエピソードを流す（Android の playFrom と同じく、出どころは番組のプレイリスト）。
    /// RSS の番組は YouTube のプレイリストではない（出どころの印はこの端末の「再生中」の表示にだけ使い、連携には送らない）。
    func play(_ index: Int) {
        guard !episodes.isEmpty else { return }
        AppServices.player.playList(episodes.map(\.song), start: index, source: summary.playlistId)
    }

    /// 再生ボタン: この番組を流していれば一時停止・再開、ほかは頭から。
    func playOrPause() {
        if isPlayingHere { AppServices.player.togglePlay() } else { play(0) }
    }

    /// 保存の切り替え。書けたら表示を変える（アーティストの登録と同じく楽観的に変えない）。
    func toggleSaved() {
        if isFeed {
            if let current = page { AppServices.feeds.toggle(current) }
            return
        }
        guard let current = page, let saved = current.saved, !saving else { return }
        saving = true
        Task {
            let result = (try? await AppServices.music.setPodcastSaved(current.playlistId, saved: !saved)) ?? .failed(.network)
            saving = false
            if result.isOk {
                page?.saved = !saved
                AppServices.library.refresh()
            } else {
                AppServices.player.message = L.t("ライブラリを変更できませんでした", "Couldn't change your library")
            }
        }
    }
}
