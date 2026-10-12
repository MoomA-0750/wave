#if os(iOS)
import SwiftUI
import WaveCore

/// 検索の結果の絞り込み（Android の SearchTab と同じ）。
private enum SearchTab: String, CaseIterable, Hashable {
    case all, songs, albums, artists, podcasts, episodes

    var label: String {
        switch self {
        case .all: L.t("すべて", "All")
        case .songs: L.t("曲", "Songs")
        case .albums: L.t("アルバム", "Albums")
        case .artists: L.t("アーティスト", "Artists")
        case .podcasts: L.t("ポッドキャスト", "Podcasts")
        case .episodes: L.t("エピソード", "Episodes")
        }
    }
}

/// 検索の状態（Android の SearchViewModel・MoodsViewModel と、Mac の SearchPageModel と同じ読み方）。
@MainActor @Observable
private final class SearchModel {
    /// 確定した検索語（nil なら結果を出していない）。
    var committed: String?
    var tab = SearchTab.all
    var loading = false
    var empty = false
    var failure: FailureKind?
    var top: HomeItem?
    var songs: [Song] = []
    var albums: [AlbumSummary] = []
    var artists: [ArtistSummary] = []
    var songContinuation: String?
    var albumContinuation: String?
    var artistContinuation: String?
    var loadingMore = false
    /// ポッドキャスト・エピソード（その絞り込みを初めて選んだときに読む。「すべて」には出さない。Android と同じ）。
    var podcasts: [PodcastSummary] = []
    var episodes: [PodcastEpisode] = []
    var podcastContinuation: String?
    var episodeContinuation: String?
    var podcastsLoading = false
    @ObservationIgnored private var podcastsLoaded = false
    @ObservationIgnored private var episodesLoaded = false

    var suggestions: SearchSuggestions?
    var history: [SearchHistoryEntry] = []
    var moods: [MoodSection] = []
    var moodsLoading = false
    var moodsFailure: FailureKind?

    @ObservationIgnored private var requestId = 0

    init() { showHistory() }

    // MARK: 何も打っていないとき

    func showHistory() { history = AppServices.settings.searchHistory }

    func remove(_ entry: SearchHistoryEntry) {
        AppServices.settings.removeSearch(entry)
        showHistory()
    }

    func clearHistory() {
        AppServices.settings.searchHistory.removeAll()
        AppServices.settings.save()
        showHistory()
    }

    /// 検索から開いた・再生したものを履歴に残す（Android と同じく、画像と補足の行も付けて）。
    func remember(_ item: HomeItem) {
        AppServices.settings.addSearch(.of(item))
        showHistory()
    }

    func loadMoods() async {
        guard moods.isEmpty, !moodsLoading else { return }
        moodsLoading = true
        moodsFailure = nil
        let result = (try? await AppServices.music.moods()) ?? .failed(.network)
        moodsLoading = false
        if let sections = result.value { moods = sections } else { moodsFailure = result.failure ?? .network }
    }

    // MARK: 打っている間

    func loadSuggestions(_ text: String) async {
        let q = text.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else {
            suggestions = nil
            return
        }
        // 打つたびに呼ばないよう少し待つ（待つ間に次の文字が来たら取り消される）
        try? await Task.sleep(for: .milliseconds(200))
        if Task.isCancelled { return }
        let result = try? await AppServices.music.suggestions(q)
        if Task.isCancelled { return }
        if let value = result?.value { suggestions = value }
    }

    // MARK: 確定した検索

