#if os(macOS)
import AppKit
import SwiftUI
import WaveCore

/// アーティストのページ（Spotify と同じ: 横いっぱいの画像に名前、再生ボタン、人気の曲、ディスコグラフィー）。
struct ArtistPageView: View {
    var browseId: String
    @State private var model: ArtistModel
    @State private var selected: String?

    init(browseId: String) {
        self.browseId = browseId
        _model = State(initialValue: ArtistModel(browseId: browseId))
    }

    var body: some View {
        let player = AppServices.player!
        let playingHere = model.isPlayingHere
        let artist = model.artist
        let rows = model.topSongs.enumerated().map { TrackRow(number: $0.offset + 1, song: $0.element) }
        // 画像は上の帯（検索の欄）の裏まで伸ばし、帯の下に見える高さはそのままにする
        GeometryReader { geo in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    ZStack(alignment: .bottom) {
                        RemoteImage(url: Ui.resize(artist?.thumbnailUrl, 1080))
                        LinearGradient(stops: [.init(color: .clear, location: 0.3), .init(color: .black.opacity(0.7), location: 1)], startPoint: .top, endPoint: .bottom)
                        // 上の帯の検索の欄・ボタンが明るい画像に埋もれないよう、上の端を暗くする
                        LinearGradient(colors: [.black.opacity(0.45), .clear], startPoint: .top, endPoint: .bottom)
                            .frame(height: geo.safeAreaInsets.top + 40)
                            .frame(maxHeight: .infinity, alignment: .top)
                        // 名前と、枠の右端に登録（Spotify の「フォロー」）。登録していなければ色を付けて目立たせる
                        HStack(alignment: .lastTextBaseline, spacing: 20) {
                            Text(artist?.name ?? "").font(.system(size: 72, weight: .bold)).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.4)
                            Spacer()
                            if artist?.channelId != nil && AppServices.client.isLoggedIn {
                                subscribeButton
                            }
                        }
                        .padding(24)
                    }
                    .frame(height: 320 + geo.safeAreaInsets.top)
                    .clipped()
                    HStack(spacing: 16) {
                        // 押せるとき（曲があるとき）だけ出す
                        if !rows.isEmpty {
                            PlayCircleButton(action: {
                                model.playOrPause()
                            }) { Image(systemName: playingHere && player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20)) }
                        }
                        IconToggle(isOn: playingHere ? player.shuffle : model.shufflePlay, help: L.t("シャッフル", "Shuffle")) {
                            model.toggleShuffle()
                        } label: { Image(systemName: "shuffle").font(.system(size: 20)) }
                        if model.loading { ProgressView().controlSize(.small) }
                    }
                    .padding(.horizontal, 24)
                    // ライブ・ツアーの告知（アーティストの YouTube のチャンネルの投稿から。Android と同じ）。告知が無ければ出さない
                    if !model.posts.isEmpty {
                        Text(L.t("ライブ・お知らせ", "Live & news")).subtitleStyle().padding(EdgeInsets(top: 8, leading: 24, bottom: 0, trailing: 24))
                        ScrollView(.horizontal) {
                            HStack(alignment: .top, spacing: 12) { ForEach(model.posts, id: \.postId) { LivePostCard(post: $0) } }
                                .padding(EdgeInsets(top: 0, leading: 24, bottom: 8, trailing: 24))
                        }
                    }
                    if model.failed { Text(L.t("アーティストを読み込めませんでした", "Couldn’t load the artist")).padding(24) }
                    if !rows.isEmpty {
                        Text(L.t("人気", "Popular")).subtitleStyle().padding(EdgeInsets(top: 8, leading: 24, bottom: 0, trailing: 24))
                        VStack(spacing: 0) {
                            ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                                TrackRowView(row: row, selected: selected == row.id, onPlay: { model.play(start: i, shuffle: nil) }, onSelect: { selected = row.id })
                            }
                        }
                        .padding(.horizontal, 12)
                    }
                    if let id = artist?.allSongsPlaylistId, let artist {
                        Button(L.t("すべての曲を見る", "See all songs")) {
                            AppServices.nav.playlist(PlaylistSummary(playlistId: id, title: artist.name + L.t(" のすべての曲", " — all songs"), subtitle: nil, thumbnailUrl: artist.thumbnailUrl))
                        }
                        .buttonStyle(.textLink).padding(.horizontal, 24)
                    }
                    // Spotify の「高く評価した曲」の行（このアーティストの、自分が高く評価した曲）。1曲も無ければ出さない
                    if model.checkingLiked || (model.liked?.count ?? 0) > 0, let artist {
                        Button { AppServices.nav.likedSongsBy(browseId, artist.name) } label: {
                            HStack(spacing: 16) {
                                LikedCover(size: 56, round: true)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L.t("高く評価した曲", "Liked Songs")).font(.body.weight(.semibold))
                                    Text(model.likedText(artist.name)).subtle()
                                }
                            }
                            .padding(8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(model.checkingLiked)
                        .padding(.horizontal, 24)
                    }
                    ForEach(Array(model.shelves.enumerated()), id: \.offset) { _, shelf in
                        ShelfView(title: shelf.title, items: shelf.items).padding(.horizontal, 16)
                    }
                }
                .padding(.bottom, 32)
            }
            .ignoresSafeArea(edges: .top)
        }
        .task { await model.load() }
    }

    private var subscribeButton: some View {
        let subscribed = model.artist?.subscribed == true
        return Button(subscribed ? L.t("登録済み", "Subscribed") : L.t("登録", "Subscribe")) { model.toggleSubscribe() }
            .buttonStyle(.borderedProminent)
            .tint(subscribed ? Color.gray.opacity(0.5) : Color.accentColor)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .disabled(model.subscribing || model.artist?.subscribed == nil)
            .padding(.bottom, 16)
    }
}

