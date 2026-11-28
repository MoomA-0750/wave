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
    /// ポッドキャストのエピソード（ダウンロードの画面で曲と分けて出す）。
    var episode = false

    var body: some View {
        let player = AppServices.player!
        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(song) }
        Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.addToQueue(song) }
        if let queueIndex {
            Button(L.t("キューから削除", "Remove from queue"), systemImage: "minus.circle") { player.removeAt(queueIndex) }
        }
        DownloadMenuItems(song: song, episode: episode)
        // RSS のエピソードは YouTube の曲ではない（プレイリスト・ラジオ・共有のリンクが無い。ADR-0012）
        let external = Feed.isExternal(song.videoId)
        Divider()
        // プレイリストへの追加はログインしているときだけ出す
        if !external && AppServices.client.isLoggedIn {
            Menu(L.t("プレイリストに保存", "Save to playlist"), systemImage: "plus") {
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
            Button(liked ? L.t("高く評価した曲から削除", "Remove from liked music") : L.t("高く評価した曲に追加", "Add to liked music"),
                   systemImage: liked ? "checkmark.circle.fill" : "plus.circle") { player.setLiked(song, !liked) }
        }
        if let removeFromPlaylist {
            Button(L.t("このプレイリストから削除", "Remove from this playlist"), systemImage: "trash", action: removeFromPlaylist)
        }
        Divider()
        if !external {
            Button(L.t("ラジオを開始", "Start radio"), systemImage: "dot.radiowaves.left.and.right") { player.playSong(song) }
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
            Button(L.t("曲の画面を開く", "Go to song"), systemImage: "music.note") { AppServices.nav.song(song) }
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
/// songs を渡すとキューの保存（作るときにその曲を入れる）。
struct CreatePlaylistSheet: View {
    var song: Song?
    var songs: [Song] = []
    @State private var name: String
    @Environment(\.dismiss) private var dismiss

    init(song: Song?, songs: [Song] = []) {
        self.song = song
        self.songs = songs
        _name = State(initialValue: !songs.isEmpty ? QueuePlaylist.defaultTitle(songs, japanese: L.ja) : song == nil ? "" : L.t("マイプレイリスト", "My playlist"))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Kasane.Space.s4) {
            Text(songs.isEmpty ? L.t("プレイリストを作成", "Create playlist") : L.t("キューをプレイリストとして保存", "Save queue as playlist"))
                .font(.title2.weight(.semibold))
            TextField(L.t("プレイリスト名", "Playlist name"), text: $name).textFieldStyle(.roundedBorder).frame(width: 340)
                .onSubmit(create)
            if !songs.isEmpty {
                Text(L.t("キューの \(L.songs(songs.count))（聴いた曲も含む）を、非公開のプレイリストに入れます。",
                         "The \(L.songs(songs.count)) in the queue (including ones already played) go into a private playlist."))
                    .font(.callout).foregroundStyle(.secondary).frame(width: 340, alignment: .leading)
            }
            HStack {
                Spacer()
                Button(L.t("キャンセル", "Cancel")) { dismiss() }.keyboardShortcut(.cancelAction)
                Button(songs.isEmpty ? L.t("作成", "Create") : L.t("保存", "Save"), action: create).keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(Kasane.Space.s6)
    }

    private func create() {
        let title = name.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { return }
        dismiss()
        let song = self.song
        let songs = self.songs
        Task { await Dialogs.shared.create(title, song: song, songs: songs) }
    }
}

/// プレイリストに追加する画面（Android・Spotify と同じ）。先頭は「高く評価した曲」で、押すと評価を外す（評価済みのライクボタンから開く）。
/// プレイリストを押しても閉じず、続けてほかのプレイリストにも足せる（閉じるのは「完了」）。入っているプレイリストには右に✓を出し、押すと外す。
/// 中身は `AddToPlaylistContent`（メニューバーの小窓と共通）。
struct AddToPlaylistSheet: View {
    var song: Song
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        AddToPlaylistContent(
            song: song,
            onNewPlaylist: { dismiss(); Dialogs.shared.createPlaylist(with: song) },
            onLiked: {
                dismiss()
                // 評価済みなら外す（今の曲のときだけ評価を変えられる）
                if AppServices.player.current?.videoId == song.videoId { AppServices.player.toggleLike() }
            },
            onDone: { dismiss() }
        )
        .padding(Kasane.Space.s6)
        .frame(width: 380 + Kasane.Space.s6 * 2)
    }
}

/// プレイリストに追加する画面の中身。幅は呼ぶ側が決める（左右の余白は呼ぶ側の padding だけ。中身は幅いっぱいに広がり、右だけ空かない）。
struct AddToPlaylistContent: View {
    var song: Song
    var maxListHeight: CGFloat = 480
    var onNewPlaylist: () -> Void
    var onLiked: () -> Void
    var onDone: () -> Void
    @State private var model: AddToPlaylistModel

    init(song: Song, maxListHeight: CGFloat = 480, onNewPlaylist: @escaping () -> Void, onLiked: @escaping () -> Void, onDone: @escaping () -> Void) {
        self.song = song
        self.maxListHeight = maxListHeight
        self.onNewPlaylist = onNewPlaylist
        self.onLiked = onLiked
        self.onDone = onDone
        _model = State(initialValue: AddToPlaylistModel(song: song))
    }

    var body: some View {
        let player = AppServices.player!
        let liked = player.current?.videoId == song.videoId ? player.like == .like : AppServices.library.isLiked(song.videoId)
        VStack(alignment: .leading, spacing: Kasane.Space.s3) {
            Text(L.t("プレイリストに保存", "Save to playlist")).font(.title2.weight(.semibold))
            ScrollView {
                VStack(spacing: 2) {
                    row(L.t("新しいプレイリスト", "New playlist"), icon: "plus", accentIcon: true, action: onNewPlaylist) { EmptyView() }
                    row(L.t("高く評価した曲", "Liked music"), icon: "heart", action: onLiked) {
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
            .frame(maxWidth: .infinity)
            .frame(height: min(maxListHeight, CGFloat(AppServices.library.editable.count + 2) * 40 + 8))
            HStack {
                Spacer()
                Button(L.t("完了", "Done"), action: onDone).keyboardShortcut(.defaultAction)
            }
        }
    }

    private func row<T: View>(_ title: String, icon: String, accentIcon: Bool = false, action: @escaping () -> Void, @ViewBuilder trailing: () -> T) -> some View {
        Button(action: action) {
            HStack(spacing: Kasane.Space.s3) {
                Image(systemName: icon).frame(width: 20).foregroundStyle(accentIcon ? Color.accentColor : Color.primary)
                Text(title).lineLimit(1)
                Spacer()
                trailing().frame(width: 20, height: 20)
            }
            .padding(.horizontal, Kasane.Space.s3)
            .frame(height: 38)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(RoundedRectangle(cornerRadius: Kasane.Radius.sm, style: Kasane.cornerStyle).fill(Color.primary.opacity(0.0001)))
    }
}
#endif
