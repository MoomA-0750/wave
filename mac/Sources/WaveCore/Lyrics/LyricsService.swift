import Foundation

/// LyricsService が YouTube Music から使うもの（MusicRepository の PlainLyricsAsync に当たる）。
public protocol PlainLyricsSource: Sendable {
    /// 曲の時刻なしの歌詞。歌詞が無ければ ok(nil)。
    func plainLyrics(videoId: String) async throws -> ApiResult<SongLyrics.Plain?>
}

/// LyricsService が LRCLIB から使うもの（LrclibClient の SyncedAsync に当たる）。
public protocol SyncedLyricsSource: Sendable {
    func synced(title: String, artist: String, album: String?, durationSeconds: Int?) async throws -> LrclibClient.Result
}

extension LrclibClient: SyncedLyricsSource {}

/// 曲の歌詞を選ぶ（Android 版の LyricsRepository と同じ）。時刻付きは LRCLIB から、無ければ YouTube Music の時刻なしの歌詞。
/// 見つかった・無かった結果は曲ごとに覚える。失敗は覚えない。
public final class LyricsService: @unchecked Sendable {
    public static let lrclibSource = "LRCLIB"
    private static let cacheSize = 50

    public enum Result: Hashable, Sendable {
        case found(SongLyrics)
        case none
        case failed
    }

    private let music: PlainLyricsSource
    private let lrclib: SyncedLyricsSource
    private var cache: [String: Result] = [:]
    private var order: [String] = []
    private let lock = NSLock()

    public init(music: PlainLyricsSource, lrclib: SyncedLyricsSource) {
        self.music = music
        self.lrclib = lrclib
    }

    public func lyrics(song: Song) async throws -> Result {
        if let hit = lock.withLock({ cache[song.videoId] }) { return hit }
        // LRCLIB と YouTube Music を同時に尋ねる
        let lrclib = self.lrclib
        let music = self.music
        async let syncedTask: LrclibClient.Result = song.artists.isEmpty
            ? .notFound
            : lrclib.synced(title: song.title, artist: song.artists[0].name, album: song.album?.title, durationSeconds: song.durationSeconds)
        async let plainTask = music.plainLyrics(videoId: song.videoId)
        let synced = try await syncedTask
        let plain = try await plainTask
        let result: Result
        if case .found(let lines) = synced {
            result = .found(.synced(SongLyrics.Synced(lines: lines, source: LyricsService.lrclibSource)))
        } else if !plain.isOk {
            result = .failed
        } else if let text = plain.value ?? nil {
            result = .found(.plain(text))
        } else {
            result = synced == .failed ? .failed : .none
        }
        if result != .failed {
            lock.withLock {
                cache[song.videoId] = result
                order.append(song.videoId)
                while order.count > LyricsService.cacheSize {
                    cache.removeValue(forKey: order.removeFirst())
                }
            }
        }
        return result
    }
}
