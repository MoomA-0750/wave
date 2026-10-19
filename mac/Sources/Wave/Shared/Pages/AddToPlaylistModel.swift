import Foundation
import WaveCore

/// プレイリストに追加する画面（Mac のシート・iPhone のシート）の状態: プレイリストごとに曲が入っているかと、書いている途中か。
/// 押すと、入っていれば外し、無ければ足す（Android・Spotify と同じ）。
@MainActor @Observable
final class AddToPlaylistModel {
    let song: Song
    private(set) var status: [String: PlaylistMembership.Status] = [:]
    private(set) var busy: Set<String> = []

    init(song: Song) {
        self.song = song
    }

    /// 入っているか（確かめている途中・書いている途中は checking）。
    func state(_ playlist: PlaylistSummary) -> PlaylistMembership.Status {
        busy.contains(playlist.playlistId) ? .checking : status[playlist.playlistId] ?? .checking
    }

    func check(_ playlist: PlaylistSummary) async {
        status[playlist.playlistId] = await AppServices.library.membership.check(playlist.playlistId, song.videoId)
    }

    func toggle(_ playlist: PlaylistSummary) {
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
