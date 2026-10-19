import Foundation
import WaveCore

/// ホームの状態（ページ・タブを切り替えても持っておく。Android の HomeFeedViewModel・Windows の NavigationCacheMode と同じ）。Mac・iPhone で使う。
@MainActor @Observable
final class HomeModel {
    static let shared = HomeModel()

    var shelves: [HomeShelf] = []
    var chips: [HomeChip] = []
    var selected: HomeChip?
    var recent: [HomeItem] = []
    /// YouTube Music の新作（アルバム・シングル）。「すべて」のときだけ読み、棚の先頭に出す（Android と同じ）。
    var newReleases: [AlbumSummary] = []
    var loading = false
    var failure: FailureKind?

    /// 出す棚: 新作を先頭に、ホームの棚を続ける（ホームに同じ新作の棚が来ていれば、こちらは出さない）。
    var displayShelves: [HomeShelf] { HomeShelves.withNewReleases(newReleases, title: L.t("新作", "New releases"), shelves: shelves) }
    private(set) var continuation: String?
    @ObservationIgnored private var seen: Set<String> = []
    @ObservationIgnored private var requestId = 0
    @ObservationIgnored private var started = false
    /// 引っぱって読み直すときは、新しい棚が届くまで前の棚を出しておく。
    @ObservationIgnored private var replacePending = false

    private init() {}

    func start() {
        guard !started else { return }
        started = true
        AppServices.account.changed.append { [weak self] in self?.reload() }
        reload()
    }

    func select(_ chip: HomeChip?) {
        selected = selected == chip ? nil : chip
        reload()
    }

    func reload() {
        Task { await refresh(keep: false) }
    }

    /// 読み直す（最初の棚が揃うまで待つ）。keep なら届くまで前の棚を残す（iPhone の引っぱって読み直す）。
    func refresh(keep: Bool) async {
        requestId += 1
        let id = requestId
        continuation = nil
        failure = nil
        loading = false
        if keep {
            replacePending = true
        } else {
            shelves = []
            seen = []
            replacePending = false
        }
        async let recentDone: Void = loadRecent(id)
        async let releasesDone: Void = loadNewReleases(id)
        // 最初は棚が8つ以上になるまで続きを読む（Android と同じ）
        for _ in 0..<4 where id == requestId {
            if !(await loadPage(id)) { break }
            if shelves.count >= 8 || continuation == nil { break }
        }
        await recentDone
        await releasesDone
    }

    private func loadNewReleases(_ id: Int) async {
        guard selected == nil else {
            newReleases = []
            return
        }
        let result = (try? await AppServices.music.newReleases()) ?? .failed(.network)
        guard id == requestId else { return }
        newReleases = result.value ?? []
        AppPaths.log("home newReleases=\(newReleases.count)")
    }

    private func loadRecent(_ id: Int) async {
        guard selected == nil, AppServices.client.isLoggedIn else {
            recent = []
            return
        }
        let items = (try? await AppServices.music.recentActivity())?.value ?? []
        guard id == requestId else { return }
        recent = Array(items.prefix(8))
    }

    /// 下の端に近づいたら続きの棚を読む。
    func loadMore() {
        guard continuation != nil, !loading else { return }
        let id = requestId
        Task { await loadPage(id) }
    }

    @discardableResult
    private func loadPage(_ id: Int) async -> Bool {
        if loading { return false }
        loading = true
        defer { loading = false }
        let startedAt = Date()
        let params = continuation == nil ? selected?.params : nil
        var result = (try? await AppServices.music.home(continuation: continuation, chipParams: params)) ?? .failed(.network)
        // 起動直後はほかの読み込みと重なって時間切れになることがあるので、通信の失敗は1回だけやり直す
        if !result.isOk && result.failure == .network && id == requestId {
            result = (try? await AppServices.music.home(continuation: continuation, chipParams: params)) ?? .failed(.network)
        }
        guard id == requestId else { return false }
        guard let page = result.value else {
            AppPaths.log("home failed failure=\(String(describing: result.failure)) status=\(result.status.map(String.init) ?? "nil") ms=\(Int(Date().timeIntervalSince(startedAt) * 1000))")
            if replacePending { replacePending = false }
            if shelves.isEmpty { failure = result.failure ?? .network }
            return false
        }
        if replacePending {
            shelves = []
            seen = []
            replacePending = false
        }
        if continuation == nil && selected == nil && !page.chips.isEmpty { chips = page.chips }
        for shelf in page.shelves where seen.insert(shelf.title + "|" + shelf.items.prefix(3).map(\.key).joined(separator: ",")).inserted {
            shelves.append(shelf)
        }
        continuation = page.continuation
        return true
    }

    /// 新作をカードにする（アルバムは開く、動画は再生する・曲の画面へ。見つけて7日は補足に NEW を付ける。Android と同じ）。ホームとアーティストのページで使う。
    static func releaseItem(_ r: NewRelease) -> HomeItem {
        let fresh = !r.seeded && Int64(Date().timeIntervalSince1970 * 1000) - r.foundAtEpochMs < 7 * 24 * 60 * 60 * 1000
        let subtitle = (fresh ? "NEW • " : "") + r.artistName
        if let album = r.albumBrowseId { return .album(AlbumSummary(browseId: album, title: r.title, subtitle: subtitle, thumbnailUrl: r.thumbnailUrl)) }
        return .song(Song(videoId: r.videoId ?? "", title: r.title, artists: [ArtistRef(name: r.artistName, browseId: r.artistId)], album: nil,
                          durationSeconds: nil, thumbnailUrl: r.thumbnailUrl))
    }
}