    func search(_ text: String) {
        let q = text.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        committed = text
        tab = .all
        AppServices.settings.addSearch(.query(q))
        showHistory()
        requestId += 1
        let id = requestId
        loading = true
        empty = false
        failure = nil
        top = nil
        songs = []
        albums = []
        artists = []
        songContinuation = nil
        albumContinuation = nil
        artistContinuation = nil
        podcasts = []
        episodes = []
        podcastContinuation = nil
        episodeContinuation = nil
        podcastsLoaded = false
        episodesLoaded = false
        podcastsLoading = false
        Task {
            let music = AppServices.music!
            async let s = music.searchSongs(q)
            async let al = music.searchAlbums(q)
            async let ar = music.searchArtists(q)
            let (songsResult, albumsResult, artistsResult) = (try? await (s, al, ar)) ?? (.failed(.network), .failed(.network), .failed(.network))
            guard id == requestId else { return }
            loading = false
            songs = songsResult.value?.songs ?? []
            songContinuation = songsResult.value?.continuation
            albums = albumsResult.value?.items ?? []
            albumContinuation = albumsResult.value?.continuation
            artists = artistsResult.value?.items ?? []
            artistContinuation = artistsResult.value?.continuation
            if songs.isEmpty && albums.isEmpty && artists.isEmpty {
                if !songsResult.isOk && !albumsResult.isOk && !artistsResult.isOk {
                    failure = songsResult.failure ?? .network
                } else {
                    empty = true
                }
                return
            }
            top = SearchModel.topResult(q, songs, artists, albums)
        }
    }

    /// 語の名前のアーティストがいれば、アーティストを曲より先に出す（Android の artistFirst と同じ）。
    var artistFirst: Bool { top?.isArtist == true }

    /// トップの検索結果: アーティストの名前が検索語と同じならアーティスト、ほかは最初の曲（Android の SearchOrder・Mac と同じ）。
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

    /// 絞り込みを変えた。ポッドキャスト・エピソードは初めて選んだときに読む。
    func tabChanged() {
        guard let q = committed?.trimmingCharacters(in: .whitespaces) else { return }
        let id = requestId
        if tab == .podcasts && !podcastsLoaded {
            podcastsLoaded = true
            podcastsLoading = true
            Task {
                let page = (try? await AppServices.music.searchPodcasts(q))?.value
                guard id == requestId else { return }
                podcastsLoading = false
                podcasts = page?.items ?? []
                podcastContinuation = page?.continuation
            }
        } else if tab == .episodes && !episodesLoaded {
            episodesLoaded = true
            podcastsLoading = true
            Task {
                let page = (try? await AppServices.music.searchEpisodes(q))?.value
                guard id == requestId else { return }
                podcastsLoading = false
                episodes = page?.items ?? []
                episodeContinuation = page?.continuation
            }
        }
    }

    /// 種類で絞った一覧の最後まで来たら続きを読む。
    func loadMore(_ tab: SearchTab) {
        guard !loadingMore, let q = committed?.trimmingCharacters(in: .whitespaces) else { return }
        let id = requestId
        let music = AppServices.music!
        switch tab {
        case .songs:
            guard let c = songContinuation else { return }
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await music.searchSongs(q, continuation: c))?.value, id == requestId else { return }
                songContinuation = page.continuation != c ? page.continuation : nil
                let known = Set(songs.map(\.videoId))
                songs += page.songs.filter { !known.contains($0.videoId) }
            }
        case .albums:
            guard let c = albumContinuation else { return }
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await music.searchAlbums(q, continuation: c))?.value, id == requestId else { return }
                albumContinuation = page.continuation != c ? page.continuation : nil
                let known = Set(albums.map(\.browseId))
                albums += page.items.filter { !known.contains($0.browseId) }
            }
        case .artists:
            guard let c = artistContinuation else { return }
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await music.searchArtists(q, continuation: c))?.value, id == requestId else { return }
                artistContinuation = page.continuation != c ? page.continuation : nil
                let known = Set(artists.map(\.browseId))
                artists += page.items.filter { !known.contains($0.browseId) }
            }
        case .podcasts:
            guard let c = podcastContinuation else { return }
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await music.searchPodcasts(q, continuation: c))?.value, id == requestId else { return }
                podcastContinuation = page.continuation != c ? page.continuation : nil
                let known = Set(podcasts.map(\.browseId))
                podcasts += page.items.filter { !known.contains($0.browseId) }
            }
        case .episodes:
            guard let c = episodeContinuation else { return }
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await music.searchEpisodes(q, continuation: c))?.value, id == requestId else { return }
                episodeContinuation = page.continuation != c ? page.continuation : nil
                let known = Set(episodes.map(\.id))
                episodes += page.items.filter { !known.contains($0.id) }
            }
        case .all:
            return
        }
    }
}