private struct LivePostCard: View {
    var post: ArtistPost

    var body: some View {
        Button { if let url = URL(string: post.url) { NSWorkspace.shared.open(url) } } label: {
            VStack(alignment: .leading, spacing: 6) {
                if let url = post.imageUrl { RemoteImage(url: Ui.resize(url, 520)).frame(height: 140).clipShape(RoundedRectangle(cornerRadius: 4)) }
                Text(post.text).lineLimit(6).multilineTextAlignment(.leading)
                if let published = post.publishedText { Text(published).subtle() }
            }
            .padding(12)
            .frame(width: 260, alignment: .topLeading)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.06)))
        }
        .buttonStyle(.plain)
    }
}

/// 曲の画面（カードの曲を押したときに開く。Android の SongScreen と同じ）。押してもすぐには再生せず、
/// 再生ボタン（その曲から始まるラジオ）と曲のメニューと同じ操作を並べる。
struct SongPageView: View {
    var song: Song
    @State private var gradient: Color?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .bottom, spacing: 24) {
                    Cover(url: Ui.large(song.thumbnailUrl), size: 220, radius: 8)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(L.t("曲", "Song")).font(.body.weight(.semibold))
                        Text(song.title).font(.system(size: 48, weight: .bold)).lineLimit(2).minimumScaleFactor(0.5).padding(.vertical, 4)
                        Text(detail).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
                .padding(EdgeInsets(top: 40, leading: 12, bottom: 24, trailing: 12))
                PlayCircleButton(action: { AppServices.player.playSong(song) }) { Image(systemName: "play.fill").font(.system(size: 20)) }
                    .padding(EdgeInsets(top: 8, leading: 12, bottom: 16, trailing: 12))
                // 並びは Android の曲の画面と同じ（曲のラジオは再生ボタンと同じなので出さない）
                VStack(alignment: .leading, spacing: 2) {
                    if AppServices.client.isLoggedIn { row(L.t("プレイリストに追加", "Add to playlist"), "plus") { Dialogs.shared.addToPlaylist(song) } }
                    row(L.t("次に再生", "Play next"), "text.line.first.and.arrowtriangle.forward") { AppServices.player.playNext(song) }
                    row(L.t("キューに追加", "Add to queue"), "text.badge.plus") { AppServices.player.addToQueue(song) }
                    if let albumId = song.album?.browseId { row(L.t("アルバムへ移動", "Go to album"), "square.stack") { AppServices.nav.album(albumId) } }
                    ForEach(song.artists.filter { $0.browseId != nil }, id: \.self) { artist in
                        row(song.artists.count > 1 ? L.t("アーティストへ移動: \(artist.name)", "Go to artist: \(artist.name)") : L.t("アーティストへ移動", "Go to artist"), "person") {
                            AppServices.nav.artist(artist.browseId!)
                        }
                    }
                    row(L.t("曲のリンクをコピー", "Copy song link"), "link") { SongActions.copyLink(song) }
                    ShareLink(item: SongActions.url(song), subject: Text(song.title), message: Text(SongActions.shareText(song))) {
                        Label(L.t("ほかのアプリで共有…", "Share with…"), systemImage: "square.and.arrow.up").padding(.horizontal, 12).padding(.vertical, 10)
                    }
                    .buttonStyle(.plain)
                }
                .frame(maxWidth: 480, alignment: .leading)
            }
            .padding(EdgeInsets(top: 0, leading: 12, bottom: 24, trailing: 12))
        }
        .background(alignment: .top) {
            LinearGradient(colors: [(gradient ?? .clear).opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).frame(height: 360)
                // 上の帯（検索の欄）の裏まで伸ばす
                .ignoresSafeArea(edges: .top)
        }
        .task { gradient = await Artwork.dominantColor(song.thumbnailUrl) }
    }

    private var detail: String {
        [song.artistsText, song.album?.title, song.durationSeconds.map { Ui.duration($0) }]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
    }

    private func row(_ text: String, _ icon: String, action: @escaping () -> Void) -> some View {
        HoverRow(action: action) {
            Label(text, systemImage: icon).labelStyle(WideIconLabel())
        }
    }
}

private struct WideIconLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 16) {
            configuration.icon.frame(width: 18)
            configuration.title
        }
    }
}

/// 乗せると薄い地が出る、横いっぱいの行のボタン。
struct HoverRow<Content: View>: View {
    var action: () -> Void
    @ViewBuilder var content: () -> Content
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            content()
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 6).fill(hover ? Color.primary.opacity(0.07) : .clear))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}
#endif
