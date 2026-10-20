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

/// 高く評価のボタン（アプリの評価のボタンと同じ印。評価していれば✓）。
private struct LikeControl: View {
    var liked: Bool
    var size: CGFloat = 22

    var body: some View {
        Button(intent: ToggleLikeIntent()) {
            Image(systemName: liked ? "checkmark.circle.fill" : "plus.circle").font(.system(size: size))
                .foregroundStyle(liked ? accent : .primary)
        }
        .buttonStyle(.plain)
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

struct PlaylistEntry: TimelineEntry {
    let date: Date
    let playlist: WidgetPlaylist?
    let cover: WidgetImage?
}

struct PlaylistProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PlaylistEntry {
        PlaylistEntry(date: Date(), playlist: WidgetPlaylist(id: "", title: WidgetText.t("プレイリスト", "Playlist")), cover: nil)
    }

    func snapshot(for configuration: SelectPlaylistIntent, in context: Context) async -> PlaylistEntry {
        await entry(configuration)
    }

    func timeline(for configuration: SelectPlaylistIntent, in context: Context) async -> Timeline<PlaylistEntry> {
        Timeline(entries: [await entry(configuration)], policy: .never)
    }

    private func entry(_ configuration: SelectPlaylistIntent) async -> PlaylistEntry {
        let all = WidgetStore.playlists
        let chosen = configuration.playlist.flatMap { p in all.first { $0.id == p.id } ?? WidgetPlaylist(id: p.id, title: p.title) } ?? all.first
        guard let chosen else { return PlaylistEntry(date: Date(), playlist: nil, cover: nil) }
        // 絵はアプリが置いた分を読み、無ければ取りに行って置いておく
        var cover = loadImage(WidgetStore.coverFile(chosen.id))
        if cover == nil, let raw = chosen.thumbnailUrl, let url = URL(string: raw), url.scheme == "https",
           let (data, _) = try? await URLSession.shared.data(from: url)
        {
            cover = WidgetImage(data: data)
            if cover != nil, let file = WidgetStore.coverFile(chosen.id) { try? data.write(to: file) }
        }
        return PlaylistEntry(date: Date(), playlist: chosen, cover: cover)
    }
}

struct PlaylistWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetStore.playlistKind, intent: SelectPlaylistIntent.self, provider: PlaylistProvider()) { entry in
            PlaylistWidgetView(entry: entry)
                .padding(widgetPadding)
                .onArtwork(entry.cover)
                .containerBackground(for: .widget) { ArtworkBackground(image: entry.cover) }
        }
        .configurationDisplayName(Text("プレイリスト"))
        .description(Text("選んだプレイリストを、押すとすぐ再生する。"))
        .supportedFamilies([.systemSmall, .systemMedium])
        .contentMarginsDisabled()
    }
}

struct PlaylistWidgetView: View {
    var entry: PlaylistEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let playlist = entry.playlist {
            let buttons = HStack(spacing: 10) {
                Button(intent: PlayPlaylistIntent(playlistId: playlist.id, shuffle: false)) {
                    Image(systemName: "play.fill").font(.system(size: 19, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 44, height: 44).background(Circle().fill(accent))
                }
                Button(intent: PlayPlaylistIntent(playlistId: playlist.id, shuffle: true)) {
                    Image(systemName: "shuffle").font(.system(size: 16, weight: .semibold)).foregroundStyle(.primary)
                        .frame(width: 40, height: 40).background(Circle().fill(Color.primary.opacity(0.12)))
                }
            }
            .buttonStyle(.plain)
            if family == .systemSmall {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .top) {
                        Artwork(image: entry.cover).frame(width: 50, height: 50)
                        Spacer(minLength: 0)
                    }
                    Spacer(minLength: 0)
                    Text(playlist.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                    buttons
                }
            } else {
                HStack(spacing: 14) {
                    Artwork(image: entry.cover).aspectRatio(1, contentMode: .fit).frame(maxHeight: .infinity)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(playlist.title).font(.title3.weight(.semibold)).lineLimit(3)
                        Spacer(minLength: 0)
                        buttons
                    }
                    Spacer(minLength: 0)
                }
            }
        } else {
            VStack(spacing: 6) {
                Image(systemName: "music.note.list").font(.title)
                Text(WidgetText.t("wave を開いてログインすると、プレイリストを選べます", "Open wave and log in to choose a playlist")).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
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
