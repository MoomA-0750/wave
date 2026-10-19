#if os(macOS)
import SwiftUI
import WaveCore

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
                if home.failure != nil && home.newReleases.isEmpty {
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
