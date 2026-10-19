#if os(macOS)
import AppKit
import Foundation
import Observation
import WaveCore

/// 検索欄の候補（語・曲・アルバム・アーティスト）。
struct Suggestion: Identifiable, Hashable {
    let id = UUID()
    var title: String
    var subtitle: String?
    var thumbnailUrl: String?
    var query: String?
    var item: HomeItem?
    var isQuery: Bool { item == nil }
    var round: Bool { item?.isArtist ?? false }
}

/// キューの1行。section はかたまりの最初の行にだけ付く見出し（「再生中」「キューの次の曲」「次に流れる曲」）。
struct QueueEntry: Identifiable, Hashable {
    var index: Int
    var song: Song
    var isCurrent: Bool
    var section: String?
    var id: Int { index }
}

/// 右の欄に出すもの。
enum RightView { case nowPlaying, queue, ai }

/// 主画面の状態（Windows の MainWindow の分。並び・右の欄・全画面・検索欄・マイライブラリの絞り込み）。
@MainActor @Observable
final class MainWindowModel {
    static let shared = MainWindowModel()

    var windowWidth: Double = 1480
    var rightPaneOpen: Bool
    var rightView: RightView = .nowPlaying
    /// キューの欄で履歴を出しているか。
    var queueHistory = false
    var fullScreen = false
    /// 「曲｜動画」の動画を選んでいるか。
    var showVideo = false
    /// 全画面の再生中の画面で、映像だけを窓いっぱいに出しているか（曲名・歌詞・下の操作の帯も隠す）。
    var videoOnly = false
    /// 映像だけの表示を、右の欄・小窓の「拡大」から直に開いたか（やめると元の画面に戻す）。
    @ObservationIgnored private var videoOnlyFromExpand = false
    /// 窓が macOS の全画面になっているか。
    var windowFullScreen = false
    /// 映像の小窓（PiP）を出しているか。
    var pipOpen = false

    // 検索欄
    var searchText = ""
    var suggestions: [Suggestion] = []
    var searchFocused = false
    @ObservationIgnored private var suggestTask: Task<Void, Never>?

    // マイライブラリ
    var libraryKind: String?
    var libraryFilter = ""

    /// 上のボタンで Claude を閉じたときに戻す、開く前の右の欄（閉じていた・再生中の画面・キュー）。
    @ObservationIgnored private var beforeAi: (open: Bool, queue: Bool) = (false, false)

    private init() {
        rightPaneOpen = AppServices.settings.rightPaneOpen
        // 今の曲を足したり外したりしたら、評価のボタンの✓を付け直す
        AppServices.library.membership.nowPlayingChanged = {
            let player = AppServices.player!
            player.inPlaylist = AppServices.library.membership.isSavedNowPlaying(player.current?.videoId)
        }
    }

    // MARK: 画面の広さに合わせた並び

    /// Claude は上のボタンから呼ぶので、少し狭い窓でも右の欄に出す（再生中の画面・キューは広いときだけ）。
    var showRight: Bool { rightPaneOpen && (windowWidth >= 1180 || (rightView == .ai && windowWidth >= 880)) }
    /// 上の帯の検索の欄の幅。帯の項目は真ん中と右の欄を合わせた幅の中央に並ぶので、右の欄を開いているときは短くして、右の欄の境にかからないようにする。
    var searchWidth: Double { windowWidth < (showRight ? 1600 : 1100) ? 300 : 440 }
    var paneToggleOn: Bool { showRight && rightView == .nowPlaying }
    var queueToggleOn: Bool { showRight && rightView == .queue }
    var aiToggleOn: Bool { showRight && rightView == .ai }

    func setRightPane(open: Bool, queue: Bool) {
        rightPaneOpen = open
        AppServices.settings.rightPaneOpen = open
        AppServices.settings.save()
        rightView = queue ? .queue : .nowPlaying
    }

    func toggleQueue() {
        let showing = rightPaneOpen && rightView == .queue
        setRightPane(open: !showing, queue: !showing)
    }

    func togglePane() {
        let showing = rightPaneOpen && rightView == .nowPlaying
        setRightPane(open: !showing, queue: false)
    }

    func showQueue(history: Bool = false) {
        queueHistory = history
        setRightPane(open: true, queue: true)
    }

    /// 上のバーの Claude のボタン（⌘J）。開いていれば、開く前の右の欄に戻す。
    func toggleAi() {
        if rightPaneOpen && rightView == .ai {
            setRightPane(open: beforeAi.open, queue: beforeAi.queue)
            return
        }
        showAi()
    }

    func showAi() {
        if !(rightPaneOpen && rightView == .ai) { beforeAi = (rightPaneOpen, rightView == .queue) }
        setRightPane(open: true, queue: false)
        rightView = .ai
    }

    // MARK: 全画面

    func setFullScreen(_ on: Bool) {
        fullScreen = on
        if !on {
            videoOnly = false
            videoOnlyFromExpand = false
        }
        // 窓も全画面にする（Windows の FullScreen の表示と同じ）
        setWindowFullScreen(on)
    }

    /// 窓を macOS の全画面にする・やめる（中の表示は変えない）。
    func setWindowFullScreen(_ on: Bool) {
        if let window = Self.mainWindow, window.styleMask.contains(.fullScreen) != on {
            window.toggleFullScreen(nil)
        }
        windowFullScreen = on
    }

