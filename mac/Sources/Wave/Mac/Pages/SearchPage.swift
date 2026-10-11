#if os(macOS)
import SwiftUI
import WaveCore

/// 検索のページの状態（上の検索欄から呼ぶため、開いているページを覚える）。
@MainActor @Observable
final class SearchPageModel {
    /// 開いている検索のページ。
    @ObservationIgnored nonisolated(unsafe) static weak var current: SearchPageModel?

    var query: String?
    var loading = false
    var empty = false
    var tab = "all"
    var top: HomeItem?
    var songs: [TrackRow] = []
    var albums: [AlbumSummary] = []
    var artists: [ArtistSummary] = []
    var moods: [(MoodCategory, Color)] = []
    var history: [SearchHistoryEntry] = []
    @ObservationIgnored private var songContinuation: String?
    @ObservationIgnored private var albumContinuation: String?
    @ObservationIgnored private var artistContinuation: String?
    @ObservationIgnored private var loadingMore = false
    @ObservationIgnored private var requestId = 0
    @ObservationIgnored private static var moodCache: [(MoodCategory, Color)]?

    /// 検索し直す（上の検索欄から呼ばれる）。空なら「すべてをブラウズ」に戻る。
    func search(_ text: String?) {
        let q = text?.trimmingCharacters(in: .whitespaces)
        query = (q?.isEmpty ?? true) ? nil : q
        if query == nil {
            showRecent()
            Task { await loadMoods() }
            return
        }
        Task { await loadResults() }
    }

    func showRecent() { history = Array(AppServices.settings.searchHistory.prefix(12)) }

    private func loadMoods() async {
        if SearchPageModel.moodCache == nil {
            guard let sections = (try? await AppServices.music.moods())?.value else { return }
            var index = 0
            SearchPageModel.moodCache = sections.flatMap(\.categories).map { c in
                defer { index += 1 }
                return (c, Ui.tile(c.color, index))
            }
        }
        moods = SearchPageModel.moodCache ?? []
    }

    private func loadResults() async {
        requestId += 1
        let id = requestId
        guard let q = query else { return }
        loading = true
        empty = false
        songs = []
        let music = AppServices.music!
        async let s = music.searchSongs(q)
        async let al = music.searchAlbums(q)
        async let ar = music.searchArtists(q)
        let (songsResult, albumsResult, artistsResult) = (try? await (s, al, ar)) ?? (.failed(.network), .failed(.network), .failed(.network))
        guard id == requestId else { return }
        loading = false
        let found = songsResult.value?.songs ?? []
        songContinuation = songsResult.value?.continuation
        albums = albumsResult.value?.items ?? []
        albumContinuation = albumsResult.value?.continuation
        artists = artistsResult.value?.items ?? []
        artistContinuation = artistsResult.value?.continuation
        if found.isEmpty && albums.isEmpty && artists.isEmpty {
            empty = true
            return
        }
        songs = found.enumerated().map { TrackRow(number: $0.offset + 1, song: $0.element) }
        top = SearchPageModel.topResult(q, found, artists, albums)
    }

    /// トップの検索結果: アーティストの名前が検索語と同じならアーティスト、ほかは最初の曲（Android 版の SearchOrder と同じ）。
    static func topResult(_ q: String, _ songs: [Song], _ artists: [ArtistSummary], _ albums: [AlbumSummary]) -> HomeItem? {
        let key = normalize(q)
        if let artist = artists.first(where: { normalize($0.name) == key }) { return .artist(artist) }
        if let album = albums.first(where: { normalize($0.title) == key }) { return .album(album) }
        if let song = songs.first { return .song(song) }
        if let artist = artists.first { return .artist(artist) }
        return albums.first.map { .album($0) }
    }

    static func normalize(_ text: String) -> String {
        text.precomposedStringWithCompatibilityMapping.lowercased().filter { !$0.isWhitespace }
    }

    func remember(_ item: HomeItem) { AppServices.settings.addSearch(.of(item)) }

    func loadMoreSongs() {
        guard !loadingMore, let c = songContinuation, let q = query else { return }
        loadingMore = true
        let id = requestId
        Task {
            defer { loadingMore = false }
            guard let page = (try? await AppServices.music.searchSongs(q, continuation: c))?.value, id == requestId else { return }
            songContinuation = page.continuation
            let n = songs.count
            songs += page.songs.enumerated().map { TrackRow(number: n + $0.offset + 1, song: $0.element) }
        }
    }

    func loadMoreCards() {
        guard !loadingMore, let q = query else { return }
        let id = requestId
        if tab == "albums", let c = albumContinuation {
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await AppServices.music.searchAlbums(q, continuation: c))?.value, id == requestId else { return }
                albumContinuation = page.continuation
                albums += page.items
            }
        } else if tab == "artists", let c = artistContinuation {
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await AppServices.music.searchArtists(q, continuation: c))?.value, id == requestId else { return }
                artistContinuation = page.continuation
                artists += page.items
            }
        }
    }
}

struct SearchPageView: View {
    var initialQuery: String?
    @State private var model = SearchPageModel()
    @State private var selected: String?

    var body: some View {
        Group {
            if model.query == nil { browse } else { results }
        }
        .onAppear {
            SearchPageModel.current = model
            model.search(initialQuery)
        }
    }

