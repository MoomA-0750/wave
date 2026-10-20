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
    static func artworkFile(_ videoId: String) -> URL? { file(artworkName(videoId)) }

    /// ジャケットのファイル名。RSS のエピソード（`ext:` + 音声の URL。ADR-0012）は `/` や `:` を含むので、
    /// ID の字だけでできていなければ、決まった値になるハッシュ（FNV-1a）で名前にする（アプリとウィジェットで同じ名前になる）。
    static func artworkName(_ videoId: String) -> String {
        let plain = !videoId.isEmpty && videoId.utf8.count <= 64 && videoId.unicodeScalars.allSatisfy {
            ($0.value >= 0x30 && $0.value <= 0x39) || ($0.value >= 0x41 && $0.value <= 0x5A) || ($0.value >= 0x61 && $0.value <= 0x7A) || $0 == "_" || $0 == "-"
        }
        if plain { return "artwork-\(videoId).jpg" }
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in videoId.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return "artwork-x\(String(hash, radix: 16)).jpg"
    }
    /// ウィジェットの文言の言語（アプリの画面の言語。アプリが起動のたびに書く）。
    static var language: String? { read(String.self, "language.json") }

    static func coverFile(_ playlistId: String) -> URL? { file("cover-\(playlistId.replacingOccurrences(of: "/", with: "_")).jpg") }
}

/// ウィジェットの文言（アプリの L と同じく日本語と英語を並べて書く）。アプリの画面の言語に合わせ、分からなければ端末の言語。
enum WidgetText {
    static var ja: Bool { WidgetStore.language.map { $0 == "ja" } ?? (Locale.preferredLanguages.first ?? "").hasPrefix("ja") }

    static func t(_ ja: String, _ en: String) -> String { Self.ja ? ja : en }
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
    /// 評価のボタンを✓にするか（アプリと同じく、高く評価したか、どれかのプレイリストに入っている）。出せないとき（ログインしていない・RSS のエピソード）は nil。
    var liked: Bool?
    /// 次に流れる曲（3曲まで。ウィジェットの右側に並べ、押すとその曲へ飛ぶ）。
    var upNext: [WidgetQueueItem]?
}

/// キューの曲（index はキューの中の位置。押されたとき、videoId と合わなければ探し直す）。
struct WidgetQueueItem: Codable, Equatable, Identifiable {
    var index: Int
    var videoId: String
    var title: String
    var artist: String

    var id: Int { index }
}

/// ライブラリのプレイリスト（ウィジェットで選ぶ）。
struct WidgetPlaylist: Codable, Equatable, Hashable {
    var id: String
    var title: String
    var thumbnailUrl: String?
}

/// ウィジェット・ロック画面のボタンから、アプリに頼むこと。
enum WidgetCommand: Sendable {
    case togglePlay, next, previous, toggleLike
    case playQueueItem(index: Int, videoId: String)
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

/// 高く評価する（＋のとき）。評価は再生ではないが、AudioPlaybackIntent にするとアプリの中で行われる（ほかの頼みと同じくアプリの評価の手順を使う）。
struct ToggleLikeIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "高く評価"
    init() {}
    func perform() async throws -> some IntentResult {
        await WidgetBridge.run(.toggleLike)
        return .result()
    }
}

/// キューの曲を選んで鳴らす（ウィジェットの「次に流れる曲」）。
struct PlayQueueItemIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "キューの曲を再生"
    @Parameter(title: "位置") var index: Int
    @Parameter(title: "曲") var videoId: String

    init() {}

    init(index: Int, videoId: String) {
        self.index = index
        self.videoId = videoId
    }

    func perform() async throws -> some IntentResult {
        await WidgetBridge.run(.playQueueItem(index: index, videoId: videoId))
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

/// プレイリストのウィジェットに出すもの。
enum PlaylistWidgetSource: String, AppEnum {
    case recent, chosen

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "出すもの"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .recent: "最近再生した順",
        .chosen: "選んだプレイリスト",
    ]
}

/// プレイリストのウィジェットの設定（最近再生した順か、選んだプレイリストか）。
struct SelectPlaylistIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "プレイリストを選ぶ"
    @Parameter(title: "出すもの", default: .recent) var source: PlaylistWidgetSource
    @Parameter(title: "プレイリスト", size: [.systemSmall: 4, .systemMedium: 4, .systemLarge: 8]) var playlists: [PlaylistEntity]?
    init() {}

    static var parameterSummary: some ParameterSummary {
        When(\.$source, .equalTo, .chosen) {
            Summary {
                \.$source
                \.$playlists
            }
        } otherwise: {
            Summary { \.$source }
        }
    }
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
