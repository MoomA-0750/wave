#if os(iOS)
import SwiftUI
import UIKit
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
    /// ミニプレーヤーの枠を自前で描くか（iOS 26 から。切ると OS の付属部品に戻る。Safari のように枠ごと入れ替わる試し）。
    var miniCustom = AppServices.settings.miniPlayerCustom
    /// この時刻まで、小窓（PiP）の「元に戻す」で再生画面を開かない（ロック画面の再生中から追加画面を開いたとき、再生画面が重なって追加画面が一度消えるため）。
    var holdPlayerUntil = Date.distantPast
    /// 小窓の「元に戻す」で再生画面を開く（直前にロック画面から追加画面を開いていたときは開かない）。
    func restorePlayerFromPip() { if Date() >= holdPlayerUntil { playerOpen = true } }
    /// 再生画面のジャケットを左右に払っている間か（その間は、払いを「再生画面をしまう」動きに取られないよう止める）。
    var coverSwiping = false
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
            // 設定で選んだタブは、右に離して置く役（pinnedRole）にする。欄を下へ送って畳んでも、
            // 左の再生中のタブと並んで右に残る
            // （ForEach で並べると OS が役を見ず、欄の中に並べてしまったので、1つずつ書く）
            tab(.home)
            if shell.pinnedTab != .search { tab(.search) }
            if shell.pinnedTab != .ai { tab(.ai) }
            if shell.pinnedTab != .library { tab(.library) }
            tab(shell.pinnedTab, role: pinnedRole)
        }
        .modifier(MiniPlayerAccessory(show: player.current != nil, zoom: playerZoom) { shell.playerOpen = true })
        // 音楽の PiP の元の枠（画面の後ろに小さく置く）
        .background(alignment: .bottomLeading) { MusicPipAnchor().frame(width: 16, height: 9) }
        // 本体の音量を動かすための見えない部品（再生画面を開いていない間・裏にいる間も、ほかの端末から届いた音量を本体へ入れる）
        .background { SystemVolumeAnchor().frame(width: 1, height: 1).offset(x: -2000).allowsHitTesting(false) }
        .overlay(alignment: .bottom) { ToastHost().padding(.bottom, player.current != nil ? (shell.miniCustom ? 114 : 120) : 64) }
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
            case "add-to-playlist":
                if let song = player.current, !Feed.isExternal(song.videoId) {
                    shell.holdPlayerUntil = Date().addingTimeInterval(3)
                    Dialogs.shared.addToPlaylist(song)
                }
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

    /// 右に離して置く役。iOS 26 は検索の役（.search）だけが離れるが、iOS 27 では検索の役も欄の中に並び、離すのは .prominent になった
    private var pinnedRole: TabRole {
        if #available(iOS 27.0, *) { return .prominent }
        return .search
    }

    private func tab(_ tab: IOSTab, role: TabRole? = nil) -> some TabContent<IOSTab> {
        Tab(tab.title, systemImage: tab.symbol, value: tab, role: role) {
            stack(tab) { TabRoot(tab: tab) }
        }
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
                CreatePlaylistSheet(song: dialogs.createSong, songs: dialogs.createQueue)
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
        case .downloads: DownloadsView()
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
    @State private var shell = IOSShell.shared
    @State private var compact = false
    var open: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *), show, shell.miniCustom {
            // 枠を自前で描き、タブバーの上に置く（OS の付属部品ではないので、スクロールで畳む動きは無い）
            // 畳むのはタブバーと同じ決まり: 下へ送り始めると畳み、タブバーの丸をタップするか、一番上に戻るか、タブを替えるまで広げない
            content
                .tabBarMinimizeBehavior(.onScrollDown) // タブバーが畳まれるのに合わせて、ミニプレーヤーも一列に畳む
                .safeAreaInset(edge: .bottom) { Color.clear.frame(height: compact ? 0 : 60).allowsHitTesting(false) }
                // 画面の中身にだけ付ける（ミニプレーヤー自身の上を払って再生画面を開く動きでは畳まない）。スクロールの邪魔をしないよう同時に受ける
                .simultaneousGesture(DragGesture(minimumDistance: 24).onChanged { v in
                    guard !compact, v.translation.height < 0, abs(v.translation.height) > abs(v.translation.width),
                          let sv = ScrollProbe.main(), !ScrollProbe.atTop(sv, slack: 4) else { return }
                    compact = true
                })
                .simultaneousGesture(SpatialTapGesture().onEnded { v in
                    // 畳んだタブバー（左下の丸）のタップで広げる
                    if compact, v.location.x < 90, v.location.y > UIScreen.main.bounds.height - 120 { compact = false }
                })
                .onChange(of: shell.tab) { compact = false }
                .task(id: compact) {
                    // 畳んでいる間、一番上に戻ったら広げる（慣性で戻る場合も拾うため、短い間隔で見る）
                    while compact, !Task.isCancelled {
                        try? await Task.sleep(for: .milliseconds(150))
                        if let sv = ScrollProbe.main(), ScrollProbe.atTop(sv, slack: 2) { compact = false }
                    }
                }
                .overlay(alignment: .bottom) {
                    MiniPlayerCard(zoom: zoom, open: open, compact: compact)
                        .padding(.leading, Kasane.Space.s3 + (compact ? 70 : 0)) // 畳んだタブバー（左端の丸）の右隣へ
                        .padding(.trailing, Kasane.Space.s3 + (compact ? 56 : 0)) // 右の丸（Claude）を避ける
                        .padding(.bottom, compact ? -6 : 56) // 下の安全な余白からの位置。タブバーの上に 8pt あける。畳むと丸と中心をそろえる
                        .animation(.snappy, value: compact)
                }
        } else if #available(iOS 26.0, *), show {
            content
                .tabViewBottomAccessory { MiniPlayer(open: open).matchedTransitionSource(id: MiniPlayer.zoomId, in: zoom) }
                .tabBarMinimizeBehavior(.onScrollDown)
        } else if show {
            content.safeAreaInset(edge: .bottom) {
                MiniPlayer(open: open)
                    .matchedTransitionSource(id: MiniPlayer.zoomId, in: zoom)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Kasane.Radius.lg, style: Kasane.cornerStyle)) // apple-ui-allow: Liquid Glass（26）より前の代わりのすりガラス
                    .padding(.horizontal, Kasane.Space.s2)
                    .padding(.bottom, 52)
            }
        } else {
            content
        }
    }
}

