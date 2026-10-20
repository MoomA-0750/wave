#if os(iOS)
import SwiftUI
import WaveCore

/// ホーム（Android の HomeScreen と同じ並び）: 絞り込みのチップ、最近聴いたものの2列の格子、登録したアーティストの新作、おすすめの棚。
/// 右上のアカウントの絵から、最近再生した曲・端末の連携・設定へ。
struct HomeView: View {
    @State private var home = HomeModel.shared

    var body: some View {
        let releases = home.selected == nil ? Array(ReleaseWatchState.diversified(AppServices.releases.recent).prefix(20)) : []
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                chips
                if !home.recent.isEmpty { RecentGrid(items: home.recent) }
                // 登録したアーティストの新作（最近聴いたものの下）。絞り込み中は出さない
                if !releases.isEmpty {
                    ShelfRow(title: L.t("登録したアーティストの新作", "New from artists you subscribe to"), strapline: nil,
                             items: releases.map(HomeModel.releaseItem))
                }
                let shelves = home.displayShelves
                ForEach(Array(shelves.enumerated()), id: \.offset) { i, shelf in
                    ShelfRow(title: shelf.title, strapline: shelf.strapline, items: shelf.items)
                        // 下の端に近づいたら続きを読む（iPhone ではホームの下に何も続かないので自動で読む）
                        .onAppear { if i >= shelves.count - 2 { home.loadMore() } }
                }
                if home.loading {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, shelves.isEmpty ? 80 : 8)
                } else if let failure = home.failure, home.newReleases.isEmpty {
                    // 新作だけでも読めていれば、失敗は出さない（Android と同じ）
                    LoadState(loading: false, failure: L.t("おすすめを読み込めませんでした: ", "Couldn't load recommendations: ") + FailureText.message(failure)) {
                        home.reload()
                    }
                }
            }
            .padding(.vertical, 8)
        }
        .refreshable { await home.refresh(keep: true) }
        .navigationTitle(L.t("ホーム", "Home"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { AccountMenu() }
        }
        .onAppear { home.start() }
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ChipButton(title: L.t("すべて", "All"), isOn: home.selected == nil) { home.select(nil) }
                ForEach(home.chips, id: \.self) { chip in
                    ChipButton(title: chip.title, isOn: home.selected == chip) { home.select(chip) }
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

/// 絞り込みのチップ（選んでいるものは塗りつぶし）。
private struct ChipButton: View {
    var title: String
    var isOn: Bool
    var action: () -> Void

    var body: some View {
        if isOn {
            Button(title, action: action).buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
        } else {
            Button(title, action: action).buttonStyle(.bordered).buttonBorderShape(.capsule).tint(.primary)
        }
    }
}

/// 右上のアカウントの絵。押すと Android の横から出るメニューと同じ項目を出す。
private struct AccountMenu: View {
    var body: some View {
        let account = AppServices.account!
        Menu {
            Section(account.name ?? L.t("アカウント", "Account")) {
                Button(L.t("最近再生した曲", "Recently played"), systemImage: "clock.arrow.circlepath") { AppServices.nav.history() }
                if AppServices.connect.configured {
                    Button(L.t("端末の連携", "Devices"), systemImage: "hifispeaker.2") { AppServices.nav.devices() }
                }
                Button(L.t("設定", "Settings"), systemImage: "gearshape") { AppServices.nav.settings() }
            }
        } label: {
            if let url = Ui.small(account.photoUrl) {
                Cover(url: url, size: 30, round: true)
            } else {
                Image(systemName: "person.crop.circle").font(.title2)
            }
        }
        .accessibilityLabel(L.t("アカウント", "Account"))
    }
}

/// 最近聴いたものの2列の格子（Spotify のホームの先頭。画像を左に、名前を右に）。
private struct RecentGrid: View {
    var items: [HomeItem]

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(items, id: \.key) { item in
                Button { AppServices.nav.openCard(item) } label: {
                    HStack(spacing: 8) {
                        Cover(url: Ui.small(item.thumbnailUrl), size: 56, round: item.isArtist, radius: 0)
                            .padding(item.isArtist ? 4 : 0)
                            .frame(width: 56, height: 56)
                        Text(item.title).font(.footnote.weight(.semibold)).lineLimit(2).multilineTextAlignment(.leading)
                        Spacer(minLength: 4)
                    }
                    .frame(height: 56)
                    .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 8))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .contentShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
                .contextMenu { CardMenuItems(item: item) }
            }
        }
        .padding(.horizontal, 16)
    }
}

/// 横に流れる棚（見出しとカード）。
private struct ShelfRow: View {
    var title: String
    var strapline: String?
    var items: [HomeItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 0) {
                if let strapline, !strapline.isEmpty { Text(strapline).subtle() }
                Text(title).font(.title3.weight(.bold)).lineLimit(1)
            }
            .padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(Array(items.enumerated()), id: \.offset) { _, item in HomeCard(item: item) }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}

/// 棚のカード。押すとそのページ（曲は曲の画面）、長押しで再生や曲のメニュー。
private struct HomeCard: View {
    static let size: CGFloat = 140
    var item: HomeItem

