#if os(iOS)
import SwiftUI
import WaveCore

/// アーティストのページ（Android の ArtistScreen と同じ並び: 大きな写真と名前、登録・シャッフル・再生、新作、ライブの投稿、人気の曲、
/// すべての曲、高く評価した曲、アルバム・シングルなどの棚、動画）。
struct ArtistView: View {
    var browseId: String
    @State private var model: ArtistModel
    /// 写真が上へ流れきったか（それまでは上の帯の名前とぼかしを出さない。写真の名前と二重になる）
    @State private var pastHero = false

    init(browseId: String) {
        self.browseId = browseId
        _model = State(initialValue: ArtistModel(browseId: browseId))
    }

    var body: some View {
        let player = AppServices.player!
        let playingHere = model.isPlayingHere
        let artist = model.artist
        let topSongs = model.topSongs
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
                HStack(spacing: 16) {
                    if artist?.channelId != nil && AppServices.client.isLoggedIn { subscribeButton }
                    if model.loading { ProgressView() }
                    Spacer()
                    Button {
                        model.toggleShuffle()
                    } label: {
                        Image(systemName: "shuffle").font(.system(size: 20, weight: .semibold))
                            .foregroundStyle((playingHere ? player.shuffle : model.shufflePlay) ? Color.accentColor : Color.secondary)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L.t("シャッフル", "Shuffle"))
                    ArtistViewPlayPause(playing: playingHere && player.isPlaying) {
                        model.playOrPause()
                    }
                    .disabled(topSongs.isEmpty)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                if model.failed {
                    LoadState(loading: false, failure: L.t("アーティストを読み込めませんでした", "Couldn't load the artist")) {
                        Task { await model.load() }
                    }
                }
                // ライブ・ツアーの告知（アーティストの YouTube のチャンネルの投稿から。Android と同じ）。告知が無ければ出さない
                if !model.posts.isEmpty {
                    ArtistViewSectionTitle(text: L.t("ライブ・お知らせ", "Live & news"))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 12) { ForEach(model.posts, id: \.postId) { ArtistViewPostCard(post: $0) } }
                            .padding(.horizontal, 16)
                    }
                }
                if !topSongs.isEmpty {
                    ArtistViewSectionTitle(text: L.t("人気", "Popular"))
                    LazyVStack(spacing: 0) {
                        ForEach(Array(topSongs.enumerated()), id: \.offset) { i, song in
                            SongRow(song: song) { model.play(start: i, shuffle: nil) }
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
                if model.checkingLiked || (model.liked?.count ?? 0) > 0, let artist {
                    Button { AppServices.nav.likedSongsBy(browseId, artist.name) } label: {
                        HStack(spacing: 14) {
                            ZStack {
                                Circle().fill(Ui.likedGradient)
                                Image(systemName: "heart.fill").foregroundStyle(.white)
                            }
                            .frame(width: 56, height: 56)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L.t("高く評価した曲", "Liked Songs")).font(.body.weight(.semibold))
                                Text(model.likedText(artist.name)).subtle()
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(model.checkingLiked)
                }
                ForEach(Array(model.shelves.enumerated()), id: \.offset) { _, shelf in
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
        .onScrollGeometryChange(for: Bool.self) { geo in
            geo.contentOffset.y + geo.contentInsets.top > 380 - 120
        } action: { _, past in
            withAnimation(.easeOut(duration: 0.2)) { pastHero = past }
        }
        .navigationTitle(pastHero ? (artist?.name ?? "") : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .modifier(TopEdgeEffect(hidden: !pastHero))
        .refreshable { await model.load() }
        .task { if model.artist == nil { await model.load() } }
    }

    // 横いっぱいの写真に名前を重ねる（下を暗くして白い文字を読めるようにする）
    private var hero: some View {
        Color.clear
            .frame(height: 380)
            .overlay { RemoteImage(url: Ui.resize(model.artist?.thumbnailUrl, 1080)) }
            .overlay {
                LinearGradient(stops: [.init(color: .clear, location: 0.4), .init(color: .black.opacity(0.7), location: 1)], startPoint: .top, endPoint: .bottom)
            }
            .overlay(alignment: .bottomLeading) {
                Text(model.artist?.name ?? "")
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
        let subscribed = model.artist?.subscribed == true
        return Button(subscribed ? L.t("登録済み", "Subscribed") : L.t("登録", "Subscribe")) { model.toggleSubscribe() }
            .font(.subheadline.weight(.semibold))
            .buttonStyle(.borderedProminent)
            .tint(subscribed ? Color.gray.opacity(0.4) : Color.accentColor)
            .buttonBorderShape(.capsule)
            .disabled(model.subscribing || model.artist?.subscribed == nil)
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

#if os(iOS)
/// iOS 26 以降の、上の帯の下のぼかし（スクロールの端の効果）を出すかどうか。
struct TopEdgeEffect: ViewModifier {
    var hidden: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.scrollEdgeEffectHidden(hidden, for: .top)
        } else {
            content
        }
    }
}
#endif
