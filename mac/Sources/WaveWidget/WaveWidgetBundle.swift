import AppIntents
import SwiftUI
import WidgetKit
#if os(iOS)
    import ActivityKit
#endif

// wave のウィジェット（アプリの PlugIns/WaveWidget.appex）。今の曲・選んだプレイリスト・ロック画面の再生中（iPhone）。
// アプリが書いた置き場（WidgetStore）を読むだけで、YouTube には触れない（プレイリストの絵だけは取りに行く）。

@main
struct WaveWidgets: WidgetBundle {
    var body: some Widget {
        NowPlayingWidget()
        PlaylistWidget()
        #if os(iOS)
            NowPlayingLiveActivity()
        #endif
    }
}

#if os(macOS)
    typealias WidgetImage = NSImage
    extension Image {
        init(widgetImage: NSImage) { self.init(nsImage: widgetImage) }
    }
#else
    typealias WidgetImage = UIImage
    extension Image {
        init(widgetImage: UIImage) { self.init(uiImage: widgetImage) }
    }
#endif

private func loadImage(_ url: URL?) -> WidgetImage? {
    guard let url, let data = try? Data(contentsOf: url) else { return nil }
    return WidgetImage(data: data)
}

private let accent = Color(red: 0.27, green: 0.55, blue: 1.0)

/// ウィジェットの中身の余白。OS の余白（contentMargins）は Mac で上下と左右が違い、角に置いた絵の丸みがウィジェットの角と
/// 中心がずれて見えたので、OS の余白を切って四方を同じにする（ContainerRelativeShape はこの余白の分だけ丸みを小さくする）。
#if os(iOS)
    private let widgetPadding: CGFloat = 16
#else
    private let widgetPadding: CGFloat = 17
#endif

/// ジャケット（無ければ音符）。枠の大きさで切り抜く（映像の 16:9 の絵など、正方形でない絵を枠いっぱいに広げても、はみ出さない）。
/// radius を渡さなければ、ウィジェットの角と中心をそろえた丸み（ContainerRelativeShape）にする（ウィジェットの角に置く絵）。
private struct Artwork: View {
    var image: WidgetImage?
    var radius: CGFloat?

    var body: some View {
        // 地（枠の大きさを決める）の上に重ねる。ZStack に入れると、広げた絵の大きさに枠が引っぱられて切り抜きが効かない
        shape.fill(Color.primary.opacity(0.08))
            .overlay {
                if let image {
                    // クリア・色付きのアイコン表示では、OS が絵を一色に塗りつぶす（真っ白になる）。絵は白黒の写真として残す
                    Image(widgetImage: image).resizable().widgetAccentedRenderingMode(.desaturated).aspectRatio(contentMode: .fill)
                } else {
                    Image(systemName: "music.note").font(.title2).foregroundStyle(.secondary)
                }
            }
            .clipShape(shape)
    }

    private var shape: AnyShape {
        radius.map { AnyShape(RoundedRectangle(cornerRadius: $0)) } ?? AnyShape(ContainerRelativeShape())
    }
}

/// ウィジェットの地（ライト・ダークの表示に合わせる。クリア・色付きの表示では OS が置き換える）。
private var widgetBackground: Color {
    #if os(iOS)
        Color(uiColor: .secondarySystemBackground)
    #else
        Color(nsColor: .windowBackgroundColor)
    #endif
}

/// 絵があれば、絵をぼかして暗くした地（上の文字は白で出す）。無ければ普通の地。
private struct ArtworkBackground: View {
    var image: WidgetImage?

    var body: some View {
        if let image {
            Image(widgetImage: image).resizable().aspectRatio(contentMode: .fill)
                .blur(radius: 30, opaque: true)
                .overlay(Color.black.opacity(0.35))
        } else {
            widgetBackground
        }
    }
}

/// ぼかした絵の地の上では、ライトモードでも文字を白にする（暗い地として描く）。クリア・色付きの表示は OS が地を置き換えるので、そのまま。
private struct OnArtwork: ViewModifier {
    var image: WidgetImage?
    @Environment(\.widgetRenderingMode) private var mode

    func body(content: Content) -> some View {
        if image != nil && mode == .fullColor { content.environment(\.colorScheme, .dark) } else { content }
    }
}

extension View {
    fileprivate func onArtwork(_ image: WidgetImage?) -> some View { modifier(OnArtwork(image: image)) }
}

// MARK: 今の曲

struct NowPlayingEntry: TimelineEntry {
    let date: Date
    let song: WidgetNowPlaying?
    let artwork: WidgetImage?
    /// 次に流れる曲のジャケット（videoId ごと）。
    var queueArtwork: [String: WidgetImage] = [:]
}

