import Foundation

/// 一覧を流し終えたあとに、最後の曲から始まるラジオへつなぐ判断（Android の QueueSession と同じ。決定 E4）。
/// 再生の部品（PlayerService）から切り出して、試験で確かめられるようにしたもの。
public enum QueueRadio {
    /// 残りがこの件数以下になったら続きを取る（Android の FETCH_WHEN_REMAINING_AT_MOST と同じ）。
    public static let fetchWhenRemainingAtMost = 2

    /// 曲のラジオの playlistId（Android の radioPlaylistIdOf と同じ）。
    public static func playlistId(_ seed: String) -> String { "RDAMVM" + seed }

    /// その曲からラジオを作れるか。ライブ配信は終わらずに流れ続け、RSS のエピソードは YouTube のラジオが作れない（ADR-0012）。
    public static func canSeed(_ song: Song) -> Bool { !song.isLive && !Feed.isExternal(song.videoId) }

    /// 残りが少なくなって、続きを取る頃合いか。index は今の曲の位置。
    public static func nearEnd(count: Int, index: Int) -> Bool {
        index >= 0 && index < count && count - 1 - index <= fetchWhenRemainingAtMost
    }

    /// 一覧の終わりが近いときに、ラジオへ切り替えるなら種の曲の videoId を返す（Android の switchToRadio）。
    /// すでにラジオのとき（種か続きの印がある）・一覧の残りをまだ読んでいるとき・全体リピート中・
    /// 最後の曲がライブ配信か RSS のエピソードのときは切り替えない。
    public static func switchSeed(queue: [Song], index: Int, radioSeed: String?, radioContinuation: String?, repeatAll: Bool,
                                  restPending: Bool) -> String?
    {
        guard radioSeed == nil, radioContinuation == nil, !restPending, !repeatAll, nearEnd(count: queue.count, index: index),
              let last = queue.last, canSeed(last)
        else { return nil }
        return last.videoId
    }

    /// 端末の連携へ書き出すキューが「尽きた」（続きもラジオへの切り替えも無い）か。
    /// プレイリスト・アルバムは最後まで行ったら受け取った端末がラジオへ切り替えるので、尽きていない（Android の Playlist と同じ）。
    /// 尽きているのは、ラジオでも一覧でもない（1曲のライブ配信・RSS など）か、最後の曲からラジオが作れないときだけ。
    public static func exhausted(radioSeed: String?, playlistSource: Bool, last: Song?, truncated: Bool) -> Bool {
        if truncated || radioSeed != nil { return false }
        guard playlistSource, let last else { return true }
        return !canSeed(last)
    }
}