    // 何も打っていないとき: 最近の検索と、気分・ジャンルのタイル（Spotify の「すべてをブラウズ」）
    private var browse: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !model.history.isEmpty {
                    HStack {
                        Text(L.t("最近の検索", "Recent searches")).subtitleStyle()
                        Spacer()
                        Button(L.t("すべて消去", "Clear all")) {
                            AppServices.settings.searchHistory.removeAll()
                            AppServices.settings.save()
                            model.showRecent()
                        }
                        .buttonStyle(.textLink)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 8)], spacing: 8) {
                        ForEach(model.history) { entry in RecentSearchTile(entry: entry, model: model) }
                    }
                }
                Text(L.t("すべてをブラウズ", "Browse all")).subtitleStyle().padding(.top, 8)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 12)], spacing: 12) {
                    ForEach(Array(model.moods.enumerated()), id: \.offset) { _, tile in
                        Button { AppServices.nav.mood(tile.0) } label: {
                            Text(tile.0.title).subtitleStyle().foregroundStyle(.white).multilineTextAlignment(.leading)
                                .padding(16)
                                .frame(maxWidth: .infinity, minHeight: 110, alignment: .topLeading)
                                .background(RoundedRectangle(cornerRadius: 8).fill(tile.1))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(EdgeInsets(top: 16, leading: 24, bottom: 32, trailing: 24))
        }
    }

    private var results: some View {
        VStack(spacing: 0) {
            Picker("", selection: $model.tab) {
                Text(L.t("すべて", "All")).tag("all")
                Text(L.t("曲", "Songs")).tag("songs")
                Text(L.t("アーティスト", "Artists")).tag("artists")
                Text(L.t("アルバム", "Albums")).tag("albums")
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .fixedSize()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 8, leading: 16, bottom: 0, trailing: 16))
            if model.loading {
                ProgressView().padding(.top, 48)
                Spacer()
            } else if model.empty {
                Text(L.t("見つかりませんでした", "No results found")).subtitleStyle().padding(.top, 64)
                Spacer()
            } else {
                switch model.tab {
                case "songs":
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(model.songs) { row in songRow(row).onAppear { if row.id == model.songs.last?.id { model.loadMoreSongs() } } }
                        }
                        .padding(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                    }
                case "albums", "artists":
                    let items: [HomeItem] = model.tab == "albums" ? model.albums.map { .album($0) } : model.artists.map { .artist($0) }
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 176), spacing: 0)], spacing: 8) {
                            ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                                MediaCard(item: item).onAppear { if i == items.count - 1 { model.loadMoreCards() } }
                            }
                        }
                        .padding(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    }
                default:
                    all
                }
            }
        }
    }

    private var all: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                HStack(alignment: .top, spacing: 24) {
                    if let top = model.top {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(L.t("トップの検索結果", "Top result")).subtitleStyle()
                            TopCard(item: top, model: model)
                        }
                        .frame(minWidth: 280, maxWidth: .infinity)
                        .layoutPriority(2)
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L.t("曲", "Songs")).subtitleStyle()
                        VStack(spacing: 0) {
                            ForEach(model.songs.prefix(4)) { row in
                                var short = row
                                let _ = short.showAlbum = false
                                songRow(short)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .layoutPriority(3)
                }
                if !model.artists.isEmpty { ShelfView(title: L.t("アーティスト", "Artists"), items: model.artists.map { .artist($0) }) }
                if !model.albums.isEmpty { ShelfView(title: L.t("アルバム", "Albums"), items: model.albums.map { .album($0) }) }
            }
            .padding(EdgeInsets(top: 16, leading: 24, bottom: 32, trailing: 24))
        }
    }

    private func songRow(_ row: TrackRow) -> some View {
        TrackRowView(row: row, selected: selected == row.id, onPlay: {
            model.remember(.song(row.song))
            AppServices.player.playSong(row.song)
        }, onSelect: { selected = row.id })
    }
}

private struct TopCard: View {
    var item: HomeItem
    var model: SearchPageModel
    @State private var hover = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(alignment: .leading, spacing: 16) {
                Cover(url: Ui.img(item.thumbnailUrl), size: 96, round: item.isArtist)
                Text(item.title).font(.title.weight(.bold)).lineLimit(2)
                Text(subtitle).font(.callout).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            PlayCircleButton(size: 48, action: {
                model.remember(item)
                AppServices.nav.play(item)
            }) { Image(systemName: "play.fill").font(.system(size: 18)) }
        }
        .padding(20)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(hover ? 0.1 : 0.06)))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture {
            model.remember(item)
            AppServices.nav.open(item)
        }
    }

    private var subtitle: String {
        switch item {
        case .song(let s): L.t("曲 • ", "Song • ") + s.artistsText
        case .artist: L.t("アーティスト", "Artist")
        case .album(let a): a.subtitle ?? L.t("アルバム", "Album")
        case .playlist(let p): p.subtitle ?? ""
        }
    }
}

private struct RecentSearchTile: View {
    var entry: SearchHistoryEntry
    var model: SearchPageModel
    @State private var hover = false

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                if entry.isQuery {
                    RoundedRectangle(cornerRadius: 6).fill(Color.primary.opacity(0.08))
                    Image(systemName: "magnifyingglass")
                } else {
                    Cover(url: Ui.small(entry.thumbnailUrl), size: 48, round: entry.isRound)
                }
            }
            .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title).lineLimit(1)
                if let subtitle = entry.subtitle { Text(subtitle).subtle() }
            }
            Spacer(minLength: 0)
            Button {
                AppServices.settings.removeSearch(entry)
                model.showRecent()
            } label: { Image(systemName: "xmark").font(.system(size: 11)) }
                .buttonStyle(IconButtonStyle())
                .help(L.t("履歴から削除", "Remove from history"))
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 6).fill(Color.primary.opacity(hover ? 0.1 : 0.05)))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture {
            AppServices.settings.addSearch(entry)
            if let item = entry.toItem() { AppServices.nav.open(item) } else { MainWindowModel.shared.searchFor(entry.title) }
        }
    }
}
#endif