struct NowPlayingProvider: TimelineProvider {
    func placeholder(in context: Context) -> NowPlayingEntry {
        NowPlayingEntry(date: Date(), song: WidgetNowPlaying(videoId: "", title: "wave", artist: "", playing: false), artwork: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (NowPlayingEntry) -> Void) { completion(entry()) }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NowPlayingEntry>) -> Void) {
        // アプリが曲や状態の変わり目に読み直させるので、こちらからは取りに行かない
        completion(Timeline(entries: [entry()], policy: .never))
    }

    private func entry() -> NowPlayingEntry {
        let song = WidgetStore.nowPlaying
        var queueArtwork: [String: WidgetImage] = [:]
        for item in song?.upNext ?? [] {
            queueArtwork[item.videoId] = loadImage(WidgetStore.artworkFile(item.videoId))
        }
        return NowPlayingEntry(date: Date(), song: song, artwork: song.flatMap { loadImage(WidgetStore.artworkFile($0.videoId)) }, queueArtwork: queueArtwork)
    }
}

struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetStore.nowPlayingKind, provider: NowPlayingProvider()) { entry in
            NowPlayingWidgetView(entry: entry)
                .padding(widgetPadding)
                .onArtwork(entry.artwork)
                .containerBackground(for: .widget) { ArtworkBackground(image: entry.artwork) }
                .widgetURL(URL(string: "wave://nowplaying"))
        }
        .configurationDisplayName(Text("再生中"))
        .description(Text("今の曲と次に流れる曲、再生の操作、高く評価。"))
        .supportedFamilies([.systemSmall, .systemMedium])
        .contentMarginsDisabled()
    }
}

/// 評価のボタン（アプリと同じ）。＋は押すと高く評価し、✓（評価済み・プレイリストに入っている）はアプリを開いてプレイリストに追加する画面を出す。
private struct LikeControl: View {
    var liked: Bool
    var size: CGFloat = 22

    var body: some View {
        if liked {
            Link(destination: URL(string: "wave://add-to-playlist")!) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: size)).foregroundStyle(accent)
            }
        } else {
            Button(intent: ToggleLikeIntent()) {
                Image(systemName: "plus.circle").font(.system(size: size)).foregroundStyle(.primary)
            }
            .buttonStyle(.plain)
        }
    }
}

/// ジャケットの右下に高く評価のボタンを重ねたもの（評価のボタンを出せないときは絵だけ）。
private struct ArtworkWithLike: View {
    var image: WidgetImage?
    var liked: Bool?
    var likeSize: CGFloat

    var body: some View {
        Artwork(image: image)
            .overlay(alignment: .bottomTrailing) {
                if let liked {
                    // 絵の上でも見えるよう、暗い丸を敷いて白（評価済みは色）で出す
                    LikeControl(liked: liked, size: likeSize)
                        .environment(\.colorScheme, .dark)
                        .background(Circle().fill(Color.black.opacity(0.45)))
                        .padding(3)
                }
            }
    }
}

private struct Controls: View {
    var playing: Bool
    var size: CGFloat = 22

    var body: some View {
        HStack(spacing: size) {
            Button(intent: PreviousTrackIntent()) { Image(systemName: "backward.fill") }
            Button(intent: TogglePlayIntent()) { Image(systemName: playing ? "pause.fill" : "play.fill").font(.system(size: size + 6)) }
            Button(intent: NextTrackIntent()) { Image(systemName: "forward.fill") }
        }
        .font(.system(size: size))
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
    }
}

