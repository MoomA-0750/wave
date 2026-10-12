#if os(iOS)
import SwiftUI
import WaveCore

/// ポッドキャストの番組のページ（Android の PodcastScreen と同じ並び: 画像・題・配信者・説明・保存、再生ボタン、新しい順のエピソード）。
/// エピソードを押すと、読み込んだエピソードの一覧をその位置から流す（曲の一覧と同じ）。
struct PodcastView: View {
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
        List {
            header(player).listRowSeparator(.hidden).listRowBackground(Color.clear)
            if loading {
                ProgressView().frame(maxWidth: .infinity).padding().listRowSeparator(.hidden)
            } else if failed && !isNewEpisodes {
                LoadState(loading: false, failure: L.t("ポッドキャストを読み込めませんでした", "Couldn't load the podcast")) {
                    Task { await load() }
                }.listRowSeparator(.hidden)
            } else if episodes.isEmpty {
                Text(L.t("エピソードはありません", "No episodes")).subtle().listRowSeparator(.hidden)
            }
            ForEach(Array(episodes.enumerated()), id: \.element.id) { i, episode in
                EpisodeRow(episode: episode, showShow: false) { play(i) }
                    .onAppear { if i == episodes.count - 1 { loadMore() } }
                    .swipeActions(edge: .leading) {
                        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(episode.song) }.tint(.indigo)
                    }
            }
            if loadingMore { ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden) }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(alignment: .top) {
            LinearGradient(colors: [(gradient ?? .clear).opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 460)
                .ignoresSafeArea()
        }
        .navigationTitle(page?.title ?? summary.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { if page == nil { await load() } }
    }

    /// 「新しいエピソード」は、番組を保存していないと番組のページの形で返らない（エピソードが無いだけとして出す）。
    private var isNewEpisodes: Bool { summary.browseId == MusicRepository.newEpisodesBrowse }

    private func header(_ player: PlayerService) -> some View {
        let playingHere = player.sourceId == summary.playlistId && player.current != nil
        return VStack(spacing: 8) {
            Group {
                if isNewEpisodes && page?.thumbnailUrl == nil {
                    NewEpisodesCover(size: 220, radius: 10)
                } else {
                    Cover(url: Ui.large(page?.thumbnailUrl ?? summary.thumbnailUrl), size: 220, radius: 10)
                }
            }
                .shadow(color: .black.opacity(0.25), radius: 16, y: 8)
                .padding(.bottom, 8)
            Text(page?.title ?? summary.title).font(.title2.weight(.bold)).multilineTextAlignment(.center).lineLimit(3)
            if let author = page?.author ?? summary.author { Text(author).font(.headline).foregroundStyle(Color.accentColor) }
            if let description = page?.description {
                // 説明は短く出し、押すと全部を出す
                Text(description).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                    .lineLimit(expanded ? nil : 3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture { withAnimation { expanded.toggle() } }
            }
            HStack(spacing: 12) {
                Button {
                    if playingHere { player.togglePlay() } else { play(0) }
                } label: {
                    Label(playingHere && player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"),
                          systemImage: playingHere && player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(episodes.isEmpty)
                if let saved = page?.saved, AppServices.client.isLoggedIn {
                    Button { toggleSaved() } label: {
                        Label(saved ? L.t("保存済み", "Saved") : L.t("ライブラリに保存", "Save"), systemImage: saved ? "checkmark" : "plus")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .disabled(saving)
                }
            }
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }

    private func load() async {
        loading = true
        failed = false
        let result = (try? await AppServices.music.podcast(summary.browseId)) ?? .failed(.network)
        loading = false
        guard let value = result.value else {
            failed = true
            return
        }
        page = value
        episodes = value.episodes
        continuation = value.continuation
        gradient = await Artwork.dominantColor(value.thumbnailUrl)
    }

    private func loadMore() {
        guard !loadingMore, let token = continuation, let page else { return }
        loadingMore = true
        Task {
            defer { loadingMore = false }
            let result = try? await AppServices.music.podcastEpisodes(continuation: token, show: ArtistRef(name: page.title, browseId: nil), showBrowseId: page.browseId)
            guard let more = result?.value else { return }
            let known = Set(episodes.map(\.id))
            let fresh = more.items.filter { !known.contains($0.id) }
            episodes += fresh
            continuation = more.continuation != token ? more.continuation : nil
            // この番組を流しているなら、読めた分を後ろに足す
            if AppServices.player.sourceId == summary.playlistId { AppServices.player.appendFrom(fresh.map(\.song)) }
        }
    }

    /// その位置から、読み込んだエピソードを流す（出どころは番組のプレイリスト）。
    private func play(_ index: Int) {
        guard !episodes.isEmpty else { return }
        AppServices.player.playList(episodes.map(\.song), start: index, source: summary.playlistId)
    }

    /// 保存の切り替え。書けたら表示を変える（アーティストの登録と同じく楽観的に変えない）。
    private func toggleSaved() {
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

/// 番組の1行（検索の結果）。
struct PodcastRow: View {
    var podcast: PodcastSummary
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Cover(url: Ui.small(podcast.thumbnailUrl), size: 48, radius: 4)
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
        HStack(alignment: .top, spacing: 12) {
            Button(action: onPlay) {
                HStack(alignment: .top, spacing: 12) {
                    RemoteImage(url: URL(string: episode.song.thumbnailUrl ?? ""))
                        .frame(width: showShow ? 48 : 96, height: showShow ? 48 : 54)
                        .background(Color.primary.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(episode.song.title).font(showShow ? .body : .subheadline.weight(.semibold)).lineLimit(2)
                            .foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                        let detail = showShow ? episode.showAndDate : episode.dateAndLength
                        if !detail.isEmpty { Text(detail).subtle() }
                        if !showShow, let description = episode.description {
                            Text(description).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        if let percent = episode.progressPercent {
                            ProgressView(value: Double(percent), total: 100).frame(maxWidth: 140)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Menu {
                SongMenuItems(song: episode.song)
            } label: {
                Image(systemName: "ellipsis").frame(width: 32, height: 44).contentShape(Rectangle())
            }
            .foregroundStyle(.secondary)
        }
    }
}
#endif
