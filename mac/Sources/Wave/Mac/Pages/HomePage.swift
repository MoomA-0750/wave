#if os(macOS)
import SwiftUI
import WaveCore

/// ホームの状態（ページを離れても持っておく。Windows の NavigationCacheMode と同じ）。
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
    var failed = false

    /// 出す棚: 新作を先頭に、ホームの棚を続ける（ホームに同じ新作の棚が来ていれば、こちらは出さない）。
    var displayShelves: [HomeShelf] { HomeShelves.withNewReleases(newReleases, title: L.t("新作", "New releases"), shelves: shelves) }
    @ObservationIgnored private var seen: Set<String> = []
    @ObservationIgnored private var continuation: String?
    @ObservationIgnored private var requestId = 0
    @ObservationIgnored private var started = false

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
        requestId += 1
        let id = requestId
        shelves = []
        seen = []
        continuation = nil
        failed = false
        Task {
            if selected == nil { await loadRecent(id) } else { recent = [] }
        }
        Task { await loadNewReleases(id) }
        Task {
            // 最初は棚が8つ以上になるまで続きを読む（Android 版と同じ）
            for _ in 0..<4 where id == requestId {
                if !(await loadPage(id)) { break }
                if shelves.count >= 8 || continuation == nil { break }
            }
        }
    }

    private func loadRecent(_ id: Int) async {
        recent = []
        guard AppServices.client.isLoggedIn else { return }
        guard let items = (try? await AppServices.music.recentActivity())?.value, id == requestId, !items.isEmpty else { return }
        recent = Array(items.prefix(8))
    }

    private func loadNewReleases(_ id: Int) async {
        newReleases = []
        guard selected == nil else { return }
        let result = (try? await AppServices.music.newReleases()) ?? .failed(.network)
        guard id == requestId else { return }
        newReleases = result.value ?? []
        AppPaths.log("home newReleases=\(newReleases.count)")
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
        let started = Date()
        let params = continuation == nil ? selected?.params : nil
        var result = (try? await AppServices.music.home(continuation: continuation, chipParams: params)) ?? .failed(.network)
        // 起動直後はほかの読み込みと重なって時間切れになることがあるので、通信の失敗は1回だけやり直す
        if !result.isOk && result.failure == .network && id == requestId {
            result = (try? await AppServices.music.home(continuation: continuation, chipParams: params)) ?? .failed(.network)
        }
        guard id == requestId else { return false }
        guard let page = result.value else {
            AppPaths.log("home failed failure=\(String(describing: result.failure)) status=\(result.status.map(String.init) ?? "nil") ms=\(Int(Date().timeIntervalSince(started) * 1000))")
            if shelves.isEmpty { failed = true }
            return false
        }
        if continuation == nil && selected == nil && !page.chips.isEmpty { chips = page.chips }
        for shelf in page.shelves where seen.insert(shelf.title + "|" + shelf.items.prefix(3).map(\.key).joined(separator: ",")).inserted {
            shelves.append(shelf)
        }
        continuation = page.continuation
        return true
    }

    /// 新作をカードにする（アルバムは開く、動画は再生する。見つけて7日は補足に NEW を付ける）。
    static func releaseItem(_ r: NewRelease) -> HomeItem {
        let fresh = !r.seeded && Int64(Date().timeIntervalSince1970 * 1000) - r.foundAtEpochMs < 7 * 24 * 60 * 60 * 1000
        let subtitle = (fresh ? "NEW • " : "") + r.artistName
        if let album = r.albumBrowseId { return .album(AlbumSummary(browseId: album, title: r.title, subtitle: subtitle, thumbnailUrl: r.thumbnailUrl)) }
        return .song(Song(videoId: r.videoId ?? "", title: r.title, artists: [ArtistRef(name: r.artistName, browseId: r.artistId)], album: nil,
                          durationSeconds: nil, thumbnailUrl: r.thumbnailUrl))
    }
}

/// ホーム（Spotify と同じ並び: 絞り込みのチップ、最近聴いたものの格子、おすすめの棚）。
struct HomePageView: View {
    @State private var home = HomeModel.shared

