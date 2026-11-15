#if os(macOS)
import SwiftUI
import WaveCore

/// ポッドキャストの番組のページ（Android と同じ: 画像・題・配信者・説明・保存のボタン・再生ボタン、新しい順のエピソード）。
/// エピソードを押すと、読み込んだエピソードの一覧をその位置から流す（曲の一覧と同じ）。
struct PodcastPageView: View {
    var summary: PodcastSummary
    @State private var model: PodcastModel
    @State private var expanded = false

    init(summary: PodcastSummary) {
        self.summary = summary
        _model = State(initialValue: PodcastModel(summary: summary))
    }

    var body: some View {
        let player = AppServices.player!
        let playingHere = model.isPlayingHere
        let episodes = model.episodes
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                header
                HStack(spacing: Kasane.Space.s4) {
                    // 押せるとき（エピソードがあるとき）だけ出す
                    if !episodes.isEmpty {
                        PlayCircleButton(action: {
                            model.playOrPause()
                        }) { Image(systemName: playingHere && player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20)) } // apple-ui-allow: 記号の大きさ（Windows 版と揃える）
                    }
                    if let saved = model.saved, model.isFeed || AppServices.client.isLoggedIn {
                        Button(saved ? L.t("保存済み", "Saved") : L.t("ライブラリに保存", "Save to library")) { model.toggleSaved() }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .controlSize(.large)
                            .disabled(model.saving)
                    }
                    if model.loading { ProgressView().controlSize(.small) }
                }
                .padding(EdgeInsets(top: Kasane.Space.s5, leading: Kasane.Space.s3, bottom: Kasane.Space.s3, trailing: Kasane.Space.s3))
                // 「新しいエピソード」は、番組を保存していないと番組のページの形で返らない（エピソードが無いだけとして出す）
                if model.failed && !model.isNewEpisodes { Text(L.t("ポッドキャストを読み込めませんでした", "Couldn't load the podcast")).padding(Kasane.Space.s3) }
                if !model.loading && (!model.failed || model.isNewEpisodes) && episodes.isEmpty { Text(L.t("エピソードはありません", "No episodes")).subtle().padding(Kasane.Space.s3) }
                ForEach(Array(episodes.enumerated()), id: \.element.id) { i, episode in
                    EpisodeRowView(episode: episode, showShow: false, onPlay: { model.play(i) })
                        .onAppear { if i == episodes.count - 1 { model.loadMore() } }
                }
                if model.loadingMore { ProgressView().controlSize(.small).padding(Kasane.Space.s3) }
            }
            .padding(EdgeInsets(top: 0, leading: Kasane.Space.s3, bottom: Kasane.Space.s6, trailing: Kasane.Space.s3))
        }
        .background(alignment: .top) {
            LinearGradient(colors: [(model.gradient ?? .clear).opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).frame(height: 360)
                // 上の帯（検索の欄）の裏まで伸ばす
                .ignoresSafeArea(edges: .top)
        }
        .task { await model.load() }
    }

    private var header: some View {
        HStack(alignment: .bottom, spacing: Kasane.Space.s6) {
            if model.isNewEpisodes && model.page?.thumbnailUrl == nil {
                NewEpisodesCover(size: 220, radius: Kasane.Radius.base)
            } else {
                Cover(url: Ui.large(model.page?.thumbnailUrl ?? summary.thumbnailUrl), size: 220, radius: Kasane.Radius.base)
            }
            VStack(alignment: .leading, spacing: Kasane.Space.s2) {
                Text(model.isNewEpisodes ? L.t("プレイリスト • 自動", "Playlist • Auto") : L.t("ポッドキャスト", "Podcast")).font(.callout)
                Text(model.page?.title ?? summary.title).font(.system(size: 48, weight: .bold)).lineLimit(2).minimumScaleFactor(0.5) // apple-ui-allow: 見出し・歌詞の大きさ（Windows 版と揃える）
                if let author = model.page?.author ?? summary.author { Text(author).font(.body.weight(.semibold)) }
                if let description = model.page?.description {
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
        .padding(EdgeInsets(top: Kasane.Space.s8, leading: Kasane.Space.s3, bottom: 0, trailing: Kasane.Space.s3))
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
        HStack(alignment: .top, spacing: Kasane.Space.s3) {
            RemoteImage(url: URL(string: episode.song.thumbnailUrl ?? ""))
                .frame(width: showShow ? 64 : 112, height: showShow ? 64 : 63)
                .background(Color.primary.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: Kasane.Radius.sm, style: Kasane.cornerStyle))
            VStack(alignment: .leading, spacing: 3) {
                Text(episode.song.title).font(.body.weight(.semibold)).lineLimit(2).foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                let detail = showShow ? episode.showAndDate : episode.dateAndLength
                HStack(spacing: Kasane.Space.s1) {
                    DownloadedMark(videoId: episode.song.videoId)
                    if !detail.isEmpty { Text(detail).subtle() }
                }
                if !showShow, let description = episode.description {
                    Text(description).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                }
                if let percent = AppServices.feeds.progress(episode) {
                    ProgressView(value: Double(percent), total: 100).frame(maxWidth: 160)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: isCurrent ? "speaker.wave.2.fill" : "play.circle")
                .font(.system(size: 22)) // apple-ui-allow: 記号・絵に合わせた大きさ（Windows 版と揃える）
                .foregroundStyle(isCurrent ? Color.accentColor : .secondary)
                .opacity(hover || isCurrent ? 1 : 0)
        }
        .padding(Kasane.Space.s2)
        .background(RoundedRectangle(cornerRadius: Kasane.Radius.sm, style: Kasane.cornerStyle).fill(hover ? Color.primary.opacity(0.07) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(perform: onPlay)
        .contextMenu { SongMenuItems(song: episode.song, episode: true) }
    }
}

/// 番組のカード（検索の結果）。押すと番組のページ。
struct PodcastCard: View {
    var podcast: PodcastSummary
    /// 開く前に呼ぶ（検索から開いたものを履歴に残す）。
    var onOpen: (() -> Void)?
    @State private var hover = false

    var body: some View {
        VStack(alignment: .leading, spacing: Kasane.Space.s2) {
            Cover(url: Ui.img(podcast.thumbnailUrl), size: 160)
            VStack(alignment: .leading, spacing: 2) {
                Text(podcast.title).font(.body.weight(.semibold)).lineLimit(1)
                Text(podcast.author ?? L.t("ポッドキャスト", "Podcast")).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .frame(width: 160, alignment: .leading)
        }
        .padding(Kasane.Space.s2)
        .frame(width: 176)
        .background(RoundedRectangle(cornerRadius: Kasane.Radius.base, style: Kasane.cornerStyle).fill(hover ? Color.primary.opacity(0.07) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture {
            onOpen?()
            AppServices.nav.podcast(podcast)
        }
    }
}
#endif
