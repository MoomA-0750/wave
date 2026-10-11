#if os(iOS)
import SwiftUI
import WaveCore

/// アーティストのページ（Android の ArtistScreen と同じ並び: 大きな写真と名前、登録・シャッフル・再生、新作、ライブの投稿、人気の曲、
/// すべての曲、高く評価した曲、アルバム・シングルなどの棚、動画）。
struct ArtistView: View {
    var browseId: String
    @State private var artist: ArtistPage?
    @State private var shelves: [(title: String, items: [HomeItem])] = []
    @State private var posts: [ArtistPost] = []
    @State private var loading = true
    @State private var failed = false
    @State private var shufflePlay = false
    @State private var subscribing = false
    @State private var liked: (count: Int, complete: Bool)?
    @State private var checkingLiked = false
    /// 「すべての曲」の一覧（ページを開いたときに読み始める。再生のたびに読み直さない）。
    @State private var allSongs: Task<ApiResult<PlaylistPage>?, Never>?

    var body: some View {
        let player = AppServices.player!
        let playingHere = player.sourceId == browseId && player.current != nil
        let topSongs = artist?.topSongs ?? []
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
                HStack(spacing: 16) {
                    if artist?.channelId != nil && AppServices.client.isLoggedIn { subscribeButton }
                    if loading { ProgressView() }
                    Spacer()
                    Button {
                        if playingHere { player.toggleShuffle() } else { shufflePlay.toggle() }
                    } label: {
                        Image(systemName: "shuffle").font(.system(size: 20, weight: .semibold))
                            .foregroundStyle((playingHere ? player.shuffle : shufflePlay) ? Color.accentColor : Color.secondary)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L.t("シャッフル", "Shuffle"))
                    ArtistViewPlayPause(playing: playingHere && player.isPlaying) {
                        if playingHere { player.togglePlay() } else { play(start: 0, shuffle: shufflePlay ? true : nil) }
                    }
                    .disabled(topSongs.isEmpty)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                if failed {
                    LoadState(loading: false, failure: L.t("アーティストを読み込めませんでした", "Couldn't load the artist")) {
                        Task { await load() }
                    }
                }
                // ライブ・ツアーの告知（アーティストの YouTube のチャンネルの投稿から。Android と同じ）。告知が無ければ出さない
                if !posts.isEmpty {
                    ArtistViewSectionTitle(text: L.t("ライブ・お知らせ", "Live & news"))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 12) { ForEach(posts, id: \.postId) { ArtistViewPostCard(post: $0) } }
                            .padding(.horizontal, 16)
                    }
                }
                if !topSongs.isEmpty {
                    ArtistViewSectionTitle(text: L.t("人気", "Popular"))
                    LazyVStack(spacing: 0) {
                        ForEach(Array(topSongs.enumerated()), id: \.offset) { i, song in
                            SongRow(song: song) { play(start: i, shuffle: nil) }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 6)
                        }
                    }
                }
                if let id = artist?.allSongsPlaylistId, let artist {
                    Button(L.t("すべての曲を見る", "See all songs")) {
                        AppServices.nav.playlist(PlaylistSummary(playlistId: id, title: artist.name + L.t(" のすべての曲", " — all songs"), subtitle: nil, thumbnailUrl: artist.thumbnailUrl))
                    }
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
                // Spotify の「高く評価した曲」の行（このアーティストの、自分が高く評価した曲）。1曲も無ければ出さない
                if checkingLiked || (liked?.count ?? 0) > 0, let artist {
                    Button { AppServices.nav.likedSongsBy(browseId, artist.name) } label: {
                        HStack(spacing: 14) {
                            ZStack {
                                Circle().fill(Ui.likedGradient)
                                Image(systemName: "heart.fill").foregroundStyle(.white)
                            }
                            .frame(width: 56, height: 56)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L.t("高く評価した曲", "Liked Songs")).font(.body.weight(.semibold))
                                Text(likedText(artist.name)).subtle()
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(checkingLiked)
                }
                ForEach(Array(shelves.enumerated()), id: \.offset) { _, shelf in
                    ArtistViewSectionTitle(text: shelf.title)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 12) {
                            ForEach(shelf.items, id: \.key) { ArtistViewCard(item: $0) }
                        }
                        .padding(.horizontal, 16)
                    }
                }
            }
            .padding(.bottom, 32)
        }
        .ignoresSafeArea(edges: .top)
        .navigationTitle(artist?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .refreshable { await load() }
        .task { if artist == nil { await load() } }
    }

    // 横いっぱいの写真に名前を重ねる（下を暗くして白い文字を読めるようにする）
    private var hero: some View {
        Color.clear
            .frame(height: 380)
            .overlay { RemoteImage(url: Ui.resize(artist?.thumbnailUrl, 1080)) }
            .overlay {
                LinearGradient(stops: [.init(color: .clear, location: 0.4), .init(color: .black.opacity(0.7), location: 1)], startPoint: .top, endPoint: .bottom)
            }
            .overlay(alignment: .bottomLeading) {
                Text(artist?.name ?? "")
                    .font(.system(size: 40, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.5)
                    .padding(16)
            }
            .clipped()
            .background(Color.primary.opacity(0.08))
    }

    private var subscribeButton: some View {
        let subscribed = artist?.subscribed == true
        return Button(subscribed ? L.t("登録済み", "Subscribed") : L.t("登録", "Subscribe")) { toggleSubscribe() }
            .font(.subheadline.weight(.semibold))
            .buttonStyle(.borderedProminent)
            .tint(subscribed ? Color.gray.opacity(0.4) : Color.accentColor)
            .buttonBorderShape(.capsule)
            .disabled(subscribing || artist?.subscribed == nil)
    }

    private func likedText(_ name: String) -> String {
        guard let liked else { return L.t("確かめています…", "Checking…") }
        let n = liked.count
        return liked.complete ? L.t("\(name) の \(n) 曲", "\(n) \(n == 1 ? "song" : "songs") by \(name)") : L.t("\(name) の \(n) 曲以上", "\(n)+ songs by \(name)")
    }

    private func load() async {
        failed = false
        let result = (try? await AppServices.music.artist(browseId)) ?? .failed(.network)
        loading = false
        guard let page = result.value else {
            failed = artist == nil
            return
        }
        artist = page
        if let id = page.allSongsPlaylistId { allSongs = Task { try? await AppServices.music.fullPlaylist(id) } }
        var list: [(title: String, items: [HomeItem])] = page.albumShelves.map { ($0.title, $0.albums.map { .album($0) }) }
        // 最近の新作（登録したアーティストの見張りで見つけたもの）を先頭に（Android と同じ）。今のページから消えたもの（同じ名前の別人の作品だった）は出さない
        let releases = ReleaseWatchState.listed(AppServices.releases.recent.filter { $0.artistId == browseId }, artistId: browseId, page: page,
                                                year: Calendar.current.component(.year, from: Date())).map(ArtistView.releaseItem)
        if !releases.isEmpty { list.insert((L.t("新作", "New releases"), releases), at: 0) }
        // 動画の棚（MV・YouTube の動画）
        if !page.videos.isEmpty { list.append((L.t("動画", "Videos"), page.videos.map { .song($0) })) }
        shelves = list
        Task { await loadPosts(page) }
        await loadLiked(page)
    }

    /// 新作をカードにする（アルバムは開く、動画は曲の画面。見つけて7日は補足に NEW を付ける。Mac の HomeModel.releaseItem と同じ）。
    private static func releaseItem(_ r: NewRelease) -> HomeItem {
        let fresh = !r.seeded && Int64(Date().timeIntervalSince1970 * 1000) - r.foundAtEpochMs < 7 * 24 * 60 * 60 * 1000
        let subtitle = (fresh ? "NEW • " : "") + r.artistName
        if let album = r.albumBrowseId { return .album(AlbumSummary(browseId: album, title: r.title, subtitle: subtitle, thumbnailUrl: r.thumbnailUrl)) }
        return .song(Song(videoId: r.videoId ?? "", title: r.title, artists: [ArtistRef(name: r.artistName, browseId: r.artistId)], album: nil,
                          durationSeconds: nil, thumbnailUrl: r.thumbnailUrl))
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
    private func toggleSubscribe() {
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

    /// 人気の曲からすぐ鳴らし始め、「すべての曲」の一覧が読めたら残りを後ろに足す（Android・Mac と同じ）。
    private func play(start: Int, shuffle: Bool?) {
        let all = allSongs
        let songs = artist?.topSongs ?? []
        Task { await AppServices.player.playArtist(browseId, songs, start: start, shuffle: shuffle, allSongs: all) }
    }
}

private struct ArtistViewSectionTitle: View {
    var text: String

    var body: some View {
        Text(text).font(.title3.weight(.bold)).padding(EdgeInsets(top: 20, leading: 16, bottom: 8, trailing: 16))
    }
}

/// 大きな丸い再生・一時停止のボタン。
private struct ArtistViewPlayPause: View {
    var playing: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: playing ? "pause.fill" : "play.fill")
                .font(.system(size: 22))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(Circle().fill(Color.accentColor))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(playing ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
    }
}

/// 棚のカード（押すとページを開く。長押しで再生。Android・Mac と同じ）。
private struct ArtistViewCard: View {
    var item: HomeItem

    var body: some View {
        let isVideo = if case .song = item { true } else { false }
        let width: CGFloat = isVideo ? 220 : 150
        Button { AppServices.nav.openCard(item) } label: {
            VStack(alignment: .leading, spacing: 4) {
                RemoteImage(url: Ui.img(item.thumbnailUrl))
                    .frame(width: width, height: isVideo ? width * 9 / 16 : width)
                    .background(Color.primary.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                Text(item.title).font(.footnote).lineLimit(2).multilineTextAlignment(.leading)
                if let subtitle = item.subtitle { Text(subtitle).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(L.t("再生", "Play"), systemImage: "play.fill") { AppServices.nav.play(item) }
            if case .song(let song) = item { SongMenuItems(song: song) }
        }
    }
}

/// ライブ・お知らせの投稿（押すと YouTube の投稿を開く）。
private struct ArtistViewPostCard: View {
    var post: ArtistPost
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button { if let url = URL(string: post.url) { openURL(url) } } label: {
            VStack(alignment: .leading, spacing: 6) {
                if let url = post.imageUrl {
                    RemoteImage(url: Ui.resize(url, 520)).frame(height: 140).clipShape(RoundedRectangle(cornerRadius: 8))
                }
                Text(post.text).font(.subheadline).lineLimit(6).multilineTextAlignment(.leading)
                if let published = post.publishedText { Text(published).subtle() }
            }
            .padding(12)
            .frame(width: 260, alignment: .topLeading)
            .frame(maxHeight: .infinity, alignment: .top)
            .glassCard(14)
        }
        .buttonStyle(.plain)
    }
}
#endif
