#if os(macOS)
import SwiftUI
import WaveCore

/// ポッドキャストの番組のページ（Android と同じ: 画像・題・配信者・説明・保存のボタン・再生ボタン、新しい順のエピソード）。
/// エピソードを押すと、読み込んだエピソードの一覧をその位置から流す（曲の一覧と同じ）。
struct PodcastPageView: View {
    var summary: PodcastSummary
    @State private var page: PodcastPage?
    @State private var episodes: [PodcastEpisode] = []
    @State private var continuation: String?
    @State private var loading = true
    @State private var loadingMore = false
    @State private var failed = false
    @State private var saving = false
    @State private var expanded = false
    @State private var gradient: Color?

    var body: some View {
        let player = AppServices.player!
        let playlistId = summary.playlistId
        let playingHere = player.sourceId == playlistId && player.current != nil
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                header
                HStack(spacing: 16) {
                    PlayCircleButton(action: {
                        if playingHere { player.togglePlay() } else { play(0) }
                    }) { Image(systemName: playingHere && player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20)) }
                        .disabled(episodes.isEmpty)
                    if let saved, isFeed || AppServices.client.isLoggedIn {
                        Button(saved ? L.t("保存済み", "Saved") : L.t("ライブラリに保存", "Save to library")) { toggleSaved() }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .controlSize(.large)
                            .disabled(saving)
                    }
                    if loading { ProgressView().controlSize(.small) }
                }
                .padding(EdgeInsets(top: 20, leading: 12, bottom: 12, trailing: 12))
                // 「新しいエピソード」は、番組を保存していないと番組のページの形で返らない（エピソードが無いだけとして出す）
                if failed && !isNewEpisodes { Text(L.t("ポッドキャストを読み込めませんでした", "Couldn’t load the podcast")).padding(12) }
                if !loading && (!failed || isNewEpisodes) && episodes.isEmpty { Text(L.t("エピソードはありません", "No episodes")).subtle().padding(12) }
                ForEach(Array(episodes.enumerated()), id: \.element.id) { i, episode in
                    EpisodeRowView(episode: episode, showShow: false, onPlay: { play(i) })
                        .onAppear { if i == episodes.count - 1 { loadMore() } }
                }
                if loadingMore { ProgressView().controlSize(.small).padding(12) }
            }
            .padding(EdgeInsets(top: 0, leading: 12, bottom: 24, trailing: 12))
        }
        .background(alignment: .top) {
            LinearGradient(colors: [(gradient ?? .clear).opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).frame(height: 360)
        }
        .task { await load() }
    }

    private var isNewEpisodes: Bool { summary.browseId == MusicRepository.newEpisodesBrowse }

    /// RSS の番組（`rss:…`。ADR-0012）。保存はこの端末の登録になる。
    private var isFeed: Bool { Feed.isFeedShow(summary.browseId) }

    /// 保存しているか。RSS の番組はこの端末の登録を見る（分からなければ nil）。
    private var saved: Bool? {
        guard page != nil else { return nil }
        if isFeed, let url = Feed.feedUrlOf(summary.browseId) { return AppServices.feeds.isSubscribed(url) }
        return page?.saved
    }

    private var header: some View {
        HStack(alignment: .bottom, spacing: 24) {
            if isNewEpisodes && page?.thumbnailUrl == nil {
                NewEpisodesCover(size: 220, radius: 8)
            } else {
                Cover(url: Ui.large(page?.thumbnailUrl ?? summary.thumbnailUrl), size: 220, radius: 8)
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(isNewEpisodes ? L.t("プレイリスト • 自動", "Playlist • Auto") : L.t("ポッドキャスト", "Podcast")).font(.callout)
                Text(page?.title ?? summary.title).font(.system(size: 48, weight: .bold)).lineLimit(2).minimumScaleFactor(0.5)
                if let author = page?.author ?? summary.author { Text(author).font(.body.weight(.semibold)) }
                if let description = page?.description {
                    // 説明は短く出し、押すと全部を出す
                    Text(description).foregroundStyle(.secondary).lineLimit(expanded ? nil : 3)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentShape(Rectangle())
                        .onTapGesture { expanded.toggle() }
                        .help(expanded ? L.t("短く表示", "Show less") : L.t("すべて表示", "Show more"))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(EdgeInsets(top: 32, leading: 12, bottom: 0, trailing: 12))
    }

    private func load() async {
        let value: PodcastPage?
        if isFeed {
            // RSS の番組は、フィードを読んで同じ形にする
            value = try? await AppServices.feeds.page(summary.browseId).get()
        } else {
            let result = (try? await AppServices.music.podcast(summary.browseId)) ?? .failed(.network)
            // 保存した番組が無いアカウントの「新しいエピソード」は番組のページの形で返らない。空として出し、RSS の回を混ぜる（Android と同じ）
            value = result.value ?? (isNewEpisodes && result.failure == .parse
                ? PodcastPage(browseId: summary.browseId, title: summary.title, author: nil, description: nil, thumbnailUrl: nil, episodes: [],
                              continuation: nil, saved: nil)
                : nil)
        }
        loading = false
        guard let value else {
            failed = true
            AppPaths.log("podcast failed")
            return
        }
        page = value
        episodes = value.episodes
        continuation = value.continuation
        // 「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012）
        if isNewEpisodes {
            let merged = await AppServices.feeds.mergeNewEpisodes(value.episodes)
            episodes = merged
            page?.episodes = merged
        }
        AppPaths.log("podcast episodes=\(value.episodes.count) more=\(value.continuation != nil) saved=\(value.saved.map { "\($0)" } ?? "nil")")
        gradient = await Artwork.dominantColor(value.thumbnailUrl)
    }

    private func loadMore() {
        guard !loadingMore, let token = continuation, let page else { return }
        loadingMore = true
        Task {
            defer { loadingMore = false }
            let more: SearchPage<PodcastEpisode>?
            if isFeed {
                more = await AppServices.feeds.more(page.browseId, token)
            } else {
                more = (try? await AppServices.music.podcastEpisodes(continuation: token, show: ArtistRef(name: page.title, browseId: nil), showBrowseId: page.browseId))?.value
            }
            guard let more else { return }
            let known = Set(episodes.map(\.id))
            let fresh = more.items.filter { !known.contains($0.id) }
            episodes += fresh
            continuation = more.continuation != token ? more.continuation : nil
            // この番組を流しているなら、読めた分を後ろに足す
            let player = AppServices.player!
            if player.sourceId == summary.playlistId { player.appendFrom(fresh.map(\.song)) }
        }
    }

    /// その位置から、読み込んだエピソードを流す（Android の playFrom と同じく、出どころは番組のプレイリスト）。
    /// RSS の番組は YouTube のプレイリストではない（出どころの印はこの端末の「再生中」の表示にだけ使い、連携には送らない）。
    private func play(_ index: Int) {
        guard !episodes.isEmpty else { return }
        AppServices.player.playList(episodes.map(\.song), start: index, source: summary.playlistId)
    }

    /// 保存の切り替え。書けたら表示を変える（アーティストの登録と同じく楽観的に変えない）。
    private func toggleSaved() {
        if isFeed {
            if let current = page { AppServices.feeds.toggle(current) }
            return
        }
        guard let current = page, let saved = current.saved, !saving else { return }
        saving = true
        Task {
            let result = (try? await AppServices.music.setPodcastSaved(current.playlistId, saved: !saved)) ?? .failed(.network)
            saving = false
            if result.isOk {
                page?.saved = !saved
                AppServices.library.refresh()
            } else {
                AppServices.player.message = L.t("ライブラリを変更できませんでした", "Couldn't change your library")
            }
        }
    }
}

/// エピソードの行（画像・題・「番組 • 日付」または「日付 • 長さ」・説明・聴いたところ）。押すと再生、右クリックで曲のメニュー。
struct EpisodeRowView: View {
    var episode: PodcastEpisode
    /// 番組名を出す（検索の結果）。番組のページでは出さない。
    var showShow: Bool
    var onPlay: () -> Void
    @State private var hover = false

    var body: some View {
        let isCurrent = AppServices.player.current?.videoId == episode.song.videoId
        HStack(alignment: .top, spacing: 12) {
            RemoteImage(url: URL(string: episode.song.thumbnailUrl ?? ""))
                .frame(width: showShow ? 64 : 112, height: showShow ? 64 : 63)
                .background(Color.primary.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 3) {
                Text(episode.song.title).font(.body.weight(.semibold)).lineLimit(2).foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                let detail = showShow ? episode.showAndDate : episode.dateAndLength
                if !detail.isEmpty { Text(detail).subtle() }
                if !showShow, let description = episode.description {
                    Text(description).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                }
                if let percent = AppServices.feeds.progress(episode) {
                    ProgressView(value: Double(percent), total: 100).frame(maxWidth: 160)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: isCurrent ? "speaker.wave.2.fill" : "play.circle")
                .font(.system(size: 22))
                .foregroundStyle(isCurrent ? Color.accentColor : .secondary)
                .opacity(hover || isCurrent ? 1 : 0)
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 6).fill(hover ? Color.primary.opacity(0.07) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(perform: onPlay)
        .contextMenu { SongMenuItems(song: episode.song) }
    }
}

/// 番組のカード（検索の結果）。押すと番組のページ。
struct PodcastCard: View {
    var podcast: PodcastSummary
    /// 開く前に呼ぶ（検索から開いたものを履歴に残す）。
    var onOpen: (() -> Void)?
    @State private var hover = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Cover(url: Ui.img(podcast.thumbnailUrl), size: 160)
            VStack(alignment: .leading, spacing: 2) {
                Text(podcast.title).font(.body.weight(.semibold)).lineLimit(1)
                Text(podcast.author ?? L.t("ポッドキャスト", "Podcast")).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .frame(width: 160, alignment: .leading)
        }
        .padding(8)
        .frame(width: 176)
        .background(RoundedRectangle(cornerRadius: 8).fill(hover ? Color.primary.opacity(0.07) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture {
            onOpen?()
            AppServices.nav.podcast(podcast)
        }
    }
}
#endif