    /// 主の窓（メニューバーの小窓や映像の小窓が前にあっても、主の窓を全画面にする）。
    static var mainWindow: NSWindow? {
        NSApp.windows.first { $0.identifier?.rawValue.hasPrefix("main") == true }
            ?? NSApp.windows.first { $0.canBecomeMain && $0.identifier?.rawValue != "login" }
    }

    /// 映像を大きく見る（右の欄・メニューバーの小窓の映像の「拡大」）。macOS の全画面にはせず、窓の中で映像だけをいっぱいに出す。
    /// 映像の右上のボタンで、そこから macOS の全画面にできる。
    func expandVideo() {
        showVideo = true
        MenuBarController.shared.close()
        MenuBarController.showMainWindow()
        fullScreen = true
        videoOnly = true
        videoOnlyFromExpand = true
    }

    /// 映像だけを窓いっぱいに出す・やめる。「拡大」から開いたときは、やめると元の画面（と元の窓の大きさ）に戻す。
    func toggleVideoOnly() {
        if videoOnly {
            videoOnly = false
            if videoOnlyFromExpand { setFullScreen(false) }
        } else {
            videoOnly = true
            fullScreen = true
        }
    }

    /// 全画面からページを開くときは、全画面を閉じてから開く。
    func openFromFull(_ open: () -> Void) {
        if fullScreen { setFullScreen(false) }
        open()
    }

    // MARK: 検索欄

    func searchTextChanged(_ text: String) {
        suggestTask?.cancel()
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            suggestions = []
            if case .search = AppServices.nav.current.route { SearchPageModel.current?.search(nil) }
            return
        }
        suggestTask = Task {
            try? await Task.sleep(for: .milliseconds(150))
            guard !Task.isCancelled, let s = (try? await AppServices.music.suggestions(trimmed))?.value, !Task.isCancelled else { return }
            var list: [Suggestion] = s.queries.prefix(5).map { Suggestion(title: $0, query: $0) }
            list += s.artists.map { Suggestion(title: $0.name, subtitle: L.t("アーティスト", "Artist"), thumbnailUrl: $0.thumbnailUrl, item: .artist($0)) }
            list += s.songs.map { Suggestion(title: $0.title, subtitle: L.t("曲 • ", "Song • ") + $0.artistsText, thumbnailUrl: $0.thumbnailUrl, item: .song($0)) }
            list += s.albums.map { Suggestion(title: $0.title, subtitle: $0.subtitle, thumbnailUrl: $0.thumbnailUrl, item: .album($0)) }
            suggestions = list
        }
    }

    /// 検索欄に入ったら「すべてをブラウズ」を出す（Spotify と同じ）。
    func searchFocusChanged(_ focused: Bool) {
        searchFocused = focused
        if focused, searchText.isEmpty {
            if case .search = AppServices.nav.current.route {} else { AppServices.nav.search() }
        }
    }

    func choose(_ suggestion: Suggestion) {
        suggestTask?.cancel()
        suggestions = []
        if let item = suggestion.item {
            AppServices.settings.addSearch(.of(item))
            AppServices.nav.open(item)
            return
        }
        searchFor(suggestion.query ?? suggestion.title)
    }

    /// 語で検索する（検索のページにいればその場で、いなければ開いて）。
    func searchFor(_ query: String) {
        suggestTask?.cancel()
        let q = query.trimmingCharacters(in: .whitespaces)
        searchText = q
        suggestions = []
        if !q.isEmpty { AppServices.settings.addSearch(.query(q)) }
        if case .search = AppServices.nav.current.route, let page = SearchPageModel.current { page.search(q) } else { AppServices.nav.search(q) }
    }

    // MARK: マイライブラリ

    func libraryEntries() -> [LibraryEntry] {
        LibraryEntry.list(AppServices.library, kind: libraryKind, filter: libraryFilter, sort: AppServices.settings.librarySort, matchSubtitle: false)
    }

    // MARK: 再生中の曲

    func prefetchMembership() { AppServices.prefetchMembership() }

    /// キュー（Spotify と同じ）: 一番上に今の曲、その下に自分で足した曲、その下に元から流れる予定の曲。もう流れた曲は「履歴」に分ける。
    func queueEntries() -> [QueueEntry] {
        let player = AppServices.player!
        var entries: [QueueEntry] = []
        if queueHistory {
            // 新しい順。押すとその曲に戻る
            var i = min(player.index, player.queue.count) - 1
            while i >= 0 {
                entries.append(QueueEntry(index: i, song: player.queue[i], isCurrent: false))
                i -= 1
            }
        } else {
            let userEnd = player.userQueueEnd
            var i = max(0, player.index)
            while i < player.queue.count {
                let section: String? = i == player.index ? L.t("再生中", "Now playing")
                    : i == player.index + 1 && i < userEnd ? L.t("キューの次の曲", "Next in queue")
                    : i == userEnd ? L.t("次に流れる曲", "Next up")
                    : nil
                entries.append(QueueEntry(index: i, song: player.queue[i], isCurrent: i == player.index, section: section))
                i += 1
            }
        }
        return entries
    }
}
#endif
