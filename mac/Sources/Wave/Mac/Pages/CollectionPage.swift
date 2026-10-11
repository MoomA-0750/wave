#if os(macOS)
import SwiftUI
import WaveCore

/// プレイリスト・アルバムのページの状態。
@MainActor @Observable
final class CollectionModel {
    /// added・published はサーバーで並べる（自分のプレイリストなど、並べ替えのメニューがあるときだけ）。ほかは手元で並べる。
    enum SortKey: String, CaseIterable { case custom = "Custom", added = "Added", published = "Published", title = "Title", artist = "Artist", album = "Album", duration = "Duration" }

    let request: CollectionRequest
    var title: String?
    var thumbnailUrl: String?
    var detail: String?
    var artistText: String?
    var albumArtist: ArtistRef?
    var gradient: Color?
    var loading = true
    var error: String?
    /// 読み込んだ全部の行（元の並び）。
    var all: [TrackRow] = []
    var filter = ""
    var kindFilter: String?
    var sort: SortKey = .custom
    var descending = false
    var sortable = false
    var editable = false
    /// 一覧の「シャッフル再生」の指定（Android と同じ）。この一覧を再生しているときは、キューのシャッフルの入り切りになる。
    var shufflePlay = false
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = 0

    init(request: CollectionRequest) {
        self.request = request
        title = request.title
        thumbnailUrl = request.thumbnailUrl
        detail = request.subtitle
        restoreOrder()
    }

    var isLiked: Bool { request.id == MusicRepository.likedSongs }

    private var serverSort: Bool { sort == .added || sort == .published }

    /// 読み込みのときに送る並べ方（プレイリストだけ）。
    private var serverOrder: String {
        switch sort {
        case .added: PlaylistOrder.params(PlaylistOrder.added, descending: descending)
        case .published: PlaylistOrder.params(PlaylistOrder.published, descending: descending)
        default: PlaylistOrder.defaultOrdering
        }
    }

    private func restoreOrder() {
        sort = .custom
        descending = false
        guard !request.isAlbum, let saved = AppServices.settings.playlistOrders[request.id] else { return }
        let parts = saved.split(separator: ":")
        sort = parts.first.flatMap { SortKey(rawValue: String($0)) } ?? .custom
        descending = parts.count > 1 && parts[1] == "desc"
    }

    private func saveOrder() {
        guard !request.isAlbum else { return }
        AppServices.settings.playlistOrders[request.id] = "\(sort.rawValue):\(descending ? "desc" : "asc")"
        AppServices.settings.save()
    }

    static func label(_ key: SortKey) -> String {
        switch key {
        case .custom: L.t("カスタム順", "Custom order")
        case .added: L.t("追加した日", "Date added")
        case .published: L.t("リリース日", "Release date")
        case .title: L.t("タイトル", "Title")
        case .artist: L.t("アーティスト", "Artist")
        case .album: L.t("アルバム", "Album")
        case .duration: L.t("再生時間", "Duration")
        }
    }

    var sortChoices: [SortKey] { SortKey.allCases.filter { sortable || ($0 != .added && $0 != .published) } }

    /// 同じ並べ方をもう一度選ぶと向きを変える。サーバーで並べる並べ方に替えた・外したときは読み直す。
    func choose(_ key: SortKey) {
        let wasServer = serverSort
        if key == sort {
            descending.toggle()
        } else {
            sort = key
            // 追加した日は新しい順から（Spotify と同じ）
            descending = key == .added
        }
        saveOrder()
        if !request.isAlbum && (wasServer || serverSort) { load() }
    }

    func load() {
        task?.cancel()
        generation += 1
        let mine = generation
        all = []
        loading = true
        error = nil
        if isLiked { gradient = Color(red: 0x50 / 255, green: 0x38 / 255, blue: 0xA0 / 255) } else { Task { await color() } }
        task = Task {
            if request.isAlbum { await loadAlbum(mine) } else { await loadPlaylist(mine) }
            if mine == generation {
                loading = false
                if all.isEmpty && error == nil { error = L.t("曲がありません", "No songs") }
            }
        }
    }

    private func color() async {
        if let c = await Artwork.dominantColor(thumbnailUrl) { gradient = c }
    }

