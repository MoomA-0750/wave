import Foundation
import Observation
import WaveCore
#if os(macOS)
    import AppKit
#else
    import UIKit
#endif

/// 曲の操作（メニューと曲の画面から使う）。
@MainActor
enum SongActions {
    /// YouTube Music の曲の URL（Android と同じ）。
    static func url(_ song: Song) -> URL { URL(string: "https://music.youtube.com/watch?v=\(song.videoId)")! }

    static func shareText(_ song: Song) -> String { song.artists.isEmpty ? song.title : "\(song.title) - \(song.artistsText)" }

    static func copyLink(_ song: Song) {
        #if os(macOS)
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(url(song).absoluteString, forType: .string)
        #else
            UIPasteboard.general.string = url(song).absoluteString
        #endif
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