/// 読み込みの失敗の文（Android の failureMessage と同じ分け方）。
private func failureText(_ failure: FailureKind) -> String {
    switch failure {
    case .notLoggedIn: L.t("ログインが必要です", "You need to log in")
    case .network: L.t("通信できませんでした", "Couldn't connect")
    case .http, .parse: L.t("YouTube Music から読み込めませんでした", "Couldn't load from YouTube Music")
    }
}

/// 種類の絞り込みで、すべての一覧の中に出す件数（Android と同じ）。
private let previewCount = 4

/// 検索（Android の SearchScreen・SearchBrowse と同じ並び）。何も打っていないときは最近の検索と「すべてを見る」のジャンル、
/// 打っている間は候補、確定すると結果（トップの検索結果と、曲・アルバム・アーティストの絞り込み）。
/// 曲は押すと再生、ほかはそのページを開く。検索から再生・開いたものは履歴に残す。
struct SearchView: View {
    var initialQuery: String?
    @State private var model = SearchModel()
    @State private var shell = IOSShell.shared
    @State private var text = ""
    @State private var started = false

    init(initialQuery: String? = nil) {
        self.initialQuery = initialQuery
    }

    var body: some View {
        content
            .navigationTitle(L.t("検索", "Search"))
            .searchable(text: $text, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: L.t("曲、アーティスト、アルバム、ポッドキャスト", "Songs, artists, albums, podcasts"))
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .onSubmit(of: .search) { submit(text) }
            .task(id: text) { if phase == .typing { await model.loadSuggestions(text) } }
            .task {
                guard !started else { return }
                started = true
                if let q = initialQuery, !q.isEmpty { submit(q) }
                await model.loadMoods()
            }
            // ほかの画面から「検索」へ移ったときの言葉（入っていたら、それで検索して空に戻す）
            .onChange(of: shell.searchQuery, initial: true) { _, query in
                guard let query else { return }
                shell.searchQuery = nil
                if !query.isEmpty { submit(query) }
            }
            .onAppear { model.showHistory() }
    }

    private enum Phase { case browse, typing, results }

    private var phase: Phase {
        if text.trimmingCharacters(in: .whitespaces).isEmpty { return .browse }
        return text == model.committed ? .results : .typing
    }

    /// 言葉で検索する（欄にも入れて、キーボードを閉じる）。
    private func submit(_ query: String) {
        text = query
        // 検索の欄は開いたまま、キーボードだけ閉じる（欄を閉じると打った言葉も消える）
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        model.search(query)
    }

    @ViewBuilder private var content: some View {
        switch phase {
        case .browse: browse
        case .typing: suggestionList
        case .results: results
        }
    }

    // MARK: 何も打っていないとき（最近の検索と、気分・ジャンルのタイル）