    private func loadAlbum(_ mine: Int) async {
        guard let album = (try? await AppServices.music.album(request.id))?.value, mine == generation else {
            if mine == generation { error = L.t("アルバムを読み込めませんでした", "Couldn't load the album") }
            return
        }
        playlistIdForPlayback = album.playlistId
        title = album.title
        let changedArt = thumbnailUrl != album.thumbnailUrl
        thumbnailUrl = album.thumbnailUrl ?? thumbnailUrl
        if changedArt { await color() }
        let total = album.songs.reduce(0) { $0 + ($1.durationSeconds ?? 0) }
        detail = [album.subtitle, "\(L.songs(album.songs.count)), \(L.length(total))"].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
        // アーティストは題名の下に別の行で出す（押すとアーティストのページ。Android と同じ）
        albumArtist = album.artists.first { $0.browseId != nil }
        artistText = album.artists.isEmpty ? nil : album.artists.map(\.name).joined(separator: ", ")
        all = album.songs.enumerated().map { TrackRow(number: $0.offset + 1, song: $0.element, showAlbum: false, showArtwork: false) }
    }

    @ObservationIgnored var playlistIdForPlayback: String?

    private func loadPlaylist(_ mine: Int) async {
        if let artist = request.artist {
            await loadLikedByArtist(artist, mine)
            return
        }
        var first = true
        let result = (try? await AppServices.music.fullPlaylist(request.id, onPage: { page in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard mine == self.generation else { return }
                    if first {
                        first = false
                        self.editable = page.editable
                        self.sortable = page.sortable
                        self.title = page.title ?? self.request.title
                        if self.request.thumbnailUrl == nil, let t = page.thumbnailUrl {
                            self.thumbnailUrl = t
                            Task { await self.color() }
                        }
                        self.detail = page.subtitle ?? self.request.subtitle
                    }
                    var rows = self.all
                    for i in rows.count..<page.songs.count {
                        rows.append(TrackRow(number: i + 1, song: page.songs[i], setVideoId: i < page.setVideoIds.count ? page.setVideoIds[i] : nil))
                    }
                    self.all = rows
                }
            }
        }, order: serverOrder)) ?? .failed(.network)
        await withCheckedContinuation { c in DispatchQueue.main.async { c.resume() } }
        guard mine == generation else { return }
        guard let page = result.value else {
            error = result.failure == .notLoggedIn ? L.t("このプレイリストを見るにはログインしてください", "Log in to see this playlist")
                : L.t("プレイリストを読み込めませんでした", "Couldn't load the playlist")
            return
        }
        let total = all.reduce(0) { $0 + ($1.song.durationSeconds ?? 0) }
        // サーバーの補足（曲数・時間を含む）があればそれを出す。無ければ読めた曲から数える
        detail = page.subtitle ?? request.subtitle ?? "\(L.songs(all.count)), \(L.length(total))"
    }

    /// 高く評価した曲のうち、そのアーティストのもの（ライブラリで覚えた一覧から絞る）。
    private func loadLikedByArtist(_ artist: ArtistFilter, _ mine: Int) async {
        let (songs, complete) = await AppServices.library.likedSongs()
        guard mine == generation else { return }
        all = LibraryState.byArtist(songs, artist.browseId, artist.name).enumerated().map { TrackRow(number: $0.offset + 1, song: $0.element) }
        let total = all.reduce(0) { $0 + ($1.song.durationSeconds ?? 0) }
        detail = "\(artist.name) • \(L.songs(all.count))\(complete ? "" : "+"), \(L.length(total))"
    }

    /// 絞り込みと並べ替えを当てた、出す行。再生は出ている並びで行う。
    var rows: [TrackRow] {
        let terms = filter.split(whereSeparator: { $0 == " " || $0 == "\u{3000}" }).map(String.init)
        var view = all.filter { row in
            let s = row.song
            let text = [s.title, s.artistsText, s.album?.title ?? ""].joined(separator: " ")
            let folded = CollectionModel.fold(text)
            let match = terms.allSatisfy { folded.contains(CollectionModel.fold($0)) }
            return match && (kindFilter == nil || s.kind == kindFilter)
        }
        func ordered<T: Comparable>(_ key: (TrackRow) -> T) {
            view = view.enumerated().sorted { a, b in
                let ka = key(a.element), kb = key(b.element)
                if ka == kb { return a.offset < b.offset }
                return descending ? ka > kb : ka < kb
            }.map(\.element)
        }
        switch sort {
        case .title: ordered { $0.song.title.lowercased() }
        case .artist: ordered { $0.song.artistsText.lowercased() }
        case .album: ordered { ($0.song.album?.title ?? "").lowercased() }
        case .duration: ordered { $0.song.durationSeconds ?? 0 }
        // サーバーで並べたもの（追加した日・公開日）はその並びのまま。カスタム順の逆は手元で
        case .custom: if descending { view.reverse() }
        case .added, .published: break
        }
        return view
    }

    /// 大文字・小文字、全角・半角、ひらがな・カタカナを区別しない比べ方の形（Windows の IgnoreCase・IgnoreWidth・IgnoreKanaType と同じ）。
    static func fold(_ text: String) -> String {
        let width = text.applyingTransform(.fullwidthToHalfwidth, reverse: false) ?? text
        let kana = width.applyingTransform(.hiraganaToKatakana, reverse: false) ?? width
        return kana.folding(options: [.caseInsensitive, .widthInsensitive], locale: nil)
    }

    var isPlayingHere: Bool { AppServices.player.sourceId == request.id && AppServices.player.current != nil }

    func play(rows: [TrackRow], start: Int = 0, shuffle: Bool? = nil) {
        AppServices.player.playList(rows.map(\.song), start: start, source: request.id, shuffleOverride: shuffle)
    }

    func remove(_ row: TrackRow) {
        guard let setId = row.setVideoId else { return }
        AppServices.library.membership.invalidate(request.id)
        Task {
            if (try? await AppServices.music.removeFromPlaylist(request.id, videoId: row.song.videoId, setVideoId: setId)) == true {
                all.removeAll { $0.id == row.id }
                AppServices.player.message = L.t("プレイリストから削除しました", "Removed from playlist")
            } else {
                AppServices.player.message = L.t("削除できませんでした", "Couldn't remove it")
            }
        }
    }
}

