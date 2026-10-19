import Foundation
import WaveCore

/// アーティストのページの状態（Mac・iPhone で同じ読み方。Android の ArtistScreen と同じ並び）。
@MainActor @Observable
final class ArtistModel {
    let browseId: String
    var artist: ArtistPage?
    /// アルバム・シングルなどの棚（最近の新作を先頭に、動画を最後に）。
    var shelves: [HomeShelf] = []
    var posts: [ArtistPost] = []
    var loading = true
    var failed = false
    /// 一覧の「シャッフル再生」の指定。このアーティストを流しているときは、キューのシャッフルの入り切りになる。
    var shufflePlay = false
    var subscribing = false
    var liked: (count: Int, complete: Bool)?
    var checkingLiked = false
    /// 「すべての曲」の一覧（ページを開いたときに読み始める。再生のたびに読み直さない）。
    @ObservationIgnored private var allSongs: Task<ApiResult<PlaylistPage>?, Never>?

    init(browseId: String) {
        self.browseId = browseId
    }

    var topSongs: [Song] { artist?.topSongs ?? [] }

    var isPlayingHere: Bool { AppServices.player.sourceId == browseId && AppServices.player.current != nil }

    func likedText(_ name: String) -> String {
        guard let liked else { return L.t("確かめています…", "Checking…") }
        let n = liked.count
        return liked.complete ? L.t("\(name) の \(n) 曲", "\(n) \(n == 1 ? "song" : "songs") by \(name)") : L.t("\(name) の \(n) 曲以上", "\(n)+ songs by \(name)")
    }

    func load() async {
        failed = false
        let result = (try? await AppServices.music.artist(browseId)) ?? .failed(.network)
        loading = false
        guard let page = result.value else {
            failed = artist == nil
            return
        }
        artist = page
        if let id = page.allSongsPlaylistId { allSongs = Task { try? await AppServices.music.fullPlaylist(id) } }
        var list = page.albumShelves.map { HomeShelf(title: $0.title, strapline: nil, items: $0.albums.map { .album($0) }) }
        // 最近の新作（登録したアーティストの見張りで見つけたもの）を先頭に（Android と同じ）。今のページから消えたもの（同じ名前の別人の作品だった）は出さない
        let releases = ReleaseWatchState.listed(AppServices.releases.recent.filter { $0.artistId == browseId }, artistId: browseId, page: page,
                                                year: Calendar.current.component(.year, from: Date())).map(HomeModel.releaseItem)
        if !releases.isEmpty { list.insert(HomeShelf(title: L.t("新作", "New releases"), strapline: nil, items: releases), at: 0) }
        // 動画の棚（MV・YouTube の動画。Android と同じ）
        if !page.videos.isEmpty { list.append(HomeShelf(title: L.t("動画", "Videos"), strapline: nil, items: page.videos.map { .song($0) })) }
        shelves = list
        Task { await loadPosts(page) }
        await loadLiked(page)
    }

    /// チャンネルの投稿のうち、ライブ・ツアーの告知らしいものを並べる（押すと YouTube の投稿を開く）。
    private func loadPosts(_ page: ArtistPage) async {
        guard let channelId = page.channelId ?? (browseId.hasPrefix("UC") ? browseId : nil) else { return }
        let result = try? await AppServices.music.artistPosts(channelId)
        let found = Array(LiveNews.of(result?.value ?? []).prefix(6))
        AppPaths.log("artist posts ok=\(result?.isOk ?? false) posts=\(result?.value?.count ?? 0) live=\(found.count)")
        posts = found
    }

    /// このアーティストの、自分が高く評価した曲の数（ログインしていなければ出さない）。
    private func loadLiked(_ page: ArtistPage) async {
        guard AppServices.client.isLoggedIn else { return }
        checkingLiked = true
        let (songs, complete) = await AppServices.library.likedSongs()
        liked = (LibraryState.byArtist(songs, browseId, page.name).count, complete)
        checkingLiked = false
    }

    /// 登録の切り替え。書けたら表示を変える（Android と同じく楽観的に変えない）。
    func toggleSubscribe() {
        guard let page = artist, let channelId = page.channelId, !subscribing else { return }
        let target = page.subscribed != true
        subscribing = true
        Task {
            let result = (try? await AppServices.music.setSubscribed(channelId, subscribe: target)) ?? .failed(.network)
            subscribing = false
            if result.isOk {
                artist?.subscribed = target
            } else {
                AppServices.player.message = L.t("登録を変更できませんでした", "Couldn't change your subscription")
            }
        }
    }

    /// アーティストを流す: 人気の曲からすぐ鳴らし始め、「すべての曲」の一覧が読めたら残りを後ろに足す（Android と同じ）。
    /// 人気の曲だけだと、終わったところで最後の曲のラジオに切り替わり、ほかのアーティストの曲が混ざっていた。
    func play(start: Int, shuffle: Bool?) {
        let all = allSongs
        let songs = topSongs
        Task { await AppServices.player.playArtist(browseId, songs, start: start, shuffle: shuffle, allSongs: all) }
    }

    /// 再生ボタン: このアーティストを流していれば一時停止・再開、ほかは頭から（シャッフル再生の指定に従う）。
    func playOrPause() {
        if isPlayingHere { AppServices.player.togglePlay() } else { play(start: 0, shuffle: shufflePlay ? true : nil) }
    }

    /// シャッフル: このアーティストを流していればキューのシャッフル、ほかはシャッフル再生の指定。
    func toggleShuffle() {
        if isPlayingHere { AppServices.player.toggleShuffle() } else { shufflePlay.toggle() }
    }
}
