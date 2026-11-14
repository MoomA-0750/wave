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
        let artist = model.artist
        let topSongs = model.topSongs
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
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
                                Text(L.t("高く評価した曲", "Liked music")).font(.body.weight(.semibold))
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
        // 地は再生画面と同じ（アーティストの写真を大きくぼかして暗くしたもの）。暗い地なので文字は常に暗い表示の色にする
        .background { ArtworkBackdrop(url: artist?.thumbnailUrl) }
        .environment(\.colorScheme, .dark)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onScrollGeometryChange(for: Bool.self) { geo in
            geo.contentOffset.y + geo.contentInsets.top > 380 - 120
        } action: { _, past in
            withAnimation(.easeOut(duration: 0.2)) { pastHero = past }
        }
        .navigationTitle(pastHero ? (artist?.name ?? "") : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar) // apple-ui-allow: iOS 18 で写真の上の帯を塗らないため（26 からはガラスになる）
        .modifier(TopEdgeEffect(hidden: !pastHero))
        .refreshable { await model.load() }
        .task { if model.artist == nil { await model.load() } }
    }

    // 横いっぱいの写真。下の端は地（ぼかした同じ写真）へ溶かし、そこに名前・登録・シャッフル・再生を1列に重ねる
    private var hero: some View {
        Color.clear
            .frame(height: 380)
            .overlay {
                RemoteImage(url: Ui.resize(model.artist?.thumbnailUrl, 1080))
                    .mask(LinearGradient(stops: [.init(color: .black, location: 0.5), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
            }
            .overlay {
                LinearGradient(stops: [.init(color: .clear, location: 0.45), .init(color: .black.opacity(0.35), location: 1)], startPoint: .top, endPoint: .bottom)
            }
            .overlay(alignment: .bottom) {
                HStack(alignment: .center, spacing: 12) {
                    Text(model.artist?.name ?? "")
                        .font(.largeTitle.bold())
                        .foregroundStyle(.white)
                        .lineLimit(2)
                        .minimumScaleFactor(0.5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if model.loading { ProgressView() }
                    ArtistViewActions(model: model)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
            }
            .clipped()
    }
}

/// 名前の右に並べる、登録・シャッフル・再生のアイコンのボタン（Liquid Glass。混ざるよう1つの入れ物にまとめる。
/// 色を付けるのは主役の再生だけ。シャッフルは入れている間だけ薄く色を付ける）。
private struct ArtistViewActions: View {
    var model: ArtistModel

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 10) { buttons }
        } else {
            buttons
        }
    }

    private var buttons: some View {
        let player = AppServices.player!
        let playingHere = model.isPlayingHere
        let shuffleOn = playingHere ? player.shuffle : model.shufflePlay
        let subscribed = model.artist?.subscribed == true
        let playing = playingHere && player.isPlaying
        return HStack(spacing: 10) {
            if model.artist?.channelId != nil && AppServices.client.isLoggedIn {
                Button { model.toggleSubscribe() } label: {
                    Image(systemName: subscribed ? "person.crop.circle.badge.checkmark" : "person.crop.circle.badge.plus")
                        .font(.title3.weight(.semibold))
                        .modifier(ArtistViewGlassCircle(size: 44))
                }
                .buttonStyle(.plain)
                .disabled(model.subscribing || model.artist?.subscribed == nil)
                .accessibilityLabel(subscribed ? L.t("登録済み", "Subscribed") : L.t("登録", "Subscribe"))
            }
            Button { model.toggleShuffle() } label: {
                Image(systemName: "shuffle")
                    .font(.title3.weight(.semibold))
                    .modifier(ArtistViewGlassCircle(size: 44, tint: shuffleOn ? Color.accentColor.opacity(0.5) : nil))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.t("シャッフル", "Shuffle"))
            .accessibilityAddTraits(shuffleOn ? .isSelected : [])
            Button { model.playOrPause() } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .modifier(ArtistViewGlassCircle(size: 56, tint: .accentColor))
            }
            .buttonStyle(.plain)
            .disabled(model.topSongs.isEmpty)
            .accessibilityLabel(playing ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
        }
        .foregroundStyle(.white)
        // 丸の大きさは決まっているので、記号は大きくしすぎない
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}

/// 丸いガラスの地（iOS 26 より前は薄い丸）。押すと光って弾む。
private struct ArtistViewGlassCircle: ViewModifier {
    var size: CGFloat
    var tint: Color? = nil

    func body(content: Content) -> some View {
        let framed = content.frame(width: size, height: size).contentShape(Circle())
        if #available(iOS 26.0, *) {
            framed.glassEffect((tint.map { Glass.regular.tint($0) } ?? .regular).interactive(), in: .circle)
        } else {
            framed.background(Circle().fill(tint ?? Color.white.opacity(0.15)))
        }
    }
}

private struct ArtistViewSectionTitle: View {
    var text: String

    var body: some View {
        Text(text).font(.title3.weight(.bold)).padding(EdgeInsets(top: 20, leading: 16, bottom: 8, trailing: 16))
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
