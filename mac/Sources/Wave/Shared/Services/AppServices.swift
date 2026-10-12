import Foundation
import WaveCore

/// アプリ全体で1つずつ持つもの（Windows の AppServices と同じ）。
@MainActor
enum AppServices {
    private(set) static var settings: Settings!
    /// YouTube への通信（cookie を自動で持たない）。
    private(set) static var client: InnerTubeClient!
    /// 音声・映像のバイトの取得（認証のヘッダを付けない）。
    nonisolated(unsafe) private(set) static var mediaSession: URLSession!
    private(set) static var music: MusicRepository!
    /// ライブ配信の検索と HLS の解決（www.youtube.com。ログインの情報を付けない、別の cookie なしの通信）。
    private(set) static var live: LiveClient!
    private(set) static var resolver: StreamResolver!
    private(set) static var stats: PlaybackStats!
    private(set) static var lyrics: LyricsService!
    private(set) static var credentials: CredentialStore!
    private(set) static var player: PlayerService!
    private(set) static var library: LibraryState!
    private(set) static var account: AccountState!
    /// 登録したアーティストの新作。
    private(set) static var releases: Releases!
    /// Claude との会話（アプリが動いている間だけ持つ）。
    private(set) static var ai: AiChat!
    /// 端末の連携（中継の場所が設定に無ければ使えない）。
    private(set) static var connect: ConnectService!
    #if os(macOS)
        /// Discord に再生中の曲を出す（設定でオンにしたときだけ）。
        private(set) static var discord: DiscordStatus!
    #endif
    /// 画面の移り変わり。
    private(set) static var nav: Nav!
    /// 画面の下の知らせ。
    private(set) static var toast: ToastCenter!

    nonisolated private static let clockStart = Date()
    nonisolated static func clockMs() -> Int64 { Int64(Date().timeIntervalSince(clockStart) * 1000) }

    static func initialize() {
        settings = Settings.load()
        L.initialize(settings.language)
        RangeStream.diagnostics = { AppPaths.log($0) }
        let session = InnerTubeClient.createSession()
        mediaSession = InnerTubeClient.createSession()
        // YouTube から届く文字（棚の見出しなど）を画面と同じ言語にする
        client = InnerTubeClient(session: session, hl: L.hl)
        live = LiveClient(session: InnerTubeClient.createSession(), hl: L.hl, gl: client.gl, nowMs: { AppServices.clockMs() })
        music = MusicRepository(client: client, live: live)
        resolver = StreamResolver(client: client, cipher: PlayerCipherProvider(session: session, nowMs: { AppServices.clockMs() }), nowMs: { AppServices.clockMs() })
        stats = PlaybackStats(client: client)
        // 歌詞の検索（LRCLIB）には YouTube と別の通信を使う
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0"
        lyrics = LyricsService(music: music, lrclib: LrclibClient(session: InnerTubeClient.createSession(), userAgent: "wave-mac/\(version)"))
        credentials = CredentialStore()
        toast = ToastCenter()
        nav = Nav()
        account = AccountState()
        library = LibraryState()
        player = PlayerService()
        ai = AiChat()
        account.restore()
        // 前回のキューを、再生を始めずに戻す（同じログインのときだけ）
        player.restoreSavedQueue()
        // 中継の宛先は設定にあればそれ、無ければ組んだときに書き込んだもの（Android の local.properties の wave.relayV2Url と同じ）
        let built = { (key: String) in (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 } }
        connect = ConnectService(player: player, relayUrl: settings.relayV2Url ?? built("WaveRelayV2Url"),
                                 relayHost: settings.relayV2Host ?? built("WaveRelayV2Host"))
        releases = Releases()
        releases.start()
        #if os(macOS)
            discord = DiscordStatus()
        #endif
        account.changed.append { library.refresh() }
        library.refresh()
        #if WAVE_DEBUG && os(macOS)
            DebugCommands.start()
        #endif
    }

    /// プレイリストの一覧が届いた（起動直後は、これを待ってから今の曲を先読みする）。
    static func onLibraryLoaded() {
        prefetchMembership()
    }

    private static var membershipTask: Task<Void, Never>?

    /// 再生が始まった曲がどのプレイリストに入っているかを先に調べておく（追加の画面をすぐ出し、評価のボタンに✓を付ける。Android と同じ）。
    static func prefetchMembership() {
        membershipTask?.cancel()
        let membership = library.membership
        player.inPlaylist = membership.isSavedNowPlaying(player.current?.videoId)
        guard let song = player.current, client.isLoggedIn else { return }
        let ids = library.editable.map(\.playlistId)
        membershipTask = Task { await membership.prefetch(ids, song.videoId) }
    }
}
