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

// MARK: 今の曲

struct NowPlayingEntry: TimelineEntry {
    let date: Date
    let song: WidgetNowPlaying?
    let artwork: WidgetImage?
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
        return NowPlayingEntry(date: Date(), song: song, artwork: song.flatMap { loadImage(WidgetStore.artworkFile($0.videoId)) })
    }
}

struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetStore.nowPlayingKind, provider: NowPlayingProvider()) { entry in
            NowPlayingWidgetView(entry: entry)
                .containerBackground(for: .widget) { widgetBackground }
                .widgetURL(URL(string: "wave://nowplaying"))
        }
        .configurationDisplayName("再生中")
        .description("今の曲と、再生・一時停止・曲送り。")
        .supportedFamilies([.systemSmall, .systemMedium])
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
                    Artwork(image: entry.artwork).frame(width: 56, height: 56)
                    Spacer(minLength: 0)
                    Text(song.title).font(.caption.weight(.semibold)).lineLimit(1)
                    Text(song.device ?? song.artist).font(.caption2).foregroundStyle(song.device != nil ? accent : .secondary).lineLimit(1)
                    Controls(playing: song.playing, size: 18)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(spacing: 14) {
                    // 上・左・下の余白いっぱいに広げて、絵の角をウィジェットの角にそろえる
                    Artwork(image: entry.artwork).aspectRatio(1, contentMode: .fit).frame(maxHeight: .infinity)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(song.title).font(.headline).lineLimit(2)
                        Text(song.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        if let device = song.device {
                            Label(device, systemImage: "hifispeaker.2").font(.caption).foregroundStyle(accent).lineLimit(1)
                        } else if let next = song.nextTitle {
                            Text("次: \(next)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Controls(playing: song.playing)
                    }
                    Spacer(minLength: 0)
                }
            }
        } else {
            VStack(spacing: 6) {
                Image(systemName: "music.note").font(.title)
                Text("何も再生していません").font(.caption).foregroundStyle(.secondary)
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
        PlaylistEntry(date: Date(), playlist: WidgetPlaylist(id: "", title: "プレイリスト"), cover: nil)
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
                .containerBackground(for: .widget) { widgetBackground }
        }
        .configurationDisplayName("プレイリスト")
        .description("選んだプレイリストを、押すとすぐ再生する。")
        .supportedFamilies([.systemSmall, .systemMedium])
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
                        Artwork(image: entry.cover).frame(width: 56, height: 56)
                        Spacer(minLength: 0)
                    }
                    Spacer(minLength: 0)
                    Text(playlist.title).font(.caption.weight(.semibold)).lineLimit(2)
                    buttons
                }
            } else {
                HStack(spacing: 14) {
                    Artwork(image: entry.cover).aspectRatio(1, contentMode: .fit).frame(maxHeight: .infinity)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(playlist.title).font(.headline).lineLimit(3)
                        Spacer(minLength: 0)
                        buttons
                    }
                    Spacer(minLength: 0)
                }
            }
        } else {
            VStack(spacing: 6) {
                Image(systemName: "music.note.list").font(.title)
                Text("wave を開いてログインすると、プレイリストを選べます").font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
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
                                Text("次: \(next)").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
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
