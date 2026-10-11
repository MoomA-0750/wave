import Foundation
import WaveCore

/// 「この曲がどのプレイリストに入っているか」（Android の PlaylistMembership と同じ）。YouTube Music にそれを返す口が無いので、
/// プレイリストを1つずつ読む。結果（曲ごとのプレイリスト内の項目の ID）はプレイリストごとに覚え、このアプリで足した・外したらその1つだけ直す。古くなったら読み直す。
@MainActor
final class PlaylistMembership {
    enum Status { case checking, isIn, notIn, unknown }

    // 再生のたびに先読みするので、短すぎると毎回全部を読み直す（Android と同じ）
    private static let maxAge: TimeInterval = 30 * 60
    private var cache: [String: (readAt: Date, items: [String: [String]])] = [:]
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// そのプレイリストに曲が入っているか。最後まで読めなければ unknown。
    func check(_ playlistId: String, _ videoId: String) async -> Status {
        guard let items = await read(playlistId, fresh: false) else { return .unknown }
        return items[videoId] != nil ? .isIn : .notIn
    }

    private func acquire() async {
        if running < 6 {
            running += 1
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty { running -= 1 } else { waiters.removeFirst().resume() }
    }

    private func read(_ playlistId: String, fresh: Bool) async -> [String: [String]]? {
        if !fresh, let hit = cache[playlistId], Date().timeIntervalSince(hit.readAt) < PlaylistMembership.maxAge { return hit.items }
        await acquire()
        defer { release() }
        guard let items = try? await AppServices.music.scanPlaylistItems(playlistId) else { return nil }
        cache[playlistId] = (Date(), items)
        return items
    }

    /// 曲を外すのに使う、プレイリスト内の項目の ID。覚えていなければ読み直す。読めなければ nil、入っていなければ空。
    func setVideoIds(_ playlistId: String, _ videoId: String) async -> [String]? {
        if let known = await read(playlistId, fresh: false)?[videoId], !known.isEmpty { return known }
        guard let items = await read(playlistId, fresh: true) else { return nil }
        return items[videoId] ?? []
    }

    /// 再生中の曲（先読みした曲）。
    private var nowPlayingId: String?
    /// 再生中の曲が入っている（と分かった）プレイリスト。
    private var nowPlayingIn: Set<String> = []

    /// 再生中の曲がどのプレイリストに入っているかが変わった。
    var nowPlayingChanged: (() -> Void)?

    /// その曲がどれかのプレイリストに入っているか（先読みした再生中の曲だけ分かる。Spotify の✓の印に使う）。
    func isSavedNowPlaying(_ videoId: String?) -> Bool {
        videoId != nil && videoId == nowPlayingId && !nowPlayingIn.isEmpty
    }

    /// 再生が始まった曲について、自分のプレイリストを先に読んでおく（追加の画面を開いたときに待たせない。Android と同じ）。
    func prefetch(_ playlistIds: [String], _ videoId: String) async {
        if nowPlayingId != videoId {
            nowPlayingId = videoId
            nowPlayingIn.removeAll()
            nowPlayingChanged?()
        }
        let started = Date()
        await withTaskGroup(of: Void.self) { group in
            for id in playlistIds {
                group.addTask { @MainActor in
                    if await self.check(id, videoId) == .isIn, !Task.isCancelled, self.nowPlayingId == videoId,
                        self.nowPlayingIn.insert(id).inserted
                    {
                        self.nowPlayingChanged?()
                    }
                }
            }
        }
        AppPaths.log("membership prefetch playlists=\(playlistIds.count) in=\(nowPlayingIn.count) ms=\(Int(Date().timeIntervalSince(started) * 1000))")
    }

    private func noteNowPlaying(_ playlistId: String, _ videoId: String, _ isIn: Bool) {
        guard videoId == nowPlayingId else { return }
        let changed = isIn ? nowPlayingIn.insert(playlistId).inserted : nowPlayingIn.remove(playlistId) != nil
        if changed { nowPlayingChanged?() }
    }

    func noteAdded(_ playlistId: String, _ videoId: String) {
        if cache[playlistId] != nil, cache[playlistId]!.items[videoId] == nil { cache[playlistId]!.items[videoId] = [] }
        noteNowPlaying(playlistId, videoId, true)
    }

    func noteRemoved(_ playlistId: String, _ videoId: String) {
        cache[playlistId]?.items.removeValue(forKey: videoId)
        noteNowPlaying(playlistId, videoId, false)
    }

    func invalidate(_ playlistId: String) { cache.removeValue(forKey: playlistId) }

    func clear() {
        cache.removeAll()
        nowPlayingId = nil
        nowPlayingIn.removeAll()
        nowPlayingChanged?()
    }
}
