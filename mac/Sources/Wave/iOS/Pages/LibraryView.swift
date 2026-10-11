#if os(iOS)
import SwiftUI
import WaveCore

/// ライブラリの1行（プレイリスト・アーティスト・アルバム。先頭の「高く評価した曲」は item が nil）。
private struct LibraryViewEntry: Identifiable, Hashable {
    var title: String
    var subtitle: String?
    var thumbnailUrl: String?
    var item: HomeItem?
    var isLiked = false
    var id: String { item?.key ?? "liked" }
    var isArtist: Bool { item?.isArtist ?? false }
}

/// ライブラリのタブ（Android の LibraryTabScreen と同じ並び: アカウントの絵と「ライブラリ」・＋、種類のチップ、並べ替えと一覧／格子の切り替え、
/// 先頭に「高く評価した曲」、その下にプレイリスト・アーティスト・アルバム）。絞り込みは iOS の検索欄、再読み込みは引っぱって。
struct LibraryView: View {
    @State private var kind: String?
    @State private var filter = ""
    @State private var sort = AppServices.settings.librarySort
    @State private var grid = AppServices.settings.libraryGrid
    @State private var account = AppServices.account!

    var body: some View {
        let lib = AppServices.library!
        let entries = entries(lib)
        Group {
            if !account.isLoggedIn {
                ContentUnavailableView {
                    Label(L.t("ライブラリ", "Library"), systemImage: "books.vertical")
                } description: {
                    Text(L.t("ログインすると、YouTube Music のプレイリスト・アーティスト・アルバムがここに並びます。",
                             "Log in to see your YouTube Music playlists, artists and albums here."))
                } actions: {
                    Button(L.t("ログイン", "Log in")) { LoginGate.shared.skipped = false }.buttonStyle(.borderedProminent)
                }
            } else if grid {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        controls
                        status(lib)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12, alignment: .top), count: 3), spacing: 16) {
                            ForEach(entries) { entry in
                                Button { open(entry) } label: { LibraryViewGridCell(entry: entry) }
                                    .buttonStyle(.plain)
                                    .contextMenu { menu(entry) }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 16)
                    }
                }
                .refreshable { await reload() }
            } else {
                List {
                    controls.listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
                    status(lib).listRowSeparator(.hidden)
                    ForEach(entries) { entry in
                        Button { open(entry) } label: { LibraryViewRow(entry: entry) }
                            .tint(.primary)
                            .contextMenu { menu(entry) }
                    }
                }
                .listStyle(.plain)
                .refreshable { await reload() }
            }
        }
        .navigationTitle(L.t("ライブラリ", "Library"))
        .searchable(text: $filter, prompt: L.t("ライブラリを検索", "Search in Your Library"))
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { AppServices.nav.settings() } label: { LibraryViewAvatar(url: account.photoUrl) }
                    .accessibilityLabel(L.t("設定", "Settings"))
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { Dialogs.shared.createPlaylist() } label: { Image(systemName: "plus") }
                    .disabled(!account.isLoggedIn)
                    .accessibilityLabel(L.t("プレイリストを作成", "Create playlist"))
            }
        }
    }

    // 種類のチップ（同じものをもう一度押すと外す）・並べ替え・一覧と格子の切り替え
    private var controls: some View {
        VStack(alignment: .leading, spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach([("playlist", L.t("プレイリスト", "Playlists")), ("artist", L.t("アーティスト", "Artists")), ("album", L.t("アルバム", "Albums"))], id: \.0) { k, title in
                        LibraryViewChip(title: title, isOn: kind == k) { kind = kind == k ? nil : k }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
            HStack {
                Menu {
                    Picker(selection: Binding(get: { sort }, set: { value in
                        sort = value
                        AppServices.settings.librarySort = value
                        AppServices.settings.save()
                    })) {
                        Text(L.t("最近", "Recents")).tag("recent")
                        Text(L.t("最近追加した順", "Recently added")).tag("added")
                        Text(L.t("アルファベット順", "Alphabetical")).tag("alpha")
                    } label: { EmptyView() }
                } label: {
                    Label(sortLabel(sort), systemImage: "arrow.up.arrow.down").font(.subheadline.weight(.medium))
                }
                Spacer()
                // 押すと切り替わる先の絵（Android・Spotify と同じ）
                Button {
                    grid.toggle()
                    AppServices.settings.libraryGrid = grid
                    AppServices.settings.save()
                } label: { Image(systemName: grid ? "list.bullet" : "square.grid.2x2").frame(width: 36, height: 32) }
                    .accessibilityLabel(grid ? L.t("リストで表示", "List view") : L.t("グリッドで表示", "Grid view"))
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 4)
        }
    }

    @ViewBuilder
    private func status(_ lib: LibraryState) -> some View {
        if lib.isLoading && lib.playlists.isEmpty && lib.artists.isEmpty && lib.albums.isEmpty {
            ProgressView().frame(maxWidth: .infinity).padding()
        } else if let failure = lib.failure {
            Text(L.t("ライブラリを読み込めませんでした（\(failure)）", "Couldn't load your library (\(failure))")).subtle().padding(.horizontal, 16)
        }
    }

    private func sortLabel(_ sort: String) -> String {
        switch sort {
        case "alpha": L.t("アルファベット順", "Alphabetical")
        case "added": L.t("最近追加した順", "Recently added")
        default: L.t("最近", "Recents")
        }
    }

    /// 出す行（Mac の libraryEntries と同じ: 種類と語で絞り、並べ、先頭に「高く評価した曲」）。
    private func entries(_ lib: LibraryState) -> [LibraryViewEntry] {
        let filter = self.filter.trimmingCharacters(in: .whitespaces)
        var entries: [LibraryViewEntry] = []
        if kind == nil || kind == "playlist" {
            entries += lib.playlists.map {
                LibraryViewEntry(title: $0.title, subtitle: L.t("プレイリスト", "Playlist") + (($0.subtitle ?? "").isEmpty ? "" : " • " + $0.subtitle!),
                                 thumbnailUrl: $0.thumbnailUrl, item: .playlist($0))
            }
        }
        if kind == nil || kind == "artist" {
            entries += lib.artists.map { LibraryViewEntry(title: $0.name, subtitle: L.t("アーティスト", "Artist"), thumbnailUrl: $0.thumbnailUrl, item: .artist($0)) }
        }
        if kind == nil || kind == "album" {
            entries += lib.albums.map {
                LibraryViewEntry(title: $0.title, subtitle: L.t("アルバム", "Album") + (($0.subtitle ?? "").isEmpty ? "" : " • " + $0.subtitle!),
                                 thumbnailUrl: $0.thumbnailUrl, item: .album($0))
            }
        }
        if !filter.isEmpty {
            entries = entries.filter { $0.title.localizedCaseInsensitiveContains(filter) || ($0.subtitle ?? "").localizedCaseInsensitiveContains(filter) }
        }
        if sort == "alpha" {
            entries.sort { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        } else if sort == "recent" {
            // 最近再生したものを先に、残りは追加した順のまま（Android と同じ）
            var rank: [String: Int] = [:]
            for (i, key) in lib.recentKeys.enumerated() where rank[key] == nil { rank[key] = i }
            entries = entries.enumerated().sorted { a, b in
                let ra = a.element.item.flatMap(LibraryState.keyOf).flatMap { rank[$0] } ?? Int.max
                let rb = b.element.item.flatMap(LibraryState.keyOf).flatMap { rank[$0] } ?? Int.max
                return ra != rb ? ra < rb : a.offset < b.offset
            }.map(\.element)
        }
        // 高く評価した曲はいつも先頭（Spotify の「お気に入りの曲」と同じ）
        let likedTitle = L.t("高く評価した曲", "Liked Songs")
        if (kind == nil || kind == "playlist") && (filter.isEmpty || likedTitle.localizedCaseInsensitiveContains(filter)) {
            entries.insert(LibraryViewEntry(title: likedTitle, subtitle: L.t("プレイリスト • 自動", "Playlist • Auto"), thumbnailUrl: lib.likedThumbnail, isLiked: true), at: 0)
        }
        return entries
    }

    private func open(_ entry: LibraryViewEntry) {
        if entry.isLiked {
            AppServices.nav.likedSongs()
        } else if let item = entry.item {
            AppServices.nav.open(item)
        }
    }

    @ViewBuilder
    private func menu(_ entry: LibraryViewEntry) -> some View {
        let item = entry.item ?? .playlist(PlaylistSummary(playlistId: MusicRepository.likedSongs, title: entry.title, subtitle: nil, thumbnailUrl: nil))
        Button(L.t("再生", "Play"), systemImage: "play.fill") { AppServices.nav.play(item) }
    }

    /// 引っぱって読み直す（読み終わるまでくるくるを出す。長くても 30 秒で戻す）。
    private func reload() async {
        let lib = AppServices.library!
        lib.refresh()
        for _ in 0..<150 {
            if !lib.isLoading { break }
            try? await Task.sleep(for: .milliseconds(200))
        }
    }
}

private struct LibraryViewAvatar: View {
    var url: String?

    var body: some View {
        if let url {
            Cover(url: Ui.small(url), size: 30, round: true)
        } else {
            Image(systemName: "person.crop.circle").font(.system(size: 24))
        }
    }
}

private struct LibraryViewChip: View {
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

private struct LibraryViewRow: View {
    var entry: LibraryViewEntry

    var body: some View {
        HStack(spacing: 12) {
            if entry.isLiked {
                LikedCover(size: 56)
            } else {
                Cover(url: Ui.small(entry.thumbnailUrl), size: 56, round: entry.isArtist)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title).lineLimit(1)
                if let subtitle = entry.subtitle { Text(subtitle).subtle() }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }
}

private struct LibraryViewGridCell: View {
    var entry: LibraryViewEntry

    var body: some View {
        VStack(alignment: entry.isArtist ? .center : .leading, spacing: 4) {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    GeometryReader { geo in
                        if entry.isLiked {
                            LikedCover(size: geo.size.width)
                        } else {
                            Cover(url: Ui.img(entry.thumbnailUrl), size: geo.size.width, round: entry.isArtist)
                        }
                    }
                }
            Text(entry.title).font(.footnote).lineLimit(2).multilineTextAlignment(entry.isArtist ? .center : .leading)
            if let subtitle = entry.subtitle { Text(subtitle).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
        }
        .frame(maxWidth: .infinity, alignment: entry.isArtist ? .center : .leading)
        .contentShape(Rectangle())
    }
}

/// 再生履歴（Android の HistoryScreen と同じ: 日ごとの見出しの下に曲。押すとその曲を再生）。
struct HistoryView: View {
    @State private var sections: [(title: String, songs: [Song])] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        List {
            if loading || error != nil {
                LoadState(loading: loading, failure: error) { Task { await load() } }.listRowSeparator(.hidden)
            }
            ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                Section(section.title) {
                    ForEach(Array(section.songs.enumerated()), id: \.offset) { _, song in
                        SongRow(song: song) { AppServices.player.playSong(song) }
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(L.t("再生履歴", "History"))
        .refreshable { await load() }
        .task { if sections.isEmpty { await load() } }
    }

    private func load() async {
        error = nil
        let result = (try? await AppServices.music.history()) ?? .failed(.network)
        loading = false
        guard let page = result.value else {
            error = result.failure == .notLoggedIn ? L.t("再生履歴を見るにはログインしてください", "Log in to see your listening history")
                : L.t("再生履歴を読み込めませんでした", "Couldn't load your listening history")
            return
        }
        sections = page.sections.map { ($0.title ?? "", $0.songs) }
        if sections.allSatisfy({ $0.songs.isEmpty }) { error = L.t("再生履歴はありません", "No listening history") }
    }
}
#endif