/// 今見えている縦のスクロール面（UIScrollView）を探す。畳んだタブバーが広がる条件（一番上に戻る）を、自前の枠でも同じにするため。
@MainActor
enum ScrollProbe {
    static func main() -> UIScrollView? {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let window = scene.windows.first(where: \.isKeyWindow) ?? scene.windows.first else { return nil }
        var best: (UIScrollView, CGFloat)?
        var stack: [UIView] = [window]
        while let v = stack.popLast() {
            if v.isHidden || v.alpha < 0.01 { continue }
            if let sv = v as? UIScrollView, sv.window != nil, sv.contentSize.height > sv.bounds.height + 1,
               sv.bounds.width > window.bounds.width * 0.7, sv.bounds.height > 240 {
                let area = sv.bounds.width * sv.bounds.height
                if area > (best?.1 ?? 0) { best = (sv, area) }
            }
            stack.append(contentsOf: v.subviews)
        }
        return best?.0
    }

    static func atTop(_ sv: UIScrollView, slack: CGFloat) -> Bool {
        sv.contentOffset.y <= -sv.adjustedContentInset.top + slack
    }
}

/// 画面の下の知らせ（Android のスナックバー）。
struct ToastHost: View {
    @State private var toast = AppServices.toast!

    var body: some View {
        if let text = toast.text {
            Text(text)
                .font(.subheadline)
                .padding(.horizontal, Kasane.Space.s4)
                .padding(.vertical, Kasane.Space.s3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .modifier(GlassCard(radius: Kasane.Radius.lg))
                .padding(.horizontal, Kasane.Space.s3)
                .onTapGesture { toast.close() }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .animation(.snappy, value: toast.text)
        }
    }
}
#endif