/// プレイリスト・アルバムのページ（Spotify と同じ: ジャケットの色のグラデーション、大きなジャケットと名前、再生ボタン、曲の表）。
struct CollectionPageView: View {
    @State private var model: CollectionModel
    @State private var filterOpen = false
    @State private var selected: String?
    @FocusState private var filterFocus: Bool

    init(request: CollectionRequest) {
        _model = State(initialValue: CollectionModel(request: request))
    }

    var body: some View {
        let player = AppServices.player!
        let rows = model.rows
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                header
                controls(player)
                kindChips
                TrackHeader(showAlbum: !model.request.isAlbum).padding(.horizontal, 12)
                if let error = model.error { Text(error).padding(EdgeInsets(top: 24, leading: 12, bottom: 24, trailing: 12)) }
                if !model.all.isEmpty && rows.isEmpty {
                    Text(L.t("一致する曲はありません", "No matching songs")).subtle().padding(24)
                }
                ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                    TrackRowView(row: row, selected: selected == row.id,
                                 removeFromPlaylist: model.editable && row.setVideoId != nil ? { model.remove(row) } : nil,
                                 onPlay: { model.play(rows: rows, start: i) }, onSelect: { selected = row.id })
                }
            }
            .padding(EdgeInsets(top: 0, leading: 12, bottom: 24, trailing: 12))
        }
        .background(alignment: .top) {
            LinearGradient(colors: [(model.gradient ?? .clear).opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).frame(height: 360)
        }
        .onKeyPress(.return) {
            guard let selected, let i = rows.firstIndex(where: { $0.id == selected }) else { return .ignored }
            model.play(rows: rows, start: i)
            return .handled
        }
        .onAppear { if model.all.isEmpty { model.load() } }
    }

    private var header: some View {
        HStack(alignment: .bottom, spacing: 24) {
            if model.isLiked {
                LikedCover(size: 220, radius: 8)
            } else {
                Cover(url: Ui.large(model.thumbnailUrl), size: 220, radius: 8)
            }
            // 題名・アーティスト（アルバムだけ。押すとアーティストのページ）・説明。下端をジャケットの下端にそろえる
            VStack(alignment: .leading, spacing: 0) {
                Text(model.title ?? "").font(.system(size: 56, weight: .bold)).lineLimit(2).minimumScaleFactor(0.5).padding(.bottom, 6)
                if let artist = model.artistText {
                    LinkText(text: artist) { if let id = model.albumArtist?.browseId { AppServices.nav.artist(id) } }
                        .font(.body.weight(.semibold)).padding(.bottom, 2)
                }
                if let detail = model.detail { Text(detail).foregroundStyle(.secondary).lineLimit(2) }
            }
        }
        .padding(EdgeInsets(top: 40, leading: 12, bottom: 24, trailing: 12))
    }

    private func controls(_ player: PlayerService) -> some View {
        HStack(spacing: 16) {
            PlayCircleButton(action: {
                if model.isPlayingHere { player.togglePlay() } else { model.play(rows: model.rows, shuffle: model.shufflePlay ? true : nil) }
            }) {
                Image(systemName: model.isPlayingHere && player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20))
            }
            .disabled(model.rows.isEmpty)
            IconToggle(isOn: model.isPlayingHere ? player.shuffle : model.shufflePlay, help: L.t("シャッフル", "Shuffle")) {
                if model.isPlayingHere { player.toggleShuffle() } else { model.shufflePlay.toggle() }
            } label: { Image(systemName: "shuffle").font(.system(size: 20)) }
            Menu {
                Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.queueAll(model.rows.map(\.song), next: true) }
                Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.queueAll(model.rows.map(\.song), next: false) }
            } label: { Image(systemName: "ellipsis").font(.system(size: 20)) }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().help(L.t("その他", "More"))
            if model.loading { ProgressView().controlSize(.small) }
            Spacer()
            // 右端: 一覧の中を探す（🔍で開く）と並べ替え（Spotify と同じ）
            Button {
                filterOpen.toggle()
                if filterOpen { filterFocus = true } else { model.filter = "" }
            } label: { Image(systemName: "magnifyingglass") }
                .buttonStyle(IconButtonStyle()).help(L.t("この一覧の中を検索", "Search in this list"))
            if filterOpen {
                TextField(L.t("この一覧の中を検索", "Search in this list"), text: $model.filter)
                    .textFieldStyle(.roundedBorder).frame(width: 240).focused($filterFocus)
                    .onKeyPress(.escape) {
                        model.filter = ""
                        filterOpen = false
                        return .handled
                    }
            }
            Menu {
                ForEach(model.sortChoices, id: \.self) { key in
                    Button {
                        model.choose(key)
                    } label: {
                        if key == model.sort {
                            Label(CollectionModel.label(key), systemImage: key == .custom ? "checkmark" : model.descending ? "arrow.down" : "arrow.up")
                        } else {
                            Text(CollectionModel.label(key))
                        }
                    }
                }
            } label: { Text(CollectionModel.label(model.sort)).font(.callout).foregroundStyle(.secondary) }
                .menuStyle(.borderlessButton).fixedSize()
        }
        .padding(EdgeInsets(top: 8, leading: 12, bottom: 16, trailing: 12))
    }

    // 曲の種類で絞る（動画や歌ってみたと、ふつうの曲を分ける。Android と同じ）。同じものをもう一度押すと外す
    private var kindChips: some View {
        HStack(spacing: 8) {
            Chip(title: L.t("すべて", "All"), isOn: model.kindFilter == nil) { model.kindFilter = nil }
            ForEach([(Song.kindSong, L.t("曲", "Songs")), (Song.kindMusicVideo, L.t("MV", "Music videos")),
                     (Song.kindUserVideo, L.t("その他の動画（歌ってみた・ライブなど）", "Other videos (covers, live…)"))], id: \.0) { kind, title in
                Chip(title: title, isOn: model.kindFilter == kind) { model.kindFilter = model.kindFilter == kind ? nil : kind }
            }
        }
        .padding(EdgeInsets(top: 0, leading: 12, bottom: 12, trailing: 12))
    }
}
#endif