    var body: some View {
        Button { AppServices.nav.openCard(item) } label: {
            VStack(alignment: .leading, spacing: 4) {
                Cover(url: Ui.img(item.thumbnailUrl), size: HomeCard.size, round: item.isArtist, radius: 8)
                Text(item.title).font(.subheadline).lineLimit(2).multilineTextAlignment(.leading)
                if let subtitle = subtitle { Text(subtitle).subtle() }
            }
            .frame(width: HomeCard.size, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu { CardMenuItems(item: item) }
    }

    private var subtitle: String? {
        if case .artist = item { return item.subtitle ?? L.t("アーティスト", "Artist") }
        return item.subtitle
    }
}

/// カードの長押しのメニュー（再生と、曲なら曲のメニュー）。
private struct CardMenuItems: View {
    var item: HomeItem

    var body: some View {
        Button(L.t("再生", "Play"), systemImage: "play.fill") { AppServices.nav.play(item) }
        if case .song(let song) = item { SongMenuItems(song: song) }
    }
}

/// 気分・ジャンルのページ（Android の MoodCategoryScreen と同じ。プレイリストなどの棚が並ぶ）。
struct MoodView: View {
    var mood: MoodCategory
    @State private var shelves: [HomeShelf] = []
    @State private var loading = true
    @State private var failure: FailureKind?

    init(mood: MoodCategory) {
        self.mood = mood
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                ForEach(Array(shelves.enumerated()), id: \.offset) { _, shelf in
                    ShelfRow(title: shelf.title, strapline: shelf.strapline, items: shelf.items)
                }
                LoadState(loading: loading && shelves.isEmpty, failure: failure.map(FailureText.message)) { Task { await load() } }
            }
            .padding(.vertical, 8)
        }
        .refreshable { await load() }
        .navigationTitle(mood.title)
        .task { if shelves.isEmpty { await load() } }
    }

    private func load() async {
        loading = true
        failure = nil
        let result = (try? await AppServices.music.moodCategory(mood.params)) ?? .failed(.network)
        loading = false
        if let page = result.value {
            shelves = page.shelves
        } else if shelves.isEmpty {
            failure = result.failure ?? .network
        }
    }
}

/// 曲の画面（ホームの棚の曲を押したときに開く。Android の SongScreen・Mac の SongPageView と同じ）。押してもすぐには再生せず、
/// 再生ボタン（その曲から始まるラジオ）と、曲のメニューと同じ操作（曲のラジオは再生ボタンと同じなので出さない）を並べる。
struct SongView: View {
    var song: Song
    @State private var tint: Color?

    init(song: Song) {
        self.song = song
    }

    var body: some View {
        let player = AppServices.player!
        List {
            Section {
                VStack(spacing: 12) {
                    Cover(url: Ui.large(song.thumbnailUrl), size: 240, radius: 10)
                        .shadow(radius: 12, y: 6)
                    Text(song.title).font(.title2.weight(.bold)).multilineTextAlignment(.center).lineLimit(3)
                    if !detail.isEmpty {
                        Text(detail).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center).lineLimit(2)
                    }
                    PlayCircle(size: 60) { player.playSong(song) }
                        .padding(.top, 4)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            Section {
                // 並びは Android の曲のメニューと同じ
                if AppServices.client.isLoggedIn {
                    Button(L.t("プレイリストに追加", "Add to playlist"), systemImage: "plus.circle") { Dialogs.shared.addToPlaylist(song) }
                }
                if AppServices.client.isLoggedIn && !Feed.isExternal(song.videoId) {
                    let liked = player.isLiked(song)
                    Button(liked ? L.t("高く評価した曲から削除", "Remove from Liked Songs") : L.t("高く評価した曲に追加", "Add to Liked Songs"),
                           systemImage: liked ? "checkmark.circle.fill" : "plus.circle") { player.setLiked(song, !liked) }
                }
                Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(song) }
                Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.addToQueue(song) }
                if let albumId = song.album?.browseId {
                    Button(L.t("アルバムへ移動", "Go to album"), systemImage: "square.stack") { AppServices.nav.album(albumId) }
                }
                ForEach(song.artists.filter { $0.browseId != nil }, id: \.self) { artist in
                    Button(song.artists.count > 1 ? L.t("アーティストへ移動: \(artist.name)", "Go to artist: \(artist.name)") : L.t("アーティストへ移動", "Go to artist"),
                           systemImage: "person") { AppServices.nav.artist(artist.browseId!) }
                }
                ShareLink(item: SongActions.url(song), subject: Text(song.title), message: Text(SongActions.shareText(song))) {
                    Label(L.t("共有", "Share"), systemImage: "square.and.arrow.up")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(alignment: .top) {
            LinearGradient(colors: [(tint ?? .clear).opacity(0.6), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 420)
                .ignoresSafeArea()
                .animation(.easeOut(duration: 0.4), value: tint)
        }
        .navigationTitle(L.t("曲", "Song"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: song.thumbnailUrl) { tint = await Artwork.dominantColor(song.thumbnailUrl) }
    }

    private var detail: String {
        [song.artistsText, song.album?.title, song.durationSeconds.map { Ui.duration($0) }]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
    }
}
#endif
