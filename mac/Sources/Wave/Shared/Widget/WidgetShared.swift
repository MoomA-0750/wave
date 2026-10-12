import AppIntents
import Foundation
#if os(iOS)
    import ActivityKit
#endif

// アプリとウィジェット（WaveWidget）の両方で組むファイル（Sources/WaveWidget/Shared はここを指す）。
// アプリが今の曲・ライブラリのプレイリストをアプリグループの置き場へ書き、ウィジェットはそれを読んで出す。
// ウィジェットのボタンは App Intents で、再生にかかわる頼み（AudioPlaybackIntent）はアプリの中で行われる。

/// アプリとウィジェットで分け合う置き場。
enum WidgetStore {
    #if os(iOS)
        static let group = "group.xyz.mooma0750.wave"
    #else
        static let group = "4U9XZY5WK3.xyz.mooma0750.wave"
    #endif
    static let nowPlayingKind = "xyz.mooma0750.wave.nowplaying"
    static let playlistKind = "xyz.mooma0750.wave.playlist"

    static var container: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) }

    static func file(_ name: String) -> URL? { container?.appendingPathComponent(name) }

    static func read<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        guard let url = file(name), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    @discardableResult
    static func write<T: Encodable>(_ value: T, _ name: String) -> Bool {
        guard let url = file(name), let data = try? JSONEncoder().encode(value) else { return false }
        return (try? data.write(to: url, options: .atomic)) != nil
    }

    static var nowPlaying: WidgetNowPlaying? { read(WidgetNowPlaying.self, "nowplaying.json") }
    static var playlists: [WidgetPlaylist] { read([WidgetPlaylist].self, "playlists.json") ?? [] }

    /// 今の曲のジャケット（小さい絵。ウィジェットとロック画面は通信せずにこれを読む）。
    static func artworkFile(_ videoId: String) -> URL? { file("artwork-\(videoId).jpg") }
    static func coverFile(_ playlistId: String) -> URL? { file("cover-\(playlistId.replacingOccurrences(of: "/", with: "_")).jpg") }
}

/// 今の曲（ウィジェットに出す分だけ）。
struct WidgetNowPlaying: Codable, Equatable {
    var videoId: String
    var title: String
    var artist: String
    var playing: Bool
    /// ほかの端末で鳴っているときは、その端末の名前。
    var device: String?
    var nextTitle: String?
    var nextArtist: String?
}

/// ライブラリのプレイリスト（ウィジェットで選ぶ）。
struct WidgetPlaylist: Codable, Equatable, Hashable {
    var id: String
    var title: String
    var thumbnailUrl: String?
}

/// ウィジェット・ロック画面のボタンから、アプリに頼むこと。
enum WidgetCommand: Sendable {
    case togglePlay, next, previous
    case playPlaylist(id: String, shuffle: Bool)
}

/// 頼みを受けるアプリ側の口（アプリの起動時に入れる。ウィジェットの中では空のまま）。
@MainActor
enum WidgetBridge {
    static var handler: ((WidgetCommand) async -> Void)?

    static func run(_ command: WidgetCommand) async {
        await handler?(command)
    }
}

struct TogglePlayIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "再生・一時停止"
    init() {}
    func perform() async throws -> some IntentResult {
        await WidgetBridge.run(.togglePlay)
        return .result()
    }
}

struct NextTrackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "次の曲"
    init() {}
    func perform() async throws -> some IntentResult {
        await WidgetBridge.run(.next)
        return .result()
    }
}

struct PreviousTrackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "前の曲"
    init() {}
    func perform() async throws -> some IntentResult {
        await WidgetBridge.run(.previous)
        return .result()
    }
}

struct PlayPlaylistIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "プレイリストを再生"
    @Parameter(title: "プレイリスト") var playlistId: String
    @Parameter(title: "シャッフル") var shuffle: Bool

    init() {}

    init(playlistId: String, shuffle: Bool) {
        self.playlistId = playlistId
        self.shuffle = shuffle
    }

    func perform() async throws -> some IntentResult {
        await WidgetBridge.run(.playPlaylist(id: playlistId, shuffle: shuffle))
        return .result()
    }
}

/// ウィジェットで選ぶプレイリスト。
struct PlaylistEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "プレイリスト"
    static let defaultQuery = PlaylistQuery()

    var id: String
    var title: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)") }
}

struct PlaylistQuery: EntityQuery {
    init() {}

    func entities(for identifiers: [String]) async throws -> [PlaylistEntity] {
        WidgetStore.playlists.filter { identifiers.contains($0.id) }.map { PlaylistEntity(id: $0.id, title: $0.title) }
    }

    func suggestedEntities() async throws -> [PlaylistEntity] {
        WidgetStore.playlists.map { PlaylistEntity(id: $0.id, title: $0.title) }
    }
}

/// プレイリストのウィジェットの設定（どのプレイリストを出すか）。
struct SelectPlaylistIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "プレイリストを選ぶ"
    @Parameter(title: "プレイリスト") var playlist: PlaylistEntity?
    init() {}
}

#if os(iOS)
    /// ロック画面・ダイナミックアイランドの「再生中」（Live Activity）。
    struct NowPlayingAttributes: ActivityAttributes {
        struct ContentState: Codable, Hashable {
            var videoId: String
            var title: String
            var artist: String
            var playing: Bool
            var device: String?
            var nextTitle: String?
        }
    }
#endif
