import AppIntents
import WaveCore

/// YouTube・YouTube Music のリンクか ID を受けて鳴らす（ショートカット・Siri）。リンクを読むのは WaveCore の YouTubeLink。
/// アプリの中だけで行う（ウィジェットの拡張には入れない。YouTube に触れるので）。
struct PlayLinkIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play a Link"
    static let description = IntentDescription("Plays a YouTube or YouTube Music link or ID: a song, video, live stream, playlist or album.")
    @Parameter(title: "Link") var link: String
    @Parameter(title: "Shuffle", default: false) var shuffle: Bool

    init() {}

    static var parameterSummary: some ParameterSummary {
        Summary("Play \(\.$link)") { \.$shuffle }
    }

    func perform() async throws -> some IntentResult {
        guard let parsed = YouTubeLink.parse(link) else { throw PlayLinkError.unreadable }
        try await WidgetSync.playLink(parsed, shuffle: shuffle)
        return .result()
    }
}

/// 「リンクで再生」の誤り（ショートカットに出る。英語をキーにして ja.lproj で日本語にする）。
enum PlayLinkError: Error, CustomLocalizedStringResourceConvertible {
    case unreadable, videoNotFound, playlistNotFound, albumNotFound, notReady

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .unreadable: "This isn't a YouTube or YouTube Music link or ID."
        case .videoNotFound: "Couldn't load the video for this link."
        case .playlistNotFound: "Couldn't load the playlist for this link."
        case .albumNotFound: "Couldn't load the album for this link."
        case .notReady: "wave isn't ready yet. Try again in a moment."
        }
    }
}
