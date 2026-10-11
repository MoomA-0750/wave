#if os(iOS)
import SwiftUI
import WaveCore

/// 一覧の1行（同じ曲が2回あるプレイリストでも見分けられるよう、元の並びの番号と項目の ID を持つ）。
private struct CollectionViewRow: Identifiable, Hashable {
    var number: Int
    var song: Song
    var setVideoId: String?
    var id: String { "\(number):\(song.videoId):\(setVideoId ?? "")" }
}

/// プレイリスト・アルバムのページの状態（Mac の CollectionModel と同じ読み方・並べ方）。
@MainActor @Observable
private final class CollectionViewModel {
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
    var all: [CollectionViewRow] = []
    var filter = ""
    var kindFilter: String?
    var sort: SortKey = .custom
    var descending = false
    var sortable = false
    var editable = false
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

    /// 引っぱって読み直す（読み終わるまで待つ）。
    func reload() async {
        load()
        await task?.value
    }

    private func color() async {
        if let c = await Artwork.dominantColor(thumbnailUrl) { gradient = c }
    }

    private func loadAlbum(_ mine: Int) async {
        guard let album = (try? await AppServices.music.album(request.id))?.value, mine == generation else {
            if mine == generation { error = L.t("アルバムを読み込めませんでした", "Couldn't load the album") }
            return
        }
        title = album.title
        let changedArt = thumbnailUrl != album.thumbnailUrl
        thumbnailUrl = album.thumbnailUrl ?? thumbnailUrl
        if changedArt { await color() }
        let total = album.songs.reduce(0) { $0 + ($1.durationSeconds ?? 0) }
        detail = [album.subtitle, "\(L.songs(album.songs.count)), \(L.length(total))"].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
        // アーティストは題名の下に別の行で出す（押すとアーティストのページ。Android と同じ）
        albumArtist = album.artists.first { $0.browseId != nil }
        artistText = album.artists.isEmpty ? nil : album.artists.map(\.name).joined(separator: ", ")
        all = album.songs.enumerated().map { CollectionViewRow(number: $0.offset + 1, song: $0.element) }
    }

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
                        rows.append(CollectionViewRow(number: i + 1, song: page.songs[i], setVideoId: i < page.setVideoIds.count ? page.setVideoIds[i] : nil))
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
        detail = page.subtitle ?? request.subtitle ?? "\(L.songs(all.count)), \(L.length(total))"
    }

    /// 高く評価した曲のうち、そのアーティストのもの（ライブラリで覚えた一覧から絞る）。
    private func loadLikedByArtist(_ artist: ArtistFilter, _ mine: Int) async {
        let (songs, complete) = await AppServices.library.likedSongs()
        guard mine == generation else { return }
        all = LibraryState.byArtist(songs, artist.browseId, artist.name).enumerated().map { CollectionViewRow(number: $0.offset + 1, song: $0.element) }
        let total = all.reduce(0) { $0 + ($1.song.durationSeconds ?? 0) }
        detail = "\(artist.name) • \(L.songs(all.count))\(complete ? "" : "+"), \(L.length(total))"
    }

    /// 絞り込みと並べ替えを当てた、出す行。再生は出ている並びで行う。
    var rows: [CollectionViewRow] {
        let terms = filter.split(whereSeparator: { $0 == " " || $0 == "\u{3000}" }).map(String.init)
        var view = all.filter { row in
            let s = row.song
            let folded = CollectionViewModel.fold([s.title, s.artistsText, s.album?.title ?? ""].joined(separator: " "))
            return terms.allSatisfy { folded.contains(CollectionViewModel.fold($0)) } && (kindFilter == nil || s.kind == kindFilter)
        }
        func ordered<T: Comparable>(_ key: (CollectionViewRow) -> T) {
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
        case .custom: if descending { view.reverse() }
        case .added, .published: break
        }
        return view
    }

    /// 大文字・小文字、全角・半角、ひらがな・カタカナを区別しない比べ方の形（Mac・Windows と同じ）。
    static func fold(_ text: String) -> String {
        let width = text.applyingTransform(.fullwidthToHalfwidth, reverse: false) ?? text
        let kana = width.applyingTransform(.hiraganaToKatakana, reverse: false) ?? width
        return kana.folding(options: [.caseInsensitive, .widthInsensitive], locale: nil)
    }

    var isPlayingHere: Bool { AppServices.player.sourceId == request.id && AppServices.player.current != nil }

    func play(rows: [CollectionViewRow], start: Int = 0, shuffle: Bool? = nil) {
        AppServices.player.playList(rows.map(\.song), start: start, source: request.id, shuffleOverride: shuffle)
    }

    func remove(_ row: CollectionViewRow) {
        guard let setId = row.setVideoId else { return }
        AppServices.library.membership.invalidate(request.id)
        Task {
            if (try? await AppServices.music.removeFromPlaylist(request.id, videoId: row.song.videoId, setVideoId: setId)) == true {
                all.removeAll { $0.id == row.id }
                AppServices.library.membership.noteRemoved(request.id, row.song.videoId)
                AppServices.player.message = L.t("プレイリストから削除しました", "Removed from playlist")
            } else {
                AppServices.player.message = L.t("削除できませんでした", "Couldn't remove it")
            }
        }
    }
}

