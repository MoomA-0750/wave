import Foundation

/// LyricsService が YouTube Music から使うもの（MusicRepository の LyricsBrowseIdAsync・PlainLyricsOfTabAsync に当たる）。
public protocol PlainLyricsSource: Sendable {
    /// 曲の歌詞のタブの行き先（MPLY…）。歌詞の無い曲は ok(nil)。
    func lyricsBrowseId(videoId: String) async throws -> ApiResult<String?>
    /// 歌詞のタブの時刻なしの本文。
    func plainLyrics(browseId: String) async throws -> ApiResult<SongLyrics.Plain?>
}

/// LyricsService が LRCLIB から使うもの（LrclibClient の SyncedAsync に当たる）。
public protocol SyncedLyricsSource: Sendable {
    func synced(title: String, artist: String, album: String?, durationSeconds: Int?) async throws -> LrclibClient.Result
}

/// LyricsService が YouTube Music の時刻付きに使うもの（YouTubeTimedLyricsClient）。
public protocol TimedLyricsSource: Sendable {
    func timed(browseId: String) async throws -> YouTubeTimedLyricsClient.Result
}

extension LrclibClient: SyncedLyricsSource {}
extension YouTubeTimedLyricsClient: TimedLyricsSource {}

/// 曲の歌詞を選ぶ（Android 版の LyricsRepository と同じ）。YouTube Music の時刻付き（ANDROID_MUSIC として匿名で読む。曲の照合が要らず、
/// 出典は Musixmatch・LyricFind）、無ければ LRCLIB の時刻付き（曲名・アーティスト・長さで照らし合わせる）、それも無ければ YouTube Music の時刻なし。
/// 見つかった・無かった結果は曲ごとに覚える。失敗は覚えない。時刻付きを探すのに失敗して時刻なしを出したときも覚えない
/// （一時の失敗で、時刻なしが残り続けないように）。
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
    private let timed: TimedLyricsSource?
    private var cache: [String: Result] = [:]
    private var order: [String] = []
    private let lock = NSLock()

    public init(music: PlainLyricsSource, lrclib: SyncedLyricsSource, timed: TimedLyricsSource? = nil) {
        self.music = music
        self.lrclib = lrclib
        self.timed = timed
    }

    public func lyrics(song: Song) async throws -> Result {
        if let hit = lock.withLock({ cache[song.videoId] }) { return hit }
        let music = self.music
        let tab = try await music.lyricsBrowseId(videoId: song.videoId)
        let browseId = tab.isOk ? (tab.value ?? nil) : nil
        // 時刻付きと時刻なしを同時に尋ねる（どちらも歌詞のタブの ID だけを送る）
        let plainTask = Task<ApiResult<SongLyrics.Plain?>, Error> {
            guard let browseId else { return tab.isOk ? .ok(nil) : .failed(tab.failure ?? .parse, status: tab.status) }
            return try await music.plainLyrics(browseId: browseId)
        }
        var fromYouTube: YouTubeTimedLyricsClient.Result?
        if let browseId, let timed {
            do {
                fromYouTube = try await timed.timed(browseId: browseId)
            } catch {
                plainTask.cancel()
                throw error
            }
        }
        var synced: LrclibClient.Result = .notFound
        let result: Result
        if case .found(let lyrics) = fromYouTube {
            // YouTube Music で取れたら LRCLIB には聞かない
            result = .found(.synced(lyrics))
        } else {
            if let artist = song.artists.first {
                synced = try await lrclib.synced(title: song.title, artist: artist.name, album: song.album?.title, durationSeconds: song.durationSeconds)
            }
            result = LyricsService.choose(synced, try await plainTask.value)
        }
        var provisional = false
        if case .found(.plain) = result { provisional = fromYouTube == .failed || synced == .failed }
        if result != .failed && !provisional {
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

    /// 時刻付きを優先する。片方が失敗していて、もう片方に無ければ「分からない」（無いとは決めない）。
    static func choose(_ synced: LrclibClient.Result, _ plain: ApiResult<SongLyrics.Plain?>) -> Result {
        if case .found(let lines) = synced { return .found(.synced(SongLyrics.Synced(lines: lines, source: LyricsService.lrclibSource))) }
        guard plain.isOk else { return .failed }
        if let text = plain.value ?? nil { return .found(.plain(text)) }
        return synced == .failed ? .failed : .none
    }
}