    private var browse: some View {
        List {
            if !model.history.isEmpty {
                Section {
                    ForEach(model.history) { entry in
                        HistoryRow(entry: entry, onRemove: { model.remove(entry) }) { openHistory(entry) }
                            .swipeActions { Button(L.t("削除", "Delete"), systemImage: "trash", role: .destructive) { model.remove(entry) } }
                    }
                } header: {
                    HStack {
                        Text(L.t("最近の検索", "Recent searches"))
                        Spacer()
                        Button(L.t("すべて消去", "Clear all")) { model.clearHistory() }.font(.footnote).textCase(nil)
                    }
                }
            }
            if model.moodsLoading {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            } else if let failure = model.moodsFailure {
                Section {
                    LoadState(loading: false, failure: L.t("ジャンルを読み込めませんでした: ", "Couldn't load genres: ") + failureText(failure)) {
                        Task { await model.loadMoods() }
                    }
                }
            }
            ForEach(Array(model.moods.enumerated()), id: \.offset) { sectionIndex, section in
                Section(section.title.isEmpty ? L.t("すべてを見る", "Browse all") : section.title) {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                        ForEach(Array(section.categories.enumerated()), id: \.offset) { i, category in
                            MoodTile(category: category, color: Ui.tile(category.color, sectionIndex * 7 + i))
                        }
                    }
                    .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                    .listRowBackground(Color.clear)
                }
            }
        }
        .listStyle(.plain)
        .refreshable {
            model.showHistory()
            if model.moods.isEmpty { await model.loadMoods() }
        }
    }

    private func openHistory(_ entry: SearchHistoryEntry) {
        if entry.isQuery {
            submit(entry.title)
            return
        }
        // 押したものを履歴の先頭に上げてから開く・再生する
        AppServices.settings.addSearch(entry)
        model.showHistory()
        if let item = entry.toItem() { AppServices.nav.open(item) } else { submit(entry.title) }
    }

    // MARK: 打っている間の候補（語の候補を先に、そのまま開けるアーティスト・アルバム・曲を後に）

    private var suggestionList: some View {
        List {
            if let s = model.suggestions {
                ForEach(s.queries, id: \.self) { query in
                    Button { submit(query) } label: {
                        HStack(spacing: 16) {
                            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                            Text(query).lineLimit(1)
                            Spacer()
                            Image(systemName: "arrow.up.left").font(.footnote).foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                ForEach(s.artists, id: \.browseId) { artist in ItemRow(item: .artist(artist)) { open(.artist(artist)) } }
                ForEach(s.albums, id: \.browseId) { album in ItemRow(item: .album(album)) { open(.album(album)) } }
                ForEach(s.songs, id: \.videoId) { song in SongRow(song: song) { open(.song(song)) } }
            }
        }
        .listStyle(.plain)
        // 候補を見ている間はキーボードを閉じない（閉じると、確定した検索の結果と見分けがつかない）
        .scrollDismissesKeyboard(.never)
    }

    /// 検索から開く・再生する（履歴に残してから。曲は再生、ほかはそのページ）。
    private func open(_ item: HomeItem) {
        model.remember(item)
        AppServices.nav.open(item)
    }

    // MARK: 確定した検索の結果

    private var results: some View {
        List {
            Section {
                // 種類のチップ（6つあるので横に送る。Android の FilterChip と同じ）
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SearchTab.allCases, id: \.self) { tab in
                            SearchTabChip(title: tab.label, isOn: model.tab == tab) { model.tab = tab }
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            }
            if model.loading || (model.podcastsLoading && (model.tab == .podcasts || model.tab == .episodes)) {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40).listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else if let failure = model.failure {
                Text(failureText(failure)).foregroundStyle(.secondary).listRowSeparator(.hidden)
            } else if model.empty {
                Text(L.t("見つかりませんでした", "No results found")).foregroundStyle(.secondary).listRowSeparator(.hidden)
            } else {
                switch model.tab {
                case .all: allSections
                case .songs: pagedSongs
                case .albums: paged(model.albums.map { HomeItem.album($0) }, more: model.albumContinuation != nil, tab: .albums)
                case .artists: paged(model.artists.map { HomeItem.artist($0) }, more: model.artistContinuation != nil, tab: .artists)
                case .podcasts: pagedPodcasts
                case .episodes: pagedEpisodes
                }
            }
        }
        .listStyle(.plain)
        .scrollDismissesKeyboard(.immediately)
        .animation(.default, value: model.tab)
        .onChange(of: model.tab) { model.tabChanged() }
    }

    @ViewBuilder private var pagedPodcasts: some View {
        if model.podcasts.isEmpty {
            Text(L.t("見つかりませんでした", "No results found")).foregroundStyle(.secondary).listRowSeparator(.hidden)
        }
        ForEach(model.podcasts) { podcast in
            PodcastRow(podcast: podcast) { AppServices.nav.podcast(podcast) }
                .onAppear { if podcast.id == model.podcasts.last?.id { model.loadMore(.podcasts) } }
        }
        if model.loadingMore { ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden) }
    }

    @ViewBuilder private var pagedEpisodes: some View {
        if model.episodes.isEmpty {
            Text(L.t("見つかりませんでした", "No results found")).foregroundStyle(.secondary).listRowSeparator(.hidden)
        }
        ForEach(model.episodes) { episode in
            // 押したエピソードだけを流す（曲を押したのと同じ）
            EpisodeRow(episode: episode, showShow: true) { AppServices.player.playSong(episode.song) }
                .onAppear { if episode.id == model.episodes.last?.id { model.loadMore(.episodes) } }
        }
        if model.loadingMore { ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden) }
    }

    @ViewBuilder private var allSections: some View {
        if let top = model.top {
            Section(L.t("トップの検索結果", "Top result")) {
                TopResultCard(item: top, open: { open(top) }, play: {
                    model.remember(top)
                    AppServices.nav.play(top)
                })
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            }
        }
        // 種類ごとに少しずつ。続きは種類の絞り込みで見る
        let order: [SearchTab] = model.artistFirst ? [.artists, .songs, .albums] : [.songs, .albums, .artists]
        ForEach(order, id: \.self) { tab in
            switch tab {
            case .songs where !model.songs.isEmpty:
                Section { ForEach(model.songs.prefix(previewCount)) { song in SongRow(song: song) { open(.song(song)) } } } header: {
                    sectionHeader(tab, more: model.songs.count > previewCount)
                }
            case .albums where !model.albums.isEmpty:
                Section { ForEach(model.albums.prefix(previewCount), id: \.browseId) { a in ItemRow(item: .album(a)) { open(.album(a)) } } } header: {
                    sectionHeader(tab, more: model.albums.count > previewCount)
                }
            case .artists where !model.artists.isEmpty:
                Section { ForEach(model.artists.prefix(previewCount), id: \.browseId) { a in ItemRow(item: .artist(a)) { open(.artist(a)) } } } header: {
                    sectionHeader(tab, more: model.artists.count > previewCount)
                }
            default:
                EmptyView()
            }
        }
    }

    private func sectionHeader(_ tab: SearchTab, more: Bool) -> some View {
        HStack {
            Text(tab.label)
            Spacer()
            if more { Button(L.t("すべて表示", "Show all")) { model.tab = tab }.font(.footnote).textCase(nil) }
        }
    }

    @ViewBuilder private var pagedSongs: some View {
        ForEach(model.songs) { song in
            SongRow(song: song) { open(.song(song)) }
                .onAppear { if song.videoId == model.songs.last?.videoId { model.loadMore(.songs) } }
        }
        if model.loadingMore { ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden) }
    }

    @ViewBuilder private func paged(_ items: [HomeItem], more: Bool, tab: SearchTab) -> some View {
        ForEach(items) { item in
            ItemRow(item: item) { open(item) }
                .onAppear { if item.key == items.last?.key { model.loadMore(tab) } }
        }
        if model.loadingMore { ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden) }
    }
}

