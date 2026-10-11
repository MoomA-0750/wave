import Foundation
import WaveCore

/// 評価のボタン（＋と✓）の出し方と押したときの動き。Mac と iPhone で同じ（画面の部品は各 OS の側）。
@MainActor
enum LikeButton {
    static func symbol(_ player: PlayerService) -> String {
        if player.like == .like || player.inPlaylist { return "checkmark.circle.fill" }
        if player.like == nil && AppServices.client.isLoggedIn && player.current != nil { return "arrow.clockwise" }
        return "plus.circle"
    }

    static func accent(_ player: PlayerService) -> Bool { player.like == .like || player.inPlaylist }

    static func tip(_ player: PlayerService) -> String {
        if player.like == .like { return L.t("プレイリストに追加", "Add to playlist") }
        if player.inPlaylist { return L.t("プレイリストに入っています（追加先を変える）", "Saved in a playlist (change)") }
        if player.like == nil && AppServices.client.isLoggedIn && player.current != nil { return L.t("評価を読み直す", "Reload like status") }
        return L.t("高く評価した曲に追加", "Add to Liked Songs")
    }

    /// 評価していなければ評価する。評価済みならプレイリストに追加する画面を開く（Android と同じ）。
    static func press(_ player: PlayerService) {
        guard let song = player.current else { return }
        if player.like == .like || player.inPlaylist {
            Dialogs.shared.addToPlaylist(song)
        } else if player.like == nil && AppServices.client.isLoggedIn {
            player.reloadLike()
        } else {
            player.toggleLike()
        }
    }
}