struct NowPlayingWidgetView: View {
    var entry: NowPlayingEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let song = entry.song {
            if family == .systemSmall {
                VStack(alignment: .leading, spacing: 6) {
                    ArtworkWithLike(image: entry.artwork, liked: song.liked, likeSize: 16).frame(width: 54, height: 54)
                    Spacer(minLength: 0)
                    Text(song.title).font(.headline).lineLimit(1)
                    Text(song.device ?? song.artist).font(.caption).foregroundStyle(song.device != nil ? accent : .secondary).lineLimit(1)
                    Controls(playing: song.playing, size: 18)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .top, spacing: 14) {
                    // 高さに収まる大きさにする（収まらないと列が上下にはみ出し、上の余白がほかより狭くなる）
                    VStack(alignment: .leading, spacing: 2) {
                        ArtworkWithLike(image: entry.artwork, liked: song.liked, likeSize: 18).frame(width: 56, height: 56)
                        Spacer(minLength: 0)
                        Text(song.title).font(.title3.weight(.semibold)).lineLimit(1)
                        if let device = song.device {
                            Label(device, systemImage: "hifispeaker.2").font(.subheadline).foregroundStyle(accent).lineLimit(1)
                        } else {
                            Text(song.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Controls(playing: song.playing, size: 20)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // 右側: 次に流れる曲（押すとその曲へ飛ぶ）。見出しの下の行を上下いっぱいに等しく割り振り、下に余白を残さない
                    VStack(alignment: .leading, spacing: 0) {
                        Text(WidgetText.t("次に流れる曲", "Up next")).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        ForEach(song.upNext ?? []) { item in
                            Button(intent: PlayQueueItemIntent(index: item.index, videoId: item.videoId)) {
                                HStack(spacing: 8) {
                                    Artwork(image: entry.queueArtwork[item.videoId], radius: 5).frame(width: 28, height: 28)
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(item.title).font(.subheadline.weight(.medium)).lineLimit(1)
                                        Text(item.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                }
                                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
            }
        } else {
            VStack(spacing: 6) {
                Image(systemName: "music.note").font(.title)
                Text(WidgetText.t("何も再生していません", "Nothing playing")).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: プレイリスト

/// 並べるプレイリストの1つ（絵つき）。
struct PlaylistTile: Identifiable {
    let playlist: WidgetPlaylist
    let cover: WidgetImage?
    var id: String { playlist.id }
}

struct PlaylistEntry: TimelineEntry {
    let date: Date
    let tiles: [PlaylistTile]
    /// 最近再生した順で並べたか（見出しの文言を変える）
    var recent = true
}

struct PlaylistProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PlaylistEntry {
        PlaylistEntry(date: Date(), tiles: (0 ..< playlistCount(context.family)).map {
            PlaylistTile(playlist: WidgetPlaylist(id: "\($0)", title: WidgetText.t("プレイリスト", "Playlist")), cover: nil)
        })
    }

    func snapshot(for configuration: SelectPlaylistIntent, in context: Context) async -> PlaylistEntry {
        await entry(configuration, context.family)
    }

    func timeline(for configuration: SelectPlaylistIntent, in context: Context) async -> Timeline<PlaylistEntry> {
        Timeline(entries: [await entry(configuration, context.family)], policy: .never)
    }

    private func entry(_ configuration: SelectPlaylistIntent, _ family: WidgetFamily) async -> PlaylistEntry {
        // アプリが書いた一覧は、ライブラリの「最近」と同じ並び（最近再生した順）
        let all = WidgetStore.playlists
        let chosen = configuration.source == .chosen
            ? (configuration.playlists ?? []).map { p in all.first { $0.id == p.id } ?? WidgetPlaylist(id: p.id, title: p.title) }
            : []
        // 選ぶにしていても1つも選んでいなければ、最近再生した順で埋める
        let shown = Array((chosen.isEmpty ? all : chosen).prefix(playlistCount(family)))
        let covers = await withTaskGroup(of: (String, WidgetImage?).self) { group in
            for playlist in shown { group.addTask { (playlist.id, await cover(playlist)) } }
            var found: [String: WidgetImage] = [:]
            for await (id, image) in group { found[id] = image }
            return found
        }
        return PlaylistEntry(date: Date(), tiles: shown.map { PlaylistTile(playlist: $0, cover: covers[$0.id]) }, recent: chosen.isEmpty)
    }

    /// 絵はアプリが置いた分を読み、無ければ取りに行って置いておく。
    private func cover(_ playlist: WidgetPlaylist) async -> WidgetImage? {
        if let image = loadImage(WidgetStore.coverFile(playlist.id)) { return image }
        guard let raw = playlist.thumbnailUrl, let url = URL(string: raw), url.scheme == "https",
              let (data, _) = try? await URLSession.shared.data(from: url), let image = WidgetImage(data: data) else { return nil }
        if let file = WidgetStore.coverFile(playlist.id) { try? data.write(to: file) }
        return image
    }
}

/// 大きさごとに並べる数（小: 2つ、中: 1段に4つ、大: 2段に4つずつ）。
private func playlistCount(_ family: WidgetFamily) -> Int {
    switch family {
    case .systemSmall: 2
    case .systemLarge: 8
    default: 4
    }
}

struct PlaylistWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetStore.playlistKind, intent: SelectPlaylistIntent.self, provider: PlaylistProvider()) { entry in
            PlaylistWidgetView(entry: entry)
                .padding(widgetPadding)
                .onArtwork(entry.tiles.first?.cover)
                .containerBackground(for: .widget) { ArtworkBackground(image: entry.tiles.first?.cover) }
        }
        .configurationDisplayName(Text("プレイリスト"))
        .description(Text("最近再生したプレイリストか、選んだプレイリストを並べ、押すとすぐ再生する。"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}

struct PlaylistWidgetView: View {
    var entry: PlaylistEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if entry.tiles.isEmpty {
            VStack(spacing: 6) {
                Image(systemName: "music.note.list").font(.title)
                Text(WidgetText.t("wave を開いてログインすると、プレイリストが出ます", "Open wave and log in to see your playlists")).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
        } else {
            // 見出しと絵・名前をまとめて下に寄せる（絵の大きさは幅で決まるので、余った高さは上に回す）。小は1段に2つ、中は1段に4つ、大は2段に4つずつ
            let rows = family == .systemLarge ? 2 : 1
            let columns = family == .systemSmall ? 2 : 4
            VStack(alignment: .leading, spacing: 8) {
                Spacer(minLength: 0)
                Text(heading)
                    .font(family == .systemSmall ? .headline : .title3.weight(.semibold)).lineLimit(1)
                Grid(horizontalSpacing: 10, verticalSpacing: 12) {
                    ForEach(0 ..< rows, id: \.self) { row in
                        GridRow {
                            ForEach(0 ..< columns, id: \.self) { column in
                                slot(row * columns + column)
                            }
                        }
                    }
                }
            }
        }
    }

    /// 見出し（小は幅が無いので短く）。
    private var heading: String {
        if !entry.recent { return WidgetText.t("プレイリスト", "Playlists") }
        return family == .systemSmall ? WidgetText.t("最近再生", "Recent") : WidgetText.t("最近再生したプレイリスト", "Recently played")
    }

    /// i 番目の枠（足りなければ空けておく。並びの大きさをそろえる）。
    @ViewBuilder
    private func slot(_ i: Int) -> some View {
        if i < entry.tiles.count {
            let tile = entry.tiles[i]
            Button(intent: PlayPlaylistIntent(playlistId: tile.playlist.id, shuffle: false)) {
                VStack(alignment: .leading, spacing: 4) {
                    Artwork(image: tile.cover, radius: 8).aspectRatio(1, contentMode: .fit)
                    Text(tile.playlist.title).font(.subheadline.weight(.medium)).lineLimit(1)
                }
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity, alignment: .top)
        } else {
            Color.clear.frame(maxWidth: .infinity)
        }
    }
}

// MARK: ロック画面・ダイナミックアイランド（iPhone）

#if os(iOS)
    struct NowPlayingLiveActivity: Widget {
        var body: some WidgetConfiguration {
            ActivityConfiguration(for: NowPlayingAttributes.self) { context in
                LockScreenView(state: context.state)
                    .padding(14)
                    .activityBackgroundTint(Color.black.opacity(0.75))
                    .activitySystemActionForegroundColor(.white)
                    .widgetURL(URL(string: "wave://nowplaying"))
            } dynamicIsland: { context in
                let state = context.state
                let art = loadImage(WidgetStore.artworkFile(state.videoId))
                return DynamicIsland {
                    DynamicIslandExpandedRegion(.leading) {
                        Artwork(image: art, radius: 8).frame(width: 52, height: 52)
                    }
                    DynamicIslandExpandedRegion(.center) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(state.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text(state.device ?? state.artist).font(.caption).foregroundStyle(state.device != nil ? accent : .secondary).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    DynamicIslandExpandedRegion(.trailing) {
                        Link(destination: URL(string: "wave://add-to-playlist")!) {
                            Image(systemName: "plus.circle").font(.title2)
                        }
                    }
                    DynamicIslandExpandedRegion(.bottom) {
                        VStack(spacing: 6) {
                            Controls(playing: state.playing, size: 20)
                            if let next = state.nextTitle {
                                Text(WidgetText.t("次: ", "Next: ") + next).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                    }
                } compactLeading: {
                    Artwork(image: art, radius: 4).frame(width: 22, height: 22)
                } compactTrailing: {
                    Image(systemName: state.playing ? "waveform" : "pause.fill").foregroundStyle(accent)
                } minimal: {
                    Image(systemName: state.playing ? "waveform" : "pause.fill").foregroundStyle(accent)
                }
                .widgetURL(URL(string: "wave://nowplaying"))
            }
        }
    }

    private struct LockScreenView: View {
        var state: NowPlayingAttributes.ContentState

        var body: some View {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    Artwork(image: loadImage(WidgetStore.artworkFile(state.videoId)), radius: 8).frame(width: 56, height: 56)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(state.title).font(.headline).lineLimit(1)
                        Text(state.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        if let device = state.device {
                            Label(device, systemImage: "hifispeaker.2").font(.caption).foregroundStyle(accent).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    // プレイリストに追加する（選ぶ画面はアプリで開く）
                    Link(destination: URL(string: "wave://add-to-playlist")!) {
                        Image(systemName: "plus.circle").font(.title2).foregroundStyle(.white)
                    }
                }
                HStack {
                    if let next = state.nextTitle {
                        Label(next, systemImage: "text.line.first.and.arrowtriangle.forward").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Controls(playing: state.playing)
                }
            }
            .foregroundStyle(.white)
        }
    }
#endif
