#if os(iOS)
import SwiftUI
import WaveCore

/// ポッドキャストの番組のページ（Android の PodcastScreen と同じ並び: 画像・題・配信者・説明・保存、再生ボタン、新しい順のエピソード）。
/// 頭はアルバムのページ（CollectionView）と同じ見せ方: 横いっぱいの画像に題・配信者と、保存（しおり）・再生のガラスの丸を重ねる。
/// エピソードを押すと、読み込んだエピソードの一覧をその位置から流す（曲の一覧と同じ）。
struct PodcastView: View {
    var summary: PodcastSummary
    @State private var model: PodcastModel
    @State private var expanded = false
    /// 上の大きな画像が流れきったか（それまでは上の帯の題を出さない。画像の上の題と二重になる）
    @State private var pastHero = false

    init(summary: PodcastSummary) {
        self.summary = summary
        _model = State(initialValue: PodcastModel(summary: summary))
    }

    private var thumbnailUrl: String? { model.page?.thumbnailUrl ?? summary.thumbnailUrl }
    private var title: String { model.page?.title ?? summary.title }

    var body: some View {
        let player = AppServices.player!
        let episodes = model.episodes
        List {
            header(player).listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
            if let description = model.page?.description {
                // 説明は短く出し、押すと全部を出す
                Text(description).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                    .lineLimit(expanded ? nil : 3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture { withAnimation { expanded.toggle() } }
                    .listRowSeparator(.hidden).listRowBackground(Color.clear)
            }
            if model.loading {
                ProgressView().frame(maxWidth: .infinity).padding().listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else if model.failed && !model.isNewEpisodes {
                LoadState(loading: false, failure: L.t("ポッドキャストを読み込めませんでした", "Couldn't load the podcast")) {
                    Task { await model.load() }
                }.listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else if episodes.isEmpty {
                Text(L.t("エピソードはありません", "No episodes")).subtle().listRowSeparator(.hidden).listRowBackground(Color.clear)
            }
            ForEach(Array(episodes.enumerated()), id: \.element.id) { i, episode in
                EpisodeRow(episode: episode, showShow: false) { model.play(i) }
                    .listRowBackground(Color.clear)
                    .onAppear { if i == episodes.count - 1 { model.loadMore() } }
                    .swipeActions(edge: .leading) {
                        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(episode.song) }.tint(.indigo)
                    }
            }
            if model.loadingMore { ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden).listRowBackground(Color.clear) }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // 大きな画像は上の帯の裏まで伸ばす（アルバム・アーティストのページと同じ）
        .ignoresSafeArea(edges: .top)
        .onScrollGeometryChange(for: Bool.self) { geo in
            geo.contentOffset.y + geo.contentInsets.top > heroHeight - 120
        } action: { _, past in
            withAnimation(.easeOut(duration: 0.2)) { pastHero = past }
        }
        // 地はアルバムと同じ（画像を大きくぼかして暗くしたもの）。暗い地なので文字は常に暗い表示の色にする
        .background {
            ZStack(alignment: .top) {
                ArtworkBackdrop(url: thumbnailUrl)
                if thumbnailUrl == nil {
                    LinearGradient(colors: [(model.gradient ?? .clear).opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
                        .frame(height: 460)
                        .ignoresSafeArea()
                }
            }
        }
        .environment(\.colorScheme, .dark)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .navigationTitle(pastHero ? title : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar) // apple-ui-allow: iOS 18 で画像の上の帯を塗らないため（26 からはガラスになる。アルバムのページと同じ）
        .modifier(TopEdgeEffect(hidden: !pastHero))
        .task { if model.page == nil { await model.load() } }
    }

    private var heroHeight: CGFloat { 420 }

    /// 上の大きな画像（横いっぱい。下の端は地へ溶かす）に、題・配信者と、保存・再生を重ねる（アルバムのページと同じ）。
    private func header(_ player: PlayerService) -> some View {
        Color.clear
            .frame(height: heroHeight)
            .overlay {
                ZStack {
                    if model.isNewEpisodes && model.page?.thumbnailUrl == nil {
                        NewEpisodesCover(size: heroHeight, radius: 0)
                    } else {
                        RemoteImage(url: Ui.large(thumbnailUrl))
                    }
                    LinearGradient(stops: [.init(color: .clear, location: 0.4), .init(color: .black.opacity(0.35), location: 0.8)], startPoint: .top, endPoint: .bottom)
                }
                .mask(LinearGradient(stops: [.init(color: .black, location: 0.35), .init(color: .black.opacity(0.9), location: 0.5),
                                             .init(color: .black.opacity(0.6), location: 0.65), .init(color: .black.opacity(0.3), location: 0.8),
                                             .init(color: .black.opacity(0.08), location: 0.92), .init(color: .clear, location: 1)],
                                     startPoint: .top, endPoint: .bottom))
            }
            .overlay(alignment: .bottom) {
                HStack(alignment: .bottom, spacing: Kasane.Space.s3) {
                    VStack(alignment: .leading, spacing: Kasane.Space.s1) {
                        Text(title).font(.title.bold()).foregroundStyle(.white).lineLimit(2).minimumScaleFactor(0.6)
                        if let author = model.page?.author ?? summary.author {
                            Text(author).font(.headline).foregroundStyle(.white.opacity(0.85)).lineLimit(1)
                        }
                        Text(model.isNewEpisodes ? L.t("プレイリスト • 自動", "Playlist • Auto") : L.t("ポッドキャスト", "Podcast"))
                            .font(.footnote).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    actions(player)
                }
                .padding(.horizontal, Kasane.Space.s4)
                .padding(.bottom, Kasane.Space.s3)
            }
            .clipped()
    }

    /// 保存（しおり。アルバムと同じ。RSS の番組はこの端末の登録）・再生。アルバムと同じ大きさのガラスの丸を1つの入れ物にまとめ、色は再生だけ。
    @ViewBuilder
    private func actions(_ player: PlayerService) -> some View {
        let playing = model.isPlayingHere && player.isPlaying
        let buttons = HStack(spacing: 10) {
            if let saved = model.saved, model.isFeed || AppServices.client.isLoggedIn {
                Button { model.toggleSaved() } label: {
                    Image(systemName: saved ? "bookmark.fill" : "bookmark").font(.title3.weight(.semibold)).modifier(GlassCircle())
                }
                .buttonStyle(.plain)
                .disabled(model.saving)
                .accessibilityLabel(saved ? L.t("ライブラリから削除", "Remove from library") : L.t("ライブラリに保存", "Save to library"))
                .accessibilityAddTraits(saved ? .isSelected : [])
            }
            Button { model.playOrPause() } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill").font(.title3).modifier(GlassCircle(tint: .accentColor))
            }
            .buttonStyle(.plain)
            .disabled(model.episodes.isEmpty)
            .accessibilityLabel(playing ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
        }
        .foregroundStyle(.white)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 10) { buttons }
        } else {
            buttons
        }
    }
}

/// 番組の1行（検索の結果）。
struct PodcastRow: View {
    var podcast: PodcastSummary
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Kasane.Space.s3) {
                Cover(url: Ui.small(podcast.thumbnailUrl), size: 48, radius: Kasane.Radius.sm)
                VStack(alignment: .leading, spacing: 2) {
                    Text(podcast.title).lineLimit(1)
                    Text([L.t("ポッドキャスト", "Podcast"), podcast.author].compactMap { $0 }.joined(separator: " • ")).subtle()
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// エピソードの1行（画像・題・「番組 • 日付」または「日付 • 長さ」・説明・聴いたところ）。押すと再生、… で曲のメニュー。
struct EpisodeRow: View {
    var episode: PodcastEpisode
    /// 番組名を出す（検索の結果）。番組のページでは出さない。
    var showShow: Bool
    var onPlay: () -> Void

    var body: some View {
        let isCurrent = AppServices.player.current?.videoId == episode.song.videoId
        HStack(alignment: .top, spacing: Kasane.Space.s3) {
            Button(action: onPlay) {
                HStack(alignment: .top, spacing: Kasane.Space.s3) {
                    // 正方形で切り抜く。エピソードの絵（16:9）は、音声の番組だと正方形の番組の絵の左右に黒い帯が焼き込まれている
                    RemoteImage(url: URL(string: episode.song.thumbnailUrl ?? ""))
                        .frame(width: showShow ? 48 : 64, height: showShow ? 48 : 64)
                        .background(Color.primary.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: Kasane.Radius.sm, style: Kasane.cornerStyle))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(episode.song.title).font(showShow ? .body : .subheadline.weight(.semibold)).lineLimit(2)
                            .foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                        let detail = showShow ? episode.showAndDate : episode.dateAndLength
                        HStack(spacing: Kasane.Space.s1) {
                            DownloadedMark(videoId: episode.song.videoId)
                            if !detail.isEmpty { Text(detail).subtle() }
                        }
                        if !showShow, let description = episode.description {
                            Text(description).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        if let percent = AppServices.feeds.progress(episode) {
                            ProgressView(value: Double(percent), total: 100).frame(maxWidth: 140)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Menu {
                SongMenuItems(song: episode.song, episode: true)
            } label: {
                Image(systemName: "ellipsis").frame(width: 32, height: 44).contentShape(Rectangle())
            }
            .foregroundStyle(.secondary)
        }
    }
}
#endif
