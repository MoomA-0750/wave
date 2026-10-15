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

/// マイライブラリの1行。
struct LibraryEntry: Identifiable, Hashable {
    var title: String
    var subtitle: String?
    var thumbnailUrl: String?
    var item: HomeItem?
    var isLiked = false
    /// 保存したポッドキャストの番組。
    var podcast: PodcastSummary?
    /// 「新しいエピソード」（保存した番組の新着の自動のプレイリスト。ポッドキャストで絞ったときだけ先頭に出す）。
    var isNewEpisodes = false
    var id: String { item?.key ?? podcast.map { "pod-" + $0.browseId } ?? (isNewEpisodes ? "new-episodes" : "liked") }
    var isArtist: Bool { item?.isArtist ?? false }
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
    /// 全画面で、映像だけを画面いっぱいに出しているか（曲名・歌詞・下の操作の帯も隠す）。
    var videoOnly = false

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
    var libraryWidth: Double { windowWidth < 1000 ? 240 : 300 }
    var searchWidth: Double { windowWidth < 1100 ? 300 : 440 }
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
        if !on { videoOnly = false }
        // 窓も全画面にする（Windows の FullScreen の表示と同じ）
        if let window = NSApp.keyWindow ?? NSApp.mainWindow, window.styleMask.contains(.fullScreen) != on {
            window.toggleFullScreen(nil)
        }
    }

    /// 映像を大きく見る（右の欄・メニューバーの小窓の映像の「拡大」）。全画面の再生中の画面で映像を出す。
    func expandVideo() {
        showVideo = true
        MenuBarController.shared.close()
        MenuBarController.showMainWindow()
        setFullScreen(true)
    }

    /// 全画面で、映像だけを画面いっぱいに出す・やめる。
    func toggleVideoOnly() {
        videoOnly.toggle()
        if videoOnly && !fullScreen { setFullScreen(true) }
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
        let lib = AppServices.library!
        let filter = libraryFilter.trimmingCharacters(in: .whitespaces)
        var entries: [LibraryEntry] = []
        if libraryKind == nil || libraryKind == "playlist" {
            entries += lib.playlists.map {
                LibraryEntry(title: $0.title, subtitle: L.t("プレイリスト", "Playlist") + (($0.subtitle ?? "").isEmpty ? "" : " • " + $0.subtitle!),
                             thumbnailUrl: $0.thumbnailUrl, item: .playlist($0))
            }
        }
        if libraryKind == nil || libraryKind == "artist" {
            entries += lib.artists.map { LibraryEntry(title: $0.name, subtitle: L.t("アーティスト", "Artist"), thumbnailUrl: $0.thumbnailUrl, item: .artist($0)) }
        }
        if libraryKind == nil || libraryKind == "album" {
            entries += lib.albums.map {
                LibraryEntry(title: $0.title, subtitle: L.t("アルバム", "Album") + (($0.subtitle ?? "").isEmpty ? "" : " • " + $0.subtitle!),
                             thumbnailUrl: $0.thumbnailUrl, item: .album($0))
            }
        }
        if libraryKind == nil || libraryKind == "podcast" {
            entries += lib.allPodcasts.map {
                LibraryEntry(title: $0.title, subtitle: L.t("ポッドキャスト", "Podcast") + (($0.author ?? "").isEmpty ? "" : " • " + $0.author!),
                             thumbnailUrl: $0.thumbnailUrl, podcast: $0)
            }
        }
        if !filter.isEmpty { entries = entries.filter { $0.title.localizedCaseInsensitiveContains(filter) } }
        let sort = AppServices.settings.librarySort
        if sort == "alpha" {
            entries.sort { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        } else if sort == "recent" {
            // 最近再生したものを先に、残りは追加した順のまま（Android と同じ）
            var rank: [String: Int] = [:]
            for (i, key) in lib.recentKeys.enumerated() where rank[key] == nil { rank[key] = i }
            entries = entries.enumerated().sorted { a, b in
                let ra = a.element.item.flatMap(LibraryState.keyOf).flatMap { rank[$0] } ?? Int.max
                let rb = b.element.item.flatMap(LibraryState.keyOf).flatMap { rank[$0] } ?? Int.max
                return ra != rb ? ra < rb : a.offset < b.offset
            }.map(\.element)
        }
        // 高く評価した曲はいつも先頭（Spotify の「お気に入りの曲」と同じ）
        let likedTitle = L.t("高く評価した曲", "Liked Songs")
        if (libraryKind == nil || libraryKind == "playlist") && (filter.isEmpty || likedTitle.contains(filter)) {
            entries.insert(LibraryEntry(title: likedTitle, subtitle: L.t("プレイリスト • 自動", "Playlist • Auto"), thumbnailUrl: lib.likedThumbnail, isLiked: true), at: 0)
        }
        // ポッドキャストで絞ったときは「新しいエピソード」を先頭に（高く評価した曲と同じく留める。Android と同じ）
        let newEpisodesTitle = L.t("新しいエピソード", "New episodes")
        if libraryKind == "podcast" && (filter.isEmpty || newEpisodesTitle.localizedCaseInsensitiveContains(filter)) {
            entries.insert(LibraryEntry(title: newEpisodesTitle, subtitle: L.t("プレイリスト • 自動", "Playlist • Auto"), isNewEpisodes: true), at: 0)
        }
        return entries
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
