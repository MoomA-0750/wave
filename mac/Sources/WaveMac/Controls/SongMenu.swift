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
        Divider()
        Menu(L.t("プレイリストに追加", "Add to playlist"), systemImage: "plus") {
            Button(L.t("新しいプレイリスト", "New playlist"), systemImage: "plus") { Dialogs.shared.createPlaylist(with: song) }
            let editable = AppServices.library.editable
            if !editable.isEmpty { Divider() }
            ForEach(editable, id: \.playlistId) { playlist in
                Button(playlist.title) { Task { await SongActions.add(playlist, song) } }
            }
        }
        .disabled(!AppServices.client.isLoggedIn)
        if let removeFromPlaylist {
            Button(L.t("このプレイリストから削除", "Remove from this playlist"), systemImage: "trash", action: removeFromPlaylist)
        }
        Divider()
        Button(L.t("曲のラジオを再生", "Go to song radio"), systemImage: "dot.radiowaves.left.and.right") { player.playSong(song) }
        if let albumId = song.album?.browseId {
            Button(L.t("アルバムへ移動", "Go to album"), systemImage: "square.stack") { AppServices.nav.album(albumId) }
        }
        ForEach(song.artists.filter { $0.browseId != nil }, id: \.self) { artist in
            Button(song.artists.count > 1 ? L.t("アーティストへ移動: \(artist.name)", "Go to artist: \(artist.name)") : L.t("アーティストへ移動", "Go to artist"),
                   systemImage: "person") { AppServices.nav.artist(artist.browseId!) }
        }
        Divider()
        Menu(L.t("共有", "Share"), systemImage: "square.and.arrow.up") {
            Button(L.t("曲のリンクをコピー", "Copy song link"), systemImage: "link") { SongActions.copyLink(song) }
            ShareLink(L.t("ほかのアプリで共有…", "Share with…"), item: SongActions.url(song), subject: Text(song.title),
                      message: Text(SongActions.shareText(song)))
        }
    }
}

/// 曲の操作（メニューと曲の画面から使う）。
@MainActor
enum SongActions {
    /// YouTube Music の曲の URL（Android と同じ）。
    static func url(_ song: Song) -> URL { URL(string: "https://music.youtube.com/watch?v=\(song.videoId)")! }

    static func shareText(_ song: Song) -> String { song.artists.isEmpty ? song.title : "\(song.title) - \(song.artistsText)" }

    static func copyLink(_ song: Song) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(url(song).absoluteString, forType: .string)
        AppServices.player.message = L.t("リンクをコピーしました", "Link copied")
    }

    /// プレイリストから曲を外す（同じ曲が2回入っていれば両方。追加の画面で、入っているプレイリストを押したとき。Android と同じ）。外せたか。
    static func remove(_ playlist: PlaylistSummary, _ song: Song) async -> Bool {
        let membership = AppServices.library.membership
        let setIds = await membership.setVideoIds(playlist.playlistId, song.videoId)
        var removed = !(setIds ?? []).isEmpty
        if removed, let setIds {
            for setId in setIds {
                let ok = (try? await AppServices.music.removeFromPlaylist(playlist.playlistId, videoId: song.videoId, setVideoId: setId)) ?? false
                removed = removed && ok
            }
            if removed { membership.noteRemoved(playlist.playlistId, song.videoId) } else { membership.invalidate(playlist.playlistId) }
        }
        AppPaths.log("playlist remove-song known=\(setIds != nil) removed=\(removed)")
        AppServices.player.message = removed ? L.t("「\(playlist.title)」から外しました", "Removed from \(playlist.title)") : L.t("外せませんでした", "Couldn't remove it")
        return removed
    }

    @discardableResult
    static func add(_ playlist: PlaylistSummary, _ song: Song, knownEmpty: Bool = false) async -> AddToPlaylistResult {
        let result = (try? await AppServices.music.addToPlaylist(playlist.playlistId, videoId: song.videoId, knownEmpty: knownEmpty)) ?? .failed
        if result == .added || result == .alreadyInPlaylist { AppServices.library.membership.noteAdded(playlist.playlistId, song.videoId) }
        AppServices.player.message = switch result {
        case .added: L.t("「\(playlist.title)」に追加しました", "Added to \(playlist.title)")
        case .alreadyInPlaylist: L.t("「\(playlist.title)」にはもう入っています", "Already in \(playlist.title)")
        case .notChecked: L.t("重複を確かめられなかったので、追加しませんでした", "Couldn't check for duplicates, so it wasn't added")
        default: L.t("追加できませんでした", "Couldn't add it")
        }
        return result
    }
}

/// 小さな入力の画面（プレイリストに追加・作成）。主画面のシートで出す。
@MainActor @Observable
final class Dialogs {
    static let shared = Dialogs()

    /// プレイリストに追加する画面を出している曲。
    var addToPlaylist: Song?
    /// プレイリストを作る画面（作ったあとに入れる曲）。
    var creating = false
    var createSong: Song?

    func addToPlaylist(_ song: Song) { addToPlaylist = song }

    func createPlaylist(with song: Song? = nil) {
        createSong = song
        creating = true
    }

    /// 新しいプレイリストを作る（非公開）。song を渡したら作ったあとに入れる。
    func create(_ title: String, song: Song?) async {
        let created = (try? await AppServices.music.createPlaylist(title)) ?? .failed(.network)
        guard let id = created.value else {
            AppServices.player.message = L.t("プレイリストを作成できませんでした", "Couldn't create the playlist")
            return
        }
        let playlist = PlaylistSummary(playlistId: id, title: title, subtitle: L.t("非公開", "Private"), thumbnailUrl: nil, editable: true)
        AppServices.library.addCreated(playlist)
        if let song { await SongActions.add(playlist, song, knownEmpty: true) } else { AppServices.player.message = L.t("「\(title)」を作成しました", "Created \(title)") }
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
    @State private var status: [String: PlaylistMembership.Status] = [:]
    @State private var busy: Set<String> = []

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
                            toggle(playlist)
                        } trailing: {
                            let s = status[playlist.playlistId] ?? .checking
                            if busy.contains(playlist.playlistId) || s == .checking {
                                ProgressView().controlSize(.small)
                            } else if s == .isIn {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor)
                            } else if s == .unknown {
                                Image(systemName: "questionmark.circle").foregroundStyle(.secondary).help(L.t("入っているか確かめられませんでした", "Couldn't check"))
                            }
                        }
                        .task {
                            let s = await AppServices.library.membership.check(playlist.playlistId, song.videoId)
                            status[playlist.playlistId] = s
                        }
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
