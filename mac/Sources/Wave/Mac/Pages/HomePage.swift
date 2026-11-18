#if os(macOS)
import SwiftUI
import WaveCore

/// ホーム（Spotify と同じ並び: 絞り込みのチップ、最近聴いたものの格子、おすすめの棚）。
struct HomePageView: View {
    @State private var home = HomeModel.shared
    /// 最近の格子を置ける幅（右の欄を開く・窓を狭めると縮む）。列の数を決めるのに使う。
    @State private var gridWidth: CGFloat = 0

    /// 最近の格子の列の数。1枚を 180pt より細くしない（窓が狭いと 4 列のままでは右の欄の下へはみ出した）。2〜4 列。
    static func recentColumns(width: CGFloat) -> Int {
        let tile: CGFloat = 180, spacing = Kasane.Space.s2
        guard width > 0 else { return 4 }
        return max(2, min(4, Int((width + spacing) / (tile + spacing))))
    }

    var body: some View {
        let releases = home.selected == nil ? Array(ReleaseWatchState.diversified(AppServices.releases.recent).prefix(20)) : []
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Kasane.Space.s6) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Kasane.Space.s2) {
                        Chip(title: L.t("すべて", "All"), isOn: home.selected == nil) { home.select(nil) }
                        ForEach(home.chips, id: \.self) { chip in Chip(title: chip.title, isOn: home.selected == chip) { home.select(chip) } }
                    }
                    .padding(.horizontal, Kasane.Space.s2)
                }
                if !home.recent.isEmpty {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Kasane.Space.s2), count: Self.recentColumns(width: gridWidth)), spacing: Kasane.Space.s2) {
                        ForEach(home.recent, id: \.key) { item in RecentTile(item: item) }
                    }
                    .padding(.horizontal, Kasane.Space.s2)
                    .onGeometryChange(for: CGFloat.self) { $0.size.width - Kasane.Space.s2 * 2 } action: { gridWidth = $0 }
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
                if home.failure != nil && home.newReleases.isEmpty {
                    VStack(spacing: Kasane.Space.s2) {
                        Text(L.t("ホームを読み込めませんでした", "Couldn't load Home"))
                        Button(L.t("もう一度", "Retry")) { home.reload() }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(EdgeInsets(top: Kasane.Space.s4, leading: Kasane.Space.s4, bottom: Kasane.Space.s8, trailing: Kasane.Space.s4))
        }
        .onAppear { home.start() }
    }
}

private struct RecentTile: View {
    var item: HomeItem
    @State private var hover = false

    var body: some View {
        Button { AppServices.nav.openCard(item) } label: {
            HStack(spacing: Kasane.Space.s3) {
                ItemCover(item: item, url: Ui.small(item.thumbnailUrl), size: 56, radius: 0)
                Text(Ui.title(item)).font(.body.weight(.semibold)).lineLimit(2).multilineTextAlignment(.leading)
                Spacer(minLength: 12)
            }
            .frame(height: 56)
            .background(Color.primary.opacity(hover ? 0.12 : 0.07))
            .clipShape(RoundedRectangle(cornerRadius: Kasane.Radius.sm, style: Kasane.cornerStyle))
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
            LazyVStack(alignment: .leading, spacing: Kasane.Space.s6) {
                Text(mood.title).font(.system(size: 48, weight: .bold)).padding(EdgeInsets(top: Kasane.Space.s4, leading: Kasane.Space.s2, bottom: 0, trailing: Kasane.Space.s2)) // apple-ui-allow: 見出し・歌詞の大きさ（Windows 版と揃える）
                if loading { ProgressView().padding(.horizontal, Kasane.Space.s2) }
                ForEach(Array(shelves.enumerated()), id: \.offset) { _, shelf in
                    ShelfView(title: shelf.title, strapline: shelf.strapline, items: shelf.items)
                }
            }
            .padding(EdgeInsets(top: Kasane.Space.s4, leading: Kasane.Space.s4, bottom: Kasane.Space.s8, trailing: Kasane.Space.s4))
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
                VStack(alignment: .leading, spacing: Kasane.Space.s2) {
                    Text(L.t("再生履歴", "History")).font(.system(size: 48, weight: .bold)) // apple-ui-allow: 見出し・歌詞の大きさ（Windows 版と揃える）
                    if loading { ProgressView() }
                    if let error { Text(error) }
                }
                .padding(EdgeInsets(top: Kasane.Space.s8, leading: Kasane.Space.s3, bottom: Kasane.Space.s4, trailing: Kasane.Space.s3))
                ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                    Text(section.title).subtitleStyle().padding(EdgeInsets(top: Kasane.Space.s3, leading: Kasane.Space.s3, bottom: Kasane.Space.s1, trailing: Kasane.Space.s3))
                    ForEach(section.rows) { row in
                        TrackRowView(row: row, selected: selected == row.id, onPlay: { AppServices.player.playSong(row.song) }, onSelect: { selected = row.id })
                    }
                }
            }
            .padding(EdgeInsets(top: 0, leading: Kasane.Space.s3, bottom: Kasane.Space.s6, trailing: Kasane.Space.s3))
        }
        .task {
            let result = (try? await AppServices.music.history()) ?? .failed(.network)
            loading = false
            guard let page = result.value else {
                error = result.failure == .notLoggedIn ? L.t("再生履歴を見るにはログインしてください", "Sign in to see your listening history")
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
