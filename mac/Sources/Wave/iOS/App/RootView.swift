#if os(iOS)
import SwiftUI
import WaveCore

/// 下のタブ（Android と同じ4つ: ホーム・検索・Claude・ライブラリ）。
enum IOSTab: String, Hashable {
    case home, search, ai, library
}

/// 画面の枠の状態（タブ・タブごとの画面の積み重ね・再生中の画面）。
@MainActor @Observable
final class IOSShell {
    static let shared = IOSShell()

    var tab = IOSTab.home
    /// 下のタブの欄で右に離して置くタブ（設定で選ぶ。欄を畳んでも出ている）。
    var pinnedTab = IOSTab(rawValue: AppServices.settings.pinnedTab).flatMap { $0 == .home ? nil : $0 } ?? .search
    /// タブごとの、上に積んだ画面（Android の AppNavigator と同じく、タブを切り替えても積み重ねを覚えておく）。
    var paths: [IOSTab: [Route]] = [.home: [], .search: [], .ai: [], .library: []]
    /// 再生中の画面を開いているか（ミニプレーヤーを押すと下からせり上がる）。
    var playerOpen = false
    /// 再生中の画面で「曲｜動画」の動画を選んでいるか（PiP から戻ったときも映像を出せるよう、画面の外に持つ）。
    var showVideo = false
    /// 映像を全画面で出しているか。
    var videoFullScreen = false
    /// 再生中の画面の上にキューを出しているか。
    var queueOpen = false
    /// 検索のタブに入れておく言葉（ほかの画面から「検索」へ移ったとき）。
    var searchQuery: String?

    /// いま一番上に出ている画面。プレイリストに追加する画面などは、ここから出す（下の画面からは、上の画面を閉じるまで出せない）。
    var topLayer: SheetLayer {
        guard playerOpen else { return .root }
        if queueOpen { return .queue }
        if LyricsSheetState.shared.open { return .lyrics }
        return .player
    }

    func path(_ tab: IOSTab) -> Binding<[Route]> {
        Binding(get: { self.paths[tab] ?? [] }, set: { self.paths[tab] = $0 })
    }

    /// 共有の Nav（通知から開く・開発用の命令・曲のメニューなど）で選ばれた行き先を、今のタブの積み重ねに積む。
    func open(_ route: Route) {
        switch route {
        case .home:
            tab = .home
            paths[.home] = []
        case .search(let query):
            tab = .search
            paths[.search] = []
            searchQuery = query
        case .lyrics:
            playerOpen = true
        default:
            // 再生中の画面からアルバムやアーティストへ移ったら、再生中の画面は閉じて、その下の画面に積む
            playerOpen = false
            queueOpen = false
            paths[tab, default: []].append(route)
        }
    }

    /// 同じタブをもう一度押したら、そのタブの一番上へ戻る（iOS の標準と同じ）。
    func select(_ next: IOSTab) {
        if next == tab { paths[next] = [] }
        tab = next
    }
}

struct RootView: View {
    @State private var shell = IOSShell.shared
    @State private var account = AppServices.account!
    /// ミニプレーヤーから再生中の画面へ広がる動き（下へ払うと閉じられる）
    @Namespace private var playerZoom

    var body: some View {
        let player = AppServices.player!
        TabView(selection: Binding(get: { shell.tab }, set: { shell.select($0) })) {
            // 設定で選んだタブは、検索の役（role: .search）にして右に離して置く。iOS 26 では欄を下へ送って畳んでも、
            // 左の再生中のタブと並んで右に残る（OS が離して置くのは検索の役のタブだけ）
            ForEach(tabOrder, id: \.self) { tab in
                Tab(tab.title, systemImage: tab.symbol, value: tab, role: tab == shell.pinnedTab ? .search : nil) {
                    stack(tab) { TabRoot(tab: tab) }
                }
            }
        }
        .modifier(MiniPlayerAccessory(show: player.current != nil, zoom: playerZoom) { shell.playerOpen = true })
        // 音楽の PiP の元の枠（画面の後ろに小さく置く）
        .background(alignment: .bottomLeading) { MusicPipAnchor().frame(width: 16, height: 9) }
        .overlay(alignment: .bottom) { ToastHost().padding(.bottom, player.current != nil ? 120 : 64) }
        .fullScreenCover(isPresented: $shell.playerOpen) {
            NowPlayingView().navigationTransition(.zoom(sourceID: MiniPlayer.zoomId, in: playerZoom))
        }
        .modifier(DialogSheets(layer: .root))
        // 共有の Nav で選ばれた行き先を、今のタブに積む
        .onChange(of: AppServices.nav.current) { _, entry in shell.open(entry.route) }
        .onChange(of: player.message) { _, message in
            guard let message else { return }
            AppServices.toast.show(message)
            player.message = nil
        }
        .onChange(of: player.current?.videoId) { _, _ in AppServices.prefetchMembership() }
        // ウィジェット・ロック画面の再生中から開いたとき
        .onOpenURL { url in
            guard url.scheme == "wave" else { return }
            switch url.host {
            case "nowplaying": if player.current != nil { shell.playerOpen = true }
            case "add-to-playlist": if let song = player.current, !Feed.isExternal(song.videoId) { Dialogs.shared.addToPlaylist(song) }
            default: break
            }
        }
        // ログインしていなければ、まずログインの画面（Android と同じく、ログインしないと曲が鳴らない）
        // 設定の「もう一度ログイン」でも開く（ログアウトはしない。Android・Mac・Windows と同じ）
        .fullScreenCover(isPresented: Binding(get: { (!account.isLoggedIn && !LoginGate.shared.skipped) || LoginGate.shared.reLogin },
                                              set: { if !$0 { LoginGate.shared.reLogin = false } })) {
            LoginView()
        }
    }

