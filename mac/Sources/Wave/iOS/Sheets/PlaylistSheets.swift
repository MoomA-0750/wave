#if os(iOS)
import SwiftUI
import WaveCore

/// プレイリストに追加する画面（Android・Mac と同じ）。先頭は「新しいプレイリスト」と「高く評価した曲」（押すと評価を外す・付ける。今の曲のときだけ）。
/// プレイリストを押しても閉じず、続けてほかのプレイリストにも足せる（閉じるのは「完了」）。入っているプレイリストには右に✓を出し、押すと外す。
struct AddToPlaylistSheet: View {
    var song: Song
    @Environment(\.dismiss) private var dismiss
    @State private var status: [String: PlaylistMembership.Status] = [:]
    @State private var busy: Set<String> = []

    var body: some View {
        let player = AppServices.player!
        let isCurrent = player.current?.videoId == song.videoId
        let liked = isCurrent ? player.like == .like : AppServices.library.isLiked(song.videoId)
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        Cover(url: Ui.small(song.thumbnailUrl), size: 44, radius: 4)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(song.title).lineLimit(1)
                            Text(song.artistsText).subtle()
                        }
                    }
                }
                Section {
                    Button {
                        dismiss()
                        // 閉じ終わってから作る画面を出す（シートを続けて出すと出ないことがある）
                        let song = self.song
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { Dialogs.shared.createPlaylist(with: song) }
                    } label: {
                        PlaylistSheetsRow(title: L.t("新しいプレイリスト", "New playlist"), icon: "plus", accent: true) { EmptyView() }
                    }
                    Button {
                        dismiss()
                        // 評価済みなら外す（今の曲のときだけ評価を変えられる。Mac と同じ）
                        if isCurrent { player.toggleLike() }
                    } label: {
                        PlaylistSheetsRow(title: L.t("高く評価した曲", "Liked Songs"), icon: "heart") {
                            Image(systemName: liked ? "checkmark.circle.fill" : "plus.circle").foregroundStyle(Color.accentColor)
                        }
                    }
                    .disabled(!isCurrent)
                }
                Section(L.t("プレイリスト", "Playlists")) {
                    ForEach(AppServices.library.editable, id: \.playlistId) { playlist in
                        Button { toggle(playlist) } label: {
                            PlaylistSheetsRow(title: playlist.title, icon: "music.note.list") {
                                let s = status[playlist.playlistId] ?? .checking
                                if busy.contains(playlist.playlistId) || s == .checking {
                                    ProgressView()
                                } else if s == .isIn {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor)
                                } else if s == .unknown {
                                    Image(systemName: "questionmark.circle").foregroundStyle(.secondary)
                                        .accessibilityLabel(L.t("入っているか確かめられませんでした", "Couldn't check"))
                                } else {
                                    Image(systemName: "plus.circle").foregroundStyle(.secondary)
                                }
                            }
                        }
                        .task {
                            let s = await AppServices.library.membership.check(playlist.playlistId, song.videoId)
                            status[playlist.playlistId] = s
                        }
                    }
                }
            }
            .tint(.primary)
            .navigationTitle(L.t("プレイリストに追加", "Add to playlist"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L.t("完了", "Done")) { dismiss() }.tint(Color.accentColor) }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func toggle(_ playlist: PlaylistSummary) {
        let id = playlist.playlistId
        let s = status[id] ?? .checking
        guard !busy.contains(id), s != .checking else { return }
        busy.insert(id)
        Task {
            if s == .isIn {
                if await SongActions.remove(playlist, song) { status[id] = .notIn }
            } else {
                let result = await SongActions.add(playlist, song)
                if result == .added || result == .alreadyInPlaylist { status[id] = .isIn }
            }
            busy.remove(id)
        }
    }
}

private struct PlaylistSheetsRow<Trailing: View>: View {
    var title: String
    var icon: String
    var accent = false
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).frame(width: 24).foregroundStyle(accent ? Color.accentColor : Color.primary)
            Text(title).lineLimit(1)
            Spacer()
            trailing().frame(width: 24, height: 24)
        }
        .contentShape(Rectangle())
    }
}

/// プレイリストを作成する画面（名前を入れて作る。曲から開いたときは、作ったあとにその曲を入れる）。
struct CreatePlaylistSheet: View {
    var song: Song?
    @State private var name: String
    @FocusState private var focused: Bool
    @Environment(\.dismiss) private var dismiss

    init(song: Song?) {
        self.song = song
        _name = State(initialValue: song == nil ? "" : L.t("マイプレイリスト", "My playlist"))
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespaces) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L.t("プレイリスト名", "Playlist name"), text: $name)
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit(create)
                } footer: {
                    if let song { Text(L.t("作成したあと、「\(song.title)」を入れます。", "“\(song.title)” will be added after it's created.")) }
                }
            }
            .navigationTitle(L.t("プレイリストを作成", "Create playlist"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L.t("キャンセル", "Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(L.t("作成", "Create"), action: create).disabled(trimmed.isEmpty) }
            }
        }
        .presentationDetents([.height(260), .medium])
        .onAppear { focused = true }
    }

    private func create() {
        let title = trimmed
        guard !title.isEmpty else { return }
        dismiss()
        let song = self.song
        Task { await Dialogs.shared.create(title, song: song) }
    }
}
#endif