/// 検索の結果の種類のチップ。
private struct SearchTabChip: View {
    var title: String
    var isOn: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .foregroundStyle(isOn ? Color.white : Color.primary)
                .background(Capsule().fill(isOn ? Color.accentColor : Color.primary.opacity(0.08)))
        }
        .buttonStyle(.plain)
    }
}

/// アルバム・アーティストの1行（アーティストは丸い絵）。
private struct ItemRow: View {
    var item: HomeItem
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Cover(url: Ui.small(item.thumbnailUrl), size: 48, round: item.isArtist, radius: 4)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title).lineLimit(1)
                    Text(detail).subtle()
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(L.t("再生", "Play"), systemImage: "play.fill") { AppServices.nav.play(item) }
        }
    }

    private var detail: String {
        switch item {
        case .artist(let a): a.subtitle ?? L.t("アーティスト", "Artist")
        default: item.subtitle ?? Ui.kind(item)
        }
    }
}

/// 最近の検索の1行（画像と、種類の分かる補足の行。語は検索のアイコン）。
private struct HistoryRow: View {
    var entry: SearchHistoryEntry
    var onRemove: () -> Void
    var action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: action) {
                HStack(spacing: 12) {
                    ZStack {
                        if entry.isQuery || entry.thumbnailUrl == nil {
                            RoundedRectangle(cornerRadius: 4).fill(.fill.tertiary)
                            Image(systemName: icon).foregroundStyle(.secondary)
                        } else {
                            Cover(url: Ui.small(entry.thumbnailUrl), size: 48, round: entry.isRound, radius: 4)
                        }
                    }
                    .frame(width: 48, height: 48)
                    .clipShape(entry.isRound ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: 4)))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.title).lineLimit(1)
                        if let detail { Text(detail).subtle() }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button(action: onRemove) {
                Image(systemName: "xmark").font(.footnote).foregroundStyle(.secondary).frame(width: 32, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.t("履歴から削除", "Remove from history"))
        }
    }

    private var icon: String {
        switch entry.kind {
        case "artist": "person"
        case "album": "square.stack"
        case "song": "music.note"
        case "playlist": "music.note.list"
        default: "magnifyingglass"
        }
    }

    private var detail: String? {
        switch entry.kind {
        case "query": nil
        case "album": [L.t("アルバム", "Album"), entry.subtitle].compactMap { $0 }.joined(separator: " • ")
        default: entry.subtitle
        }
    }
}

