#if os(macOS)
import SwiftUI
import WaveCore

/// マイライブラリ（左の欄）。
struct LibraryPane: View {
    @State private var model = MainWindowModel.shared

    var body: some View {
        let lib = AppServices.library!
        let account = AppServices.account!
        let settings = AppServices.settings!
        let entries = model.libraryEntries()
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: "books.vertical").font(.system(size: 16))
                Text(L.t("マイライブラリ", "Your Library")).font(.body.weight(.semibold))
                Spacer()
                Button { Dialogs.shared.createPlaylist() } label: { Image(systemName: "plus") }
                    .buttonStyle(IconButtonStyle())
                    .disabled(!account.isLoggedIn)
                    .help(L.t("プレイリストを作成", "Create playlist"))
            }
            .padding(EdgeInsets(top: 12, leading: 16, bottom: 4, trailing: 8))
            HStack(spacing: 6) {
                ForEach([("playlist", L.t("プレイリスト", "Playlists")), ("artist", L.t("アーティスト", "Artists")), ("album", L.t("アルバム", "Albums"))], id: \.0) { kind, title in
                    Chip(title: title, isOn: model.libraryKind == kind) { model.libraryKind = model.libraryKind == kind ? nil : kind }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            HStack(spacing: 4) {
                TextField(L.t("マイライブラリを検索", "Search in Your Library"), text: $model.libraryFilter).textFieldStyle(.roundedBorder)
                Menu {
                    Picker(selection: Binding(get: { settings.librarySort }, set: {
                        settings.librarySort = $0
                        settings.save()
                        model.libraryFilter = model.libraryFilter  // 並べ直す
                    })) {
                        Text(L.t("最近", "Recents")).tag("recent")
                        Text(L.t("最近追加した順", "Recently added")).tag("added")
                        Text(L.t("アルファベット順", "Alphabetical")).tag("alpha")
                    } label: { EmptyView() }
                        .pickerStyle(.inline)
                } label: {
                    Text(sortLabel(settings.librarySort)).font(.callout)
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                // リスト・グリッドの切り替え（Android と同じ）。押すと切り替わる先の絵（Spotify と同じ）
                Button {
                    settings.libraryGrid.toggle()
                    settings.save()
                    model.libraryFilter = model.libraryFilter
                } label: { Image(systemName: settings.libraryGrid ? "list.bullet" : "square.grid.2x2") }
                    .buttonStyle(IconButtonStyle())
                    .help(settings.libraryGrid ? L.t("リストで表示", "List view") : L.t("グリッドで表示", "Grid view"))
            }
            .padding(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 8))
            ZStack(alignment: .top) {
                if !account.isLoggedIn {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L.t("ログインすると、YouTube Music のプレイリスト・アーティスト・アルバムがここに並びます。",
                                 "Log in to see your YouTube Music playlists, artists and albums here."))
                            .fixedSize(horizontal: false, vertical: true)
                        Button(L.t("ログイン", "Log in")) { LoginWindowController.show() }.buttonStyle(.borderedProminent)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else if settings.libraryGrid {
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96, maximum: 110), spacing: 8, alignment: .top)], spacing: 8) {
                            ForEach(entries) { entry in
                                VStack(alignment: .leading, spacing: 4) {
                                    entryCover(entry, size: 96)
                                    Text(entry.title).font(.caption).lineLimit(1)
                                }
                                .frame(width: 96)
                                .contentShape(Rectangle())
                                .onTapGesture { open(entry) }
                                .contextMenu { entryMenu(entry) }
                            }
                        }
                        .padding(EdgeInsets(top: 4, leading: 10, bottom: 8, trailing: 4))
                    }
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(entries) { entry in
                                LibraryRow(entry: entry) { open(entry) }
                                    .contextMenu { entryMenu(entry) }
                            }
                        }
                        .padding(EdgeInsets(top: 0, leading: 4, bottom: 8, trailing: 4))
                    }
                }
                if lib.isLoading { ProgressView().progressViewStyle(.linear).padding(.horizontal, 12) }
            }
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private func sortLabel(_ sort: String) -> String {
        switch sort {
        case "alpha": L.t("アルファベット順", "Alphabetical")
        case "added": L.t("最近追加した順", "Recently added")
        default: L.t("最近", "Recents")
        }
    }

    @ViewBuilder
    private func entryCover(_ entry: LibraryEntry, size: CGFloat) -> some View {
        if entry.isLiked {
            LikedCover(size: size)
        } else {
            Cover(url: Ui.small(entry.thumbnailUrl), size: size, round: entry.isArtist)
        }
    }

    private func open(_ entry: LibraryEntry) {
        if entry.isLiked {
            AppServices.nav.likedSongs()
        } else if let item = entry.item {
            AppServices.nav.open(item)
        }
    }

    @ViewBuilder
    private func entryMenu(_ entry: LibraryEntry) -> some View {
        if let item = entry.item {
            Button(L.t("再生", "Play"), systemImage: "play.fill") { AppServices.nav.play(item) }
        } else if entry.isLiked {
            Button(L.t("再生", "Play"), systemImage: "play.fill") {
                AppServices.nav.play(.playlist(PlaylistSummary(playlistId: MusicRepository.likedSongs, title: entry.title, subtitle: nil, thumbnailUrl: nil)))
            }
        }
    }
}

private struct LibraryRow: View {
    var entry: LibraryEntry
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: 12) {
            if entry.isLiked {
                LikedCover(size: 48)
            } else {
                Cover(url: Ui.small(entry.thumbnailUrl), size: 48, round: entry.isArtist)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title).lineLimit(1)
                if let subtitle = entry.subtitle { Text(subtitle).subtle() }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8)
        .frame(minHeight: 60)
        .background(RoundedRectangle(cornerRadius: 6).fill(hover ? Color.primary.opacity(0.07) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(perform: action)
    }
}
#endif
