#if os(macOS)
import AppKit
import SwiftUI
import WaveCore

/// 曲の右クリック・「…」のメニュー（Spotify と同じ並び。Windows の SongMenu と同じ）。
/// removeFromPlaylist を渡すと「このプレイリストから削除」を出す。
struct SongMenuItems: View {
    var song: Song
    var removeFromPlaylist: (() -> Void)?
    var queueIndex: Int?

    var body: some View {
        let player = AppServices.player!
        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(song) }
        Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.addToQueue(song) }
        if let queueIndex {
            Button(L.t("キューから削除", "Remove from queue"), systemImage: "minus.circle") { player.removeAt(queueIndex) }
        }
        // RSS のエピソードは YouTube の曲ではない（プレイリスト・ラジオ・共有のリンクが無い。ADR-0012）
        let external = Feed.isExternal(song.videoId)
        Divider()
        // プレイリストへの追加はログインしているときだけ出す
        if !external && AppServices.client.isLoggedIn {
            Menu(L.t("プレイリストに追加", "Add to playlist"), systemImage: "plus") {
                Button(L.t("新しいプレイリスト", "New playlist"), systemImage: "plus") { Dialogs.shared.createPlaylist(with: song) }
                let editable = AppServices.library.editable
                if !editable.isEmpty { Divider() }
                ForEach(editable, id: \.playlistId) { playlist in
                    Button(playlist.title) { Task { await SongActions.add(playlist, song) } }
                }
            }
        }
        // 高く評価した曲に足す・外す（Spotify の「お気に入りに追加」。Android・Windows と同じ）
        if !external && AppServices.client.isLoggedIn {
            let liked = player.isLiked(song)
            Button(liked ? L.t("高く評価した曲から削除", "Remove from Liked Songs") : L.t("高く評価した曲に追加", "Add to Liked Songs"),
                   systemImage: liked ? "checkmark.circle.fill" : "plus.circle") { player.setLiked(song, !liked) }
        }
        if let removeFromPlaylist {
            Button(L.t("このプレイリストから削除", "Remove from this playlist"), systemImage: "trash", action: removeFromPlaylist)
        }
        Divider()
        if !external {
            Button(L.t("曲のラジオを再生", "Go to song radio"), systemImage: "dot.radiowaves.left.and.right") { player.playSong(song) }
        }
        if let albumId = song.album?.browseId {
            Button(L.t("アルバムへ移動", "Go to album"), systemImage: "square.stack") { AppServices.nav.album(albumId) }
        }
        ForEach(song.artists.filter { $0.browseId != nil }, id: \.self) { artist in
            Button(song.artists.count > 1 ? L.t("アーティストへ移動: \(artist.name)", "Go to artist: \(artist.name)") : L.t("アーティストへ移動", "Go to artist"),
                   systemImage: "person") { AppServices.nav.artist(artist.browseId!) }
        }
        // 曲の画面（iPhone のメニューと同じ。ライブ配信には曲の画面が無い）
        if !external && !song.isLive {
            Button(L.t("曲の画面を開く", "Open song"), systemImage: "music.note") { AppServices.nav.song(song) }
        }
        if !external {
            Divider()
            Menu(L.t("共有", "Share"), systemImage: "square.and.arrow.up") {
                Button(L.t("曲のリンクをコピー", "Copy song link"), systemImage: "link") { SongActions.copyLink(song) }
                ShareLink(L.t("ほかのアプリで共有…", "Share with…"), item: SongActions.url(song), subject: Text(song.title),
                          message: Text(SongActions.shareText(song)))
            }
        }
    }
}

/// プレイリストを作成する画面。
struct CreatePlaylistSheet: View {
    var song: Song?
    @State private var name: String
    @Environment(\.dismiss) private var dismiss

    init(song: Song?) {
        self.song = song
        _name = State(initialValue: song == nil ? "" : L.t("マイプレイリスト", "My playlist"))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L.t("プレイリストを作成", "Create playlist")).font(.title2.weight(.semibold))
            TextField(L.t("プレイリスト名", "Playlist name"), text: $name).textFieldStyle(.roundedBorder).frame(width: 340)
                .onSubmit(create)
            HStack {
                Spacer()
                Button(L.t("キャンセル", "Cancel")) { dismiss() }.keyboardShortcut(.cancelAction)
                Button(L.t("作成", "Create"), action: create).keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
    }

    private func create() {
        let title = name.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { return }
        dismiss()
        let song = self.song
        Task { await Dialogs.shared.create(title, song: song) }
    }
}

/// プレイリストに追加する画面（Android・Spotify と同じ）。先頭は「高く評価した曲」で、押すと評価を外す（評価済みのライクボタンから開く）。
/// プレイリストを押しても閉じず、続けてほかのプレイリストにも足せる（閉じるのは「完了」）。入っているプレイリストには右に✓を出し、押すと外す。
struct AddToPlaylistSheet: View {
    var song: Song
    @Environment(\.dismiss) private var dismiss
    @State private var model: AddToPlaylistModel

    init(song: Song) {
        self.song = song
        _model = State(initialValue: AddToPlaylistModel(song: song))
    }

    var body: some View {
        let player = AppServices.player!
        let liked = player.current?.videoId == song.videoId ? player.like == .like : AppServices.library.isLiked(song.videoId)
        VStack(alignment: .leading, spacing: 12) {
            Text(L.t("プレイリストに追加", "Add to playlist")).font(.title2.weight(.semibold))
            ScrollView {
                VStack(spacing: 2) {
                    row(L.t("新しいプレイリスト", "New playlist"), icon: "plus", accentIcon: true) {
                        dismiss()
                        Dialogs.shared.createPlaylist(with: song)
                    } trailing: { EmptyView() }
                    row(L.t("高く評価した曲", "Liked Songs"), icon: "heart") {
                        dismiss()
                        // 評価済みなら外す（今の曲のときだけ評価を変えられる）
                        if player.current?.videoId == song.videoId { player.toggleLike() }
                    } trailing: {
                        Image(systemName: liked ? "checkmark.circle.fill" : "plus.circle").foregroundStyle(Color.accentColor)
                    }
                    ForEach(AppServices.library.editable, id: \.playlistId) { playlist in
                        row(playlist.title, icon: "music.note.list") {
                            model.toggle(playlist)
                        } trailing: {
                            let s = model.state(playlist)
                            if s == .checking {
                                ProgressView().controlSize(.small)
                            } else if s == .isIn {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor)
                            } else if s == .unknown {
                                Image(systemName: "questionmark.circle").foregroundStyle(.secondary).help(L.t("入っているか確かめられませんでした", "Couldn't check"))
                            }
                        }
                        .task { await model.check(playlist) }
                    }
                }
            }
            .frame(width: 380, height: min(480, CGFloat(AppServices.library.editable.count + 2) * 40 + 8))
            HStack {
                Spacer()
                Button(L.t("完了", "Done")) { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
    }

    private func row<T: View>(_ title: String, icon: String, accentIcon: Bool = false, action: @escaping () -> Void, @ViewBuilder trailing: () -> T) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).frame(width: 20).foregroundStyle(accentIcon ? Color.accentColor : Color.primary)
                Text(title).lineLimit(1)
                Spacer()
                trailing().frame(width: 20, height: 20)
            }
            .padding(.horizontal, 12)
            .frame(height: 38)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(RoundedRectangle(cornerRadius: 6).fill(Color.primary.opacity(0.0001)))
    }
}
#endif