/// プレイリスト・アルバム・高く評価した曲のページ（Android の PlaylistScreen・AlbumScreen と同じ並び: 上にジャケットと名前・補足、
/// 再生・シャッフル再生、曲の種類のチップ、曲の一覧）。一覧の中の絞り込みは iOS の検索欄、並べ替えとその他は右上のメニュー。
struct CollectionView: View {
    var request: CollectionRequest
    @State private var model: CollectionViewModel

    init(request: CollectionRequest) {
        self.request = request
        _model = State(initialValue: CollectionViewModel(request: request))
    }

    var body: some View {
        let player = AppServices.player!
        let rows = model.rows
        List {
            header(player, rows).listRowSeparator(.hidden).listRowBackground(Color.clear)
            kindChips.listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
            if model.loading && model.all.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding().listRowSeparator(.hidden)
            } else if let error = model.error {
                LoadState(loading: false, failure: error) { model.load() }.listRowSeparator(.hidden)
            } else if !model.all.isEmpty && rows.isEmpty {
                Text(L.t("一致する曲はありません", "No matching songs")).subtle().frame(maxWidth: .infinity).padding().listRowSeparator(.hidden)
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                let remove: (() -> Void)? = model.editable && row.setVideoId != nil ? { model.remove(row) } : nil
                SongRow(song: row.song, number: request.isAlbum ? row.number : nil, removeFromPlaylist: remove) { model.play(rows: rows, start: i) }
                    .swipeActions(edge: .trailing) {
                        if let remove {
                            Button(L.t("削除", "Remove"), systemImage: "trash", role: .destructive, action: remove)
                        }
                    }
                    .swipeActions(edge: .leading) {
                        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(row.song) }.tint(.indigo)
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(alignment: .top) {
            LinearGradient(colors: [(model.gradient ?? .clear).opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 460)
                .ignoresSafeArea()
        }
        .navigationTitle(model.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $model.filter, placement: .navigationBarDrawer(displayMode: .automatic), prompt: L.t("この一覧の中を検索", "Search in this list"))
        .toolbar { ToolbarItem(placement: .topBarTrailing) { moreMenu(player) } }
        .refreshable { await model.reload() }
        .onAppear { if model.all.isEmpty && model.error == nil { model.load() } }
    }

    private func header(_ player: PlayerService, _ rows: [CollectionViewRow]) -> some View {
        VStack(spacing: 8) {
            Group {
                if model.isLiked {
                    LikedCover(size: 240, radius: 10)
                } else {
                    Cover(url: Ui.large(model.thumbnailUrl), size: 240, radius: 10)
                }
            }
            .shadow(color: .black.opacity(0.25), radius: 16, y: 8)
            .padding(.bottom, 8)
            Text(model.title ?? "").font(.title2.weight(.bold)).multilineTextAlignment(.center).lineLimit(3)
            if let artist = model.artistText {
                Button(artist) { if let id = model.albumArtist?.browseId { AppServices.nav.artist(id) } }
                    .font(.headline)
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.accentColor)
                    .disabled(model.albumArtist?.browseId == nil)
            }
            if let detail = model.detail { Text(detail).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center).lineLimit(3) }
            // 再生・シャッフル再生（iOS のミュージックと同じ大きな2つのボタン）。この一覧を流していれば再生は一時停止・再開になる
            HStack(spacing: 12) {
                CollectionViewBigButton(title: model.isPlayingHere && player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"),
                                        icon: model.isPlayingHere && player.isPlaying ? "pause.fill" : "play.fill") {
                    if model.isPlayingHere { player.togglePlay() } else { model.play(rows: rows) }
                }
                CollectionViewBigButton(title: L.t("シャッフル", "Shuffle"), icon: "shuffle") { model.play(rows: rows, shuffle: true) }
            }
            .disabled(rows.isEmpty)
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }

    // 曲の種類で絞る（動画や歌ってみたと、ふつうの曲を分ける。Android と同じ）。同じものをもう一度押すと外す
    private var kindChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                CollectionViewChip(title: L.t("すべて", "All"), isOn: model.kindFilter == nil) { model.kindFilter = nil }
                ForEach([(Song.kindSong, L.t("曲", "Songs")), (Song.kindMusicVideo, L.t("MV", "Music videos")),
                         (Song.kindUserVideo, L.t("その他の動画", "Other videos"))], id: \.0) { kind, title in
                    CollectionViewChip(title: title, isOn: model.kindFilter == kind) { model.kindFilter = model.kindFilter == kind ? nil : kind }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
    }

    private func moreMenu(_ player: PlayerService) -> some View {
        Menu {
            Section(L.t("並べ替え", "Sort by")) {
                ForEach(model.sortChoices, id: \.self) { key in
                    Button {
                        model.choose(key)
                    } label: {
                        if key == model.sort {
                            Label(CollectionViewModel.label(key), systemImage: key == .custom ? "checkmark" : model.descending ? "arrow.down" : "arrow.up")
                        } else {
                            Text(CollectionViewModel.label(key))
                        }
                    }
                }
            }
            Section {
                Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.queueAll(model.rows.map(\.song), next: true) }
                Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.queueAll(model.rows.map(\.song), next: false) }
            }
            .disabled(model.rows.isEmpty)
        } label: {
            Image(systemName: "ellipsis")
        }
        .accessibilityLabel(L.t("その他", "More"))
    }
}

private struct CollectionViewBigButton: View {
    var title: String
    var icon: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.accentColor)
        .glassCard(14, interactive: true)
    }
}

private struct CollectionViewChip: View {
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
#endif