/// 気分・ジャンルの色のタイル（Spotify の「すべてを見る」のタイル）。
private struct MoodTile: View {
    var category: MoodCategory
    var color: Color

    var body: some View {
        Button { AppServices.nav.mood(category) } label: {
            Text(category.title)
                .font(.headline)
                .foregroundStyle(.white)
                .multilineTextAlignment(.leading)
                .padding(12)
                .frame(maxWidth: .infinity, minHeight: 88, alignment: .topLeading)
                .background(color, in: RoundedRectangle(cornerRadius: 10))
                .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
    }
}

/// トップの検索結果の大きなカード（押すと開く・曲は再生。右下の丸いボタンで再生）。
private struct TopResultCard: View {
    var item: HomeItem
    var open: () -> Void
    var play: () -> Void

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Button(action: open) {
                VStack(alignment: .leading, spacing: 8) {
                    Cover(url: Ui.img(item.thumbnailUrl), size: 96, round: item.isArtist, radius: 6)
                    Text(item.title).font(.title2.weight(.bold)).lineLimit(2).multilineTextAlignment(.leading)
                    Text(detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            PlayCircle(size: 48, action: play).padding(16)
        }
        .glassCard(16)
        .contextMenu {
            if case .song(let song) = item { SongMenuItems(song: song) }
        }
    }

    private var detail: String {
        switch item {
        case .song(let s): [L.t("曲", "Song"), s.artistsText].filter { !$0.isEmpty }.joined(separator: " • ")
        case .artist: L.t("アーティスト", "Artist")
        case .album(let a): [L.t("アルバム", "Album"), a.subtitle].compactMap { $0 }.joined(separator: " • ")
        case .playlist(let p): p.subtitle ?? L.t("プレイリスト", "Playlist")
        }
    }
}
#endif