    /// 右に離すタブを最後にした並び（ほかはホーム・検索・Claude・ライブラリの順のまま）。
    private var tabOrder: [IOSTab] {
        [IOSTab.home, .search, .ai, .library].filter { $0 != shell.pinnedTab } + [shell.pinnedTab]
    }

    private func stack(_ tab: IOSTab, @ViewBuilder root: () -> some View) -> some View {
        NavigationStack(path: shell.path(tab)) {
            root().navigationDestination(for: Route.self) { route in RouteView(route: route) }
        }
    }
}

extension IOSTab {
    var title: String {
        switch self {
        case .home: L.t("ホーム", "Home")
        case .search: L.t("検索", "Search")
        case .ai: "Claude"
        case .library: L.t("ライブラリ", "Library")
        }
    }

    var symbol: String {
        switch self {
        case .home: "house.fill"
        case .search: "magnifyingglass"
        case .ai: "sparkles"
        case .library: "books.vertical.fill"
        }
    }
}

/// タブの一番下の画面。
private struct TabRoot: View {
    let tab: IOSTab

    var body: some View {
        switch tab {
        case .home: HomeView()
        case .search: SearchView()
        case .ai: AiTabView()
        case .library: LibraryView()
        }
    }
}

/// 画面の重なり（下から 画面の枠・再生中の画面・その上のキューか歌詞）。
enum SheetLayer {
    case root, player, queue, lyrics
}

/// プレイリストに追加する・作る画面を、指定の重なりが一番上のときだけ、その重なりから出す。
struct DialogSheets: ViewModifier {
    let layer: SheetLayer
    @State private var dialogs = Dialogs.shared
    @State private var shell = IOSShell.shared

    func body(content: Content) -> some View {
        let top = shell.topLayer == layer
        content
            .sheet(item: Binding(get: { top ? dialogs.addToPlaylist : nil }, set: { dialogs.addToPlaylist = $0 })) { song in
                AddToPlaylistSheet(song: song)
            }
            .sheet(isPresented: Binding(get: { top && dialogs.creating }, set: { dialogs.creating = $0 })) {
                CreatePlaylistSheet(song: dialogs.createSong)
            }
    }
}

/// ログインの画面を閉じて、ログインせずに使う（検索や歌詞は見られる。曲は鳴らない）。
@MainActor @Observable
final class LoginGate {
    static let shared = LoginGate()
    var skipped = false
    /// ログインしたまま、ログインの画面を開いている（設定の「もう一度ログイン」）。ログインし終えるか閉じたら false。
    var reLogin = false
}

/// 積んだ画面の中身（共有の Route ごと）。
struct RouteView: View {
    let route: Route

    var body: some View {
        switch route {
        case .home: HomeView()
        case .search(let query): SearchView(initialQuery: query)
        case .collection(let request): CollectionView(request: request)
        case .artist(let id): ArtistView(browseId: id)
        case .podcast(let podcast): PodcastView(summary: podcast)
        case .song(let song): SongView(song: song)
        case .mood(let mood): MoodView(mood: mood)
        case .history: HistoryView()
        case .settings: SettingsView()
        case .devices: DevicesView()
        case .lyrics: LyricsView()
        }
    }
}

/// ミニプレーヤーを、iOS 26 以降はタブバーの上の付属部品（Liquid Glass）として、それより前は下に重ねて出す。
private struct MiniPlayerAccessory: ViewModifier {
    var show: Bool
    var zoom: Namespace.ID
    var open: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *), show {
            content
                .tabViewBottomAccessory { MiniPlayer(open: open).matchedTransitionSource(id: MiniPlayer.zoomId, in: zoom) }
                .tabBarMinimizeBehavior(.onScrollDown)
        } else if show {
            content.safeAreaInset(edge: .bottom) {
                MiniPlayer(open: open)
                    .matchedTransitionSource(id: MiniPlayer.zoomId, in: zoom)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal, 8)
                    .padding(.bottom, 52)
            }
        } else {
            content
        }
    }
}

/// 画面の下の知らせ（Android のスナックバー）。
struct ToastHost: View {
    @State private var toast = AppServices.toast!

    var body: some View {
        if let text = toast.text {
            Text(text)
                .font(.subheadline)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .modifier(GlassCard(radius: 14))
                .padding(.horizontal, 12)
                .onTapGesture { toast.close() }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .animation(.snappy, value: toast.text)
        }
    }
}
#endif
