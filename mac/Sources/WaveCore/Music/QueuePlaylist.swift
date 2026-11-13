import Foundation

/// キューをプレイリストとして保存する（SPEC「キューをプレイリストとして保存」。全端末で同じ決まり）。
public enum QueuePlaylist {
    /// 保存する曲: キューの全部を並びの順に。エピソード（RSS・YouTube）とライブは除き、同じ曲は最初の1つだけ。
    public static func songsToSave(_ queue: [Song]) -> [Song] {
        var seen = Set<String>()
        return queue.filter { song in
            !song.isLive && !Feed.isExternal(song.videoId) && song.kind != episodeKind && !song.videoId.isEmpty && seen.insert(song.videoId).inserted
        }
    }

    /// 名前の既定: 「〈キューの先頭の曲名〉のラジオ」。
    public static func defaultTitle(_ songs: [Song], japanese: Bool) -> String {
        guard let first = songs.first else { return japanese ? "キュー" : "Queue" }
        return japanese ? "\(first.title)のラジオ" : "\(first.title) radio"
    }

    static let episodeKind = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
}
