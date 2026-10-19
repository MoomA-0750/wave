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
    /// 曲・アルバム・アーティストのどれも読めなかった（0件の「見つかりませんでした」と分けて理由を出す。Android と同じ）。
    var failure: FailureKind?
    var tab = "all" {
        didSet {
            if tab == "live" { loadLiveIfNeeded() }
            if tab == "lyrics" { loadLyricsIfNeeded() }
        }
    }
    var top: HomeItem?
    var songs: [TrackRow] = []
    var albums: [AlbumSummary] = []
    var artists: [ArtistSummary] = []
    /// ポッドキャスト・エピソード（その絞り込みを初めて選んだときに読む。「すべて」には出さない。Android と同じ）。
    var podcasts: [PodcastSummary] = []
    var episodes: [PodcastEpisode] = []
    var podcastsLoading = false
    var podcastsFailure: FailureKind?
    var episodesFailure: FailureKind?
    var moods: [(MoodCategory, Color)] = []
    var history: [SearchHistoryEntry] = []
    /// 「ライブ」のタブ（ライブ配信の検索）。タブを開いたときに読む。
    var liveSongs: [TrackRow] = []
    var liveLoading = false
    var liveFailure: FailureKind?
    @ObservationIgnored private var liveQuery: String?
    /// 「歌詞」のタブ（打った語を歌詞の一節として探した曲。Android と同じ）。タブを開いたときに読む。続きのページは無い。
    var lyricsHits: [LyricsHit] = []
    var lyricsLoading = false
    var lyricsFailure: FailureKind?
    @ObservationIgnored private var lyricsQuery: String?
    @ObservationIgnored private var liveContinuation: String?
    @ObservationIgnored private var songContinuation: String?
    @ObservationIgnored private var albumContinuation: String?
    @ObservationIgnored private var artistContinuation: String?
    @ObservationIgnored private var podcastContinuation: String?
    @ObservationIgnored private var episodeContinuation: String?
    @ObservationIgnored private var podcastsLoaded = false
    @ObservationIgnored private var episodesLoaded = false
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
        failure = nil
        songs = []
        podcasts = []
        episodes = []
        podcastsFailure = nil
        episodesFailure = nil
        podcastsLoaded = false
        episodesLoaded = false
        podcastContinuation = nil
        episodeContinuation = nil
        liveSongs = []
        liveQuery = nil
        liveContinuation = nil
        liveFailure = nil
        lyricsHits = []
        lyricsQuery = nil
        lyricsFailure = nil
        if tab == "live" { loadLiveIfNeeded() }
        if tab == "lyrics" { loadLyricsIfNeeded() }
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
        // ポッドキャスト・エピソードで絞っているときは、その一覧も読み直す
        if tab == "podcasts" || tab == "episodes" { tabChanged() }
        if found.isEmpty && albums.isEmpty && artists.isEmpty {
            // どれも読めなかったら理由を出す（どれかが読めて0件なら「見つかりませんでした」）
            if !songsResult.isOk && !albumsResult.isOk && !artistsResult.isOk {
                failure = songsResult.failure ?? .network
            } else {
                empty = true
            }
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

    /// 読めなかったポッドキャスト・エピソードを読み直す。
    func retryTab() {
        if tab == "podcasts" { podcastsLoaded = false }
        if tab == "episodes" { episodesLoaded = false }
        tabChanged()
    }

    /// 絞り込みを変えた。ポッドキャスト・エピソードは初めて選んだときに読む。
    func tabChanged() {
        guard let q = query else { return }
        let id = requestId
        if tab == "podcasts" && !podcastsLoaded {
            podcastsLoaded = true
            podcastsLoading = true
            Task {
                // Apple の目録の番組（RSS、ADR-0012）を先に、YouTube の番組を後に並べる
                let result = await AppServices.feeds.searchFirstPage(q)
                guard id == requestId else { return }
                podcastsLoading = false
                podcasts = result.value?.items ?? []
                podcastContinuation = result.value?.continuation
                podcastsFailure = result.value == nil ? result.failure ?? .network : nil
                AppPaths.log("search podcasts n=\(podcasts.count)")
            }
        } else if tab == "episodes" && !episodesLoaded {
            episodesLoaded = true
            podcastsLoading = true
            Task {
                let result = (try? await AppServices.music.searchEpisodes(q)) ?? .failed(.network)
                guard id == requestId else { return }
                podcastsLoading = false
                episodes = result.value?.items ?? []
                episodeContinuation = result.value?.continuation
                episodesFailure = result.value == nil ? result.failure ?? .network : nil
                AppPaths.log("search episodes n=\(episodes.count)")
            }
        }
    }

    /// ライブ配信の検索（YouTube Music の動画のライブと YouTube のライブを合わせたもの）。同じ語では読み直さない。
    func loadLiveIfNeeded() {
        guard let q = query, liveQuery != q else { return }
        liveQuery = q
        liveLoading = true
        liveFailure = nil
        let id = requestId
        Task {
            let result = (try? await AppServices.music.searchLive(q)) ?? .failed(.network)
            guard id == requestId, liveQuery == q else { return }
            liveLoading = false
            guard let page = result.value else {
                liveFailure = result.failure ?? .network
                liveQuery = nil
                return
            }
            liveContinuation = page.continuation
            liveSongs = page.songs.enumerated().map { TrackRow(number: $0.offset + 1, song: $0.element) }
        }
    }

    /// 歌詞から探す（絞り込まない検索の上位の曲の歌詞と照らし合わせる）。歌詞に語が見つかった曲が先に来る。同じ語では読み直さない。
    func loadLyricsIfNeeded() {
        guard let q = query, lyricsQuery != q else { return }
        lyricsQuery = q
        lyricsLoading = true
        lyricsFailure = nil
        let id = requestId
        Task {
            let result = (try? await AppServices.lyricsSearch.search(q)) ?? .failed(.network)
            guard id == requestId, lyricsQuery == q else { return }
            lyricsLoading = false
            guard let hits = result.value else {
                lyricsFailure = result.failure ?? .network
                lyricsQuery = nil
                AppPaths.log("search lyrics failed")
                return
            }
            lyricsHits = hits
            AppPaths.log("search lyrics n=\(hits.count) matched=\(hits.filter { $0.match != nil }.count)")
        }
    }

    func loadMoreLive() {
        guard !loadingMore, let c = liveContinuation, let q = liveQuery else { return }
        loadingMore = true
        let id = requestId
        Task {
            defer { loadingMore = false }
            guard let page = (try? await AppServices.music.searchLive(q, continuation: c))?.value, id == requestId, liveQuery == q else { return }
            liveContinuation = page.continuation != c ? page.continuation : nil
            var known = Set(liveSongs.map(\.song.videoId))
            let n = liveSongs.count
            liveSongs += page.songs.filter { known.insert($0.videoId).inserted }.enumerated().map { TrackRow(number: n + $0.offset + 1, song: $0.element) }
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
        } else if tab == "podcasts", let c = podcastContinuation {
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await AppServices.music.searchPodcasts(q, continuation: c))?.value, id == requestId else { return }
                podcastContinuation = page.continuation
                podcasts += page.items.filter { p in !podcasts.contains { $0.browseId == p.browseId } }
            }
        } else if tab == "episodes", let c = episodeContinuation {
            loadingMore = true
            Task {
                defer { loadingMore = false }
                guard let page = (try? await AppServices.music.searchEpisodes(q, continuation: c))?.value, id == requestId else { return }
                episodeContinuation = page.continuation
                episodes += page.items.filter { e in !episodes.contains { $0.id == e.id } }
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
                Text(L.t("アルバム", "Albums")).tag("albums")
                Text(L.t("アーティスト", "Artists")).tag("artists")
                Text(L.t("ライブ", "Live")).tag("live")
                Text(L.t("ポッドキャスト", "Podcasts")).tag("podcasts")
                Text(L.t("エピソード", "Episodes")).tag("episodes")
                Text(L.t("歌詞", "Lyrics")).tag("lyrics")
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .fixedSize()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 8, leading: 16, bottom: 0, trailing: 16))
            .onChange(of: model.tab) { model.tabChanged() }
            if model.tab == "live" {
                live
            } else if model.tab == "lyrics" {
                lyrics
            } else if model.loading || (model.podcastsLoading && (model.tab == "podcasts" || model.tab == "episodes")) {
                ProgressView().padding(.top, 48)
                Spacer()
            } else if model.tab == "podcasts" || model.tab == "episodes" {
                podcastResults
            } else if let failure = model.failure {
                failed(failure) { model.search(model.query) }
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

    /// ポッドキャスト（番組のカード）・エピソード（行）の結果。
    @ViewBuilder
    private var podcastResults: some View {
        if let failure = model.tab == "podcasts" ? model.podcastsFailure : model.episodesFailure {
            failed(failure) { model.retryTab() }
        } else if model.tab == "podcasts" ? model.podcasts.isEmpty : model.episodes.isEmpty {
            Text(L.t("見つかりませんでした", "No results found")).subtitleStyle().padding(.top, 64)
            Spacer()
        } else if model.tab == "podcasts" {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 176), spacing: 0)], spacing: 8) {
                    ForEach(Array(model.podcasts.enumerated()), id: \.element.id) { i, podcast in
                        PodcastCard(podcast: podcast, onOpen: { AppServices.settings.addSearch(.of(podcast)) }).onAppear { if i == model.podcasts.count - 1 { model.loadMoreCards() } }
                    }
                }
                .padding(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(model.episodes.enumerated()), id: \.element.id) { i, episode in
                        // 押したエピソードだけを流す（曲を押したのと同じ）
                        EpisodeRowView(episode: episode, showShow: true, onPlay: { AppServices.player.playSong(episode.song) })
                            .onAppear { if i == model.episodes.count - 1 { model.loadMoreCards() } }
                    }
                }
                .padding(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
            }
        }
    }

    /// 「ライブ」のタブ: いま配信中のライブ。押すと HLS で流す（後ろにラジオは足さない）。
    @ViewBuilder
    private var live: some View {
        if model.liveLoading {
            ProgressView().padding(.top, 48)
            Spacer()
        } else if let failure = model.liveFailure {
            failed(failure, L.t("ライブ配信を検索できませんでした", "Couldn't search live streams")) { model.loadLiveIfNeeded() }
        } else if model.liveSongs.isEmpty {
            Text(L.t("配信中のライブは見つかりませんでした", "No live streams found")).subtitleStyle().padding(.top, 64)
            Spacer()
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(model.liveSongs) { row in songRow(row).onAppear { if row.id == model.liveSongs.last?.id { model.loadMoreLive() } } }
                }
                .padding(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
            }
        }
    }

    /// 「歌詞」のタブ: 歌詞に語が見つかった曲を先に、見つかった行を曲の下に引用して出す（大体合っているときは「≈」）。
    /// 曲はほかの曲の行と同じに、ダブルクリックで再生する。
    @ViewBuilder
    private var lyrics: some View {
        if model.lyricsLoading {
            ProgressView().padding(.top, 48)
            Spacer()
        } else if let failure = model.lyricsFailure {
            failed(failure, L.t("歌詞から探せませんでした", "Couldn't search by lyrics")) { model.loadLyricsIfNeeded() }
        } else if model.lyricsHits.isEmpty {
            Text(L.t("見つかりませんでした", "No results found")).subtitleStyle().padding(.top, 64)
            Spacer()
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(model.lyricsHits.enumerated()), id: \.element.id) { i, hit in
                        VStack(alignment: .leading, spacing: 0) {
                            songRow(TrackRow(number: i + 1, song: hit.song))
                            if let match = hit.match {
                                // 番号・画像の幅だけ下げて、曲名の下にそろえる
                                Text(SearchPageView.quote(match))
                                    .font(.callout)
                                    .foregroundStyle(Color.accentColor)
                                    .lineLimit(2)
                                    .padding(EdgeInsets(top: 0, leading: 108, bottom: 8, trailing: 16))
                            }
                        }
                    }
                }
                .padding(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
            }
        }
    }

    /// 読み込みの失敗: 0件の「見つかりませんでした」と分けて、理由（ログインしていない・通信できない・応答が無い・読み取れない）を出す。
    @ViewBuilder
    private func failed(_ failure: FailureKind, _ title: String = L.t("検索できませんでした", "Couldn't search"), retry: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Text(title).subtitleStyle()
            Text(FailureText.message(failure)).subtle()
            Button(L.t("もう一度", "Retry"), action: retry)
        }
        .padding(.top, 64)
        Spacer()
    }

    /// 合った歌詞の行の引用。大体合っているときは先頭に「≈ 」（Android・Windows と同じ）。
    static func quote(_ match: LyricsMatch) -> String { (match.exact ? "" : "≈ ") + "\u{201C}" + match.line + "\u{201D}" }

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
            if let podcast = entry.toPodcast() {
                AppServices.nav.podcast(podcast)
            } else if let item = entry.toItem() {
                AppServices.nav.open(item)
            } else {
                MainWindowModel.shared.searchFor(entry.title)
            }
        }
    }
}
#endif