    var body: some View {
        let releases = home.selected == nil ? Array(ReleaseWatchState.diversified(AppServices.releases.recent).prefix(20)) : []
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        Chip(title: L.t("すべて", "All"), isOn: home.selected == nil) { home.select(nil) }
                        ForEach(home.chips, id: \.self) { chip in Chip(title: chip.title, isOn: home.selected == chip) { home.select(chip) } }
                    }
                    .padding(.horizontal, 8)
                }
                if !home.recent.isEmpty {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(minimum: 200), spacing: 8), count: 4), spacing: 8) {
                        ForEach(home.recent, id: \.key) { item in RecentTile(item: item) }
                    }
                    .padding(.horizontal, 8)
                }
                // 登録したアーティストの新作（Android と同じ。アーティストごとに2件まで交互に）
                if !releases.isEmpty {
                    ShelfView(title: L.t("登録したアーティストの新作", "New from artists you subscribe to"), items: releases.map(HomeModel.releaseItem))
                }
                let shelves = home.displayShelves
                ForEach(Array(shelves.enumerated()), id: \.offset) { i, shelf in
                    ShelfView(title: shelf.title, strapline: shelf.strapline, items: shelf.items)
                        .onAppear { if i >= shelves.count - 2 { home.loadMore() } }
                }
                if home.loading { ProgressView().frame(maxWidth: .infinity) }
                // 新作だけでも読めていれば、失敗は出さない（Android と同じ）
                if home.failed && home.newReleases.isEmpty {
                    VStack(spacing: 8) {
                        Text(L.t("ホームを読み込めませんでした", "Couldn’t load Home"))
                        Button(L.t("もう一度読み込む", "Try again")) { home.reload() }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(EdgeInsets(top: 16, leading: 16, bottom: 32, trailing: 16))
        }
        .onAppear { home.start() }
    }
}

private struct RecentTile: View {
    var item: HomeItem
    @State private var hover = false

    var body: some View {
        Button { AppServices.nav.openCard(item) } label: {
            HStack(spacing: 12) {
                Cover(url: Ui.small(item.thumbnailUrl), size: 56, round: item.isArtist, radius: 0)
                Text(item.title).font(.body.weight(.semibold)).lineLimit(2).multilineTextAlignment(.leading)
                Spacer(minLength: 12)
            }
            .frame(height: 56)
            .background(Color.primary.opacity(hover ? 0.12 : 0.07))
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

/// 気分・ジャンルの1つのページ（棚の並び）。
struct MoodPageView: View {
    var mood: MoodCategory
    @State private var shelves: [HomeShelf] = []
    @State private var loading = true

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                Text(mood.title).font(.system(size: 48, weight: .bold)).padding(EdgeInsets(top: 16, leading: 8, bottom: 0, trailing: 8))
                if loading { ProgressView().padding(.horizontal, 8) }
                ForEach(Array(shelves.enumerated()), id: \.offset) { _, shelf in
                    ShelfView(title: shelf.title, strapline: shelf.strapline, items: shelf.items)
                }
            }
            .padding(EdgeInsets(top: 16, leading: 16, bottom: 32, trailing: 16))
        }
        .task {
            let result = try? await AppServices.music.moodCategory(mood.params)
            loading = false
            shelves = result?.value?.shelves ?? []
        }
    }
}

/// 最近再生した曲（YouTube Music の再生履歴。「今日」「昨日」などで区切る）。
struct HistoryPageView: View {
    @State private var sections: [(title: String, rows: [TrackRow])] = []
    @State private var loading = true
    @State private var error: String?
    @State private var selected: String?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L.t("最近再生した曲", "Recently played")).font(.system(size: 48, weight: .bold))
                    if loading { ProgressView() }
                    if let error { Text(error) }
                }
                .padding(EdgeInsets(top: 32, leading: 12, bottom: 16, trailing: 12))
                ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                    Text(section.title).subtitleStyle().padding(EdgeInsets(top: 12, leading: 12, bottom: 4, trailing: 12))
                    ForEach(section.rows) { row in
                        TrackRowView(row: row, selected: selected == row.id, onPlay: { AppServices.player.playSong(row.song) }, onSelect: { selected = row.id })
                    }
                }
            }
            .padding(EdgeInsets(top: 0, leading: 12, bottom: 24, trailing: 12))
        }
        .task {
            let result = (try? await AppServices.music.history()) ?? .failed(.network)
            loading = false
            guard let page = result.value else {
                error = result.failure == .notLoggedIn ? L.t("再生履歴を見るにはログインしてください", "Log in to see your listening history")
                    : L.t("再生履歴を読み込めませんでした", "Couldn't load your listening history")
                return
            }
            var n = 1
            sections = page.sections.map { s in
                (s.title ?? "", s.songs.map { song in
                    defer { n += 1 }
                    return TrackRow(number: n, song: song)
                })
            }
        }
    }
}
#endif
