import AppIntents
import SwiftUI
import WidgetKit
#if os(iOS)
    import ActivityKit
#endif

// wave のウィジェット（アプリの PlugIns/WaveWidget.appex）。今の曲・選んだプレイリスト・ワンタップ再生・ロック画面の再生中（iPhone）。
// アプリが書いた置き場（WidgetStore）を読むだけで、YouTube には触れない（プレイリストの絵だけは取りに行く）。

@main
struct WaveWidgets: WidgetBundle {
    var body: some Widget {
        NowPlayingWidget()
        PlaylistWidget()
        ItemWidget()
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

/// ウィジェットの文字の大きさ。同じ名前の大きさ（.headline など）でも iPhone は Mac より2〜5pt 大きく、
/// 同じ広さのウィジェットで iPhone だけ文字が大きく見えたので、iPhone でも Mac の大きさに固定する。
private enum WidgetFont {
    #if os(iOS)
        static let title = Font.system(size: 22)
        static let title2 = Font.system(size: 17)
        static let title3 = Font.system(size: 15)
        static let headline = Font.system(size: 13, weight: .bold)
        static let subheadline = Font.system(size: 11)
        static let caption = Font.system(size: 10)
        static let caption2 = Font.system(size: 10)
    #else
        static let title = Font.title
        static let title2 = Font.title2
        static let title3 = Font.title3
        static let headline = Font.headline
        static let subheadline = Font.subheadline
        static let caption = Font.caption
        static let caption2 = Font.caption2
    #endif
}

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
                    // クリア・色付きのアイコン表示では、OS が絵を一色に塗りつぶす（真っ白になる）。絵はそのままの色で残す
                    // （白黒の写真にする desaturated は、Mac のデスクトップでボタンの中の絵を押しても意図が届かず、アプリが開くだけになった）
                    Image(widgetImage: image).resizable().widgetAccentedRenderingMode(.fullColor).aspectRatio(contentMode: .fill)
                } else {
                    Image(systemName: "music.note").font(WidgetFont.title2).foregroundStyle(.secondary)
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
/// クリア・色付きの表示（フルカラー以外）では地を置かず、OS のガラスだけにする（絵が透けて残らないように）。
private struct ArtworkBackground: View {
    var image: WidgetImage?
    @Environment(\.widgetRenderingMode) private var renderingMode

    var body: some View {
        if renderingMode != .fullColor {
            Color.clear
        } else if let image {
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
                    Text(song.title).font(WidgetFont.headline).lineLimit(1)
                    Text(song.device ?? song.artist).font(WidgetFont.caption).foregroundStyle(song.device != nil ? accent : .secondary).lineLimit(1)
                    Controls(playing: song.playing, size: 18)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .top, spacing: 14) {
                    // 高さに収まる大きさにする（収まらないと列が上下にはみ出し、上の余白がほかより狭くなる）
                    VStack(alignment: .leading, spacing: 2) {
                        ArtworkWithLike(image: entry.artwork, liked: song.liked, likeSize: 18).frame(width: 56, height: 56)
                        Spacer(minLength: 0)
                        Text(song.title).font(WidgetFont.title3.weight(.semibold)).lineLimit(1)
                        if let device = song.device {
                            Label(device, systemImage: "hifispeaker.2").font(WidgetFont.subheadline).foregroundStyle(accent).lineLimit(1)
                        } else {
                            Text(song.artist).font(WidgetFont.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Controls(playing: song.playing, size: 20)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // 右側: 次に流れる曲（押すとその曲へ飛ぶ）。見出しの下の行を上下いっぱいに等しく割り振り、下に余白を残さない
                    VStack(alignment: .leading, spacing: 0) {
                        Text(WidgetText.t("次に流れる曲", "Up next")).font(WidgetFont.caption.weight(.semibold)).foregroundStyle(.secondary)
                        ForEach(song.upNext ?? []) { item in
                            Button(intent: PlayQueueItemIntent(index: item.index, videoId: item.videoId)) {
                                HStack(spacing: 8) {
                                    Artwork(image: entry.queueArtwork[item.videoId], radius: 5).frame(width: 28, height: 28)
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(item.title).font(WidgetFont.subheadline.weight(.medium)).lineLimit(1)
                                        Text(item.artist).font(WidgetFont.caption).foregroundStyle(.secondary).lineLimit(1)
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
                Image(systemName: "music.note").font(WidgetFont.title)
                Text(WidgetText.t("何も再生していません", "Nothing playing")).font(WidgetFont.caption).foregroundStyle(.secondary)
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
    /// 再生中の曲（中・大の上に出す）とそのジャケット
    var song: WidgetNowPlaying?
    var artwork: WidgetImage?
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
        let song = WidgetStore.nowPlaying
        return PlaylistEntry(date: Date(), tiles: shown.map { PlaylistTile(playlist: $0, cover: covers[$0.id]) },
                             song: song, artwork: song.flatMap { loadImage(WidgetStore.artworkFile($0.videoId)) })
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

/// 大きさごとに並べる数（小: 2×2、中: 1段に4つ、大: 2段に4つずつ）。
private func playlistCount(_ family: WidgetFamily) -> Int {
    family == .systemLarge ? 8 : 4
}

struct PlaylistWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetStore.playlistKind, intent: SelectPlaylistIntent.self, provider: PlaylistProvider()) { entry in
            PlaylistWidgetView(entry: entry)
                .padding(widgetPadding)
                .onArtwork(entry.background)
                .containerBackground(for: .widget) { ArtworkBackground(image: entry.background) }
        }
        .configurationDisplayName(Text("プレイリスト"))
        .description(Text("最近再生したプレイリストか、選んだプレイリストを並べ、押すとすぐ再生する。"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}

extension PlaylistEntry {
    /// 地にぼかす絵（中・大は再生中のジャケット、無ければ・小は先頭の表紙）。
    var background: WidgetImage? { artwork ?? tiles.first?.cover }
}

struct PlaylistWidgetView: View {
    var entry: PlaylistEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if entry.tiles.isEmpty {
            VStack(spacing: 6) {
                Image(systemName: "music.note.list").font(WidgetFont.title)
                Text(WidgetText.t("wave を開いてログインすると、プレイリストが出ます", "Open wave and log in to see your playlists")).font(WidgetFont.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
        } else if family == .systemSmall {
            // 小は表紙だけを 2×2 に
            grid(rows: 2, columns: 2, spacing: 8)
        } else {
            // 中・大は上に再生中の曲と操作、下に表紙を寄せる
            VStack(alignment: .leading, spacing: 10) {
                nowPlaying
                Spacer(minLength: 0)
                grid(rows: family == .systemLarge ? 2 : 1, columns: 4, spacing: 10)
            }
        }
    }

    /// 再生中の曲（ジャケット・曲名・アーティストか端末名）と前へ・再生/一時停止・次へ。
    private var nowPlaying: some View {
        HStack(spacing: 10) {
            if let song = entry.song {
                Artwork(image: entry.artwork, radius: 6).frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(song.title).font(WidgetFont.headline).lineLimit(1)
                    Text(song.device ?? song.artist).font(WidgetFont.subheadline).foregroundStyle(song.device != nil ? accent : .secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                Controls(playing: song.playing, size: 18)
            } else {
                Text(WidgetText.t("何も再生していません", "Nothing playing")).font(WidgetFont.subheadline).foregroundStyle(.secondary)
                Spacer(minLength: 0)
            }
        }
    }

    private func grid(rows: Int, columns: Int, spacing: CGFloat) -> some View {
        VStack(spacing: spacing) {
            ForEach(0 ..< rows, id: \.self) { row in
                HStack(spacing: spacing) {
                    ForEach(0 ..< columns, id: \.self) { column in
                        slot(row * columns + column)
                    }
                }
            }
        }
    }

    /// i 番目の枠（足りなければ空けておく。並びの大きさをそろえる）。
    @ViewBuilder
    private func slot(_ i: Int) -> some View {
        if i < entry.tiles.count {
            let tile = entry.tiles[i]
            // 鳴らしているプレイリストは、表紙をアクセントの色で縁取り、右下に鳴っている印を出す（アプリの一覧と同じ考え）
            let current = entry.song?.sourceId == tile.playlist.id
            Button(intent: PlayPlaylistIntent(playlistId: tile.playlist.id, shuffle: false)) {
                Artwork(image: tile.cover, radius: 8)
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        if current { RoundedRectangle(cornerRadius: 8).strokeBorder(accent, lineWidth: 3) }
                    }
                    .overlay(alignment: .bottomTrailing) {
                        if current {
                            Image(systemName: entry.song?.playing == true ? "speaker.wave.2.fill" : "pause.fill")
                                .font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                                // デスクトップで OS が色を抜いて描く（色付き・クリアの表示）ときも見えるよう、暗い丸に白い絵柄にする
                                .frame(width: 22, height: 22).background(Circle().fill(Color.black.opacity(0.6)))
                                .padding(5)
                        }
                    }
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity)
        } else {
            Color.clear.aspectRatio(1, contentMode: .fit).frame(maxWidth: .infinity)
        }
    }
}

// MARK: ワンタップ再生

struct ItemEntry: TimelineEntry {
    let date: Date
    /// 選んだもの（まだ選んでいなければ nil）
    var item: WidgetItem?
    var cover: WidgetImage?
    var song: WidgetNowPlaying?
}

struct ItemProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ItemEntry {
        ItemEntry(date: Date(), item: WidgetItem(kind: .playlist, id: "", title: WidgetText.t("プレイリスト", "Playlist"), subtitle: nil, thumbnailUrl: nil))
    }

    func snapshot(for configuration: SelectItemIntent, in context: Context) async -> ItemEntry {
        // 一覧で見せるときは、まだ選んでいなくても候補の先頭で見せる
        await entry(configuration.item?.item ?? (context.isPreview ? WidgetStore.items.first : nil))
    }

    func timeline(for configuration: SelectItemIntent, in context: Context) async -> Timeline<ItemEntry> {
        Timeline(entries: [await entry(configuration.item?.item)], policy: .never)
    }

    private func entry(_ item: WidgetItem?) async -> ItemEntry {
        guard let item else { return ItemEntry(date: Date(), item: nil) }
        return ItemEntry(date: Date(), item: item, cover: await cover(item), song: WidgetStore.nowPlaying)
    }

    /// 絵は置き場に残した分を読み、無ければ取りに行って残しておく（プレイリストのウィジェットと同じ置き場）。
    private func cover(_ item: WidgetItem) async -> WidgetImage? {
        let key = "\(item.kind.rawValue)-\(item.id)"
        if let image = loadImage(WidgetStore.coverFile(key)) { return image }
        guard let raw = item.thumbnailUrl, let url = URL(string: raw), url.scheme == "https",
              let (data, _) = try? await URLSession.shared.data(from: url), let image = WidgetImage(data: data) else { return nil }
        if let file = WidgetStore.coverFile(key) { try? data.write(to: file) }
        return image
    }
}

struct ItemWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetStore.itemKind, intent: SelectItemIntent.self, provider: ItemProvider()) { entry in
            ItemWidgetView(entry: entry)
                .padding(widgetPadding)
                .onArtwork(entry.cover)
                .containerBackground(for: .widget) { ArtworkBackground(image: entry.cover) }
        }
        .configurationDisplayName(Text("ワンタップ再生"))
        .description(Text("選んだプレイリスト・アルバム・曲・ライブを、押すだけで再生する。"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}

struct ItemWidgetView: View {
    var entry: ItemEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let item = entry.item {
            let playing = item.isCurrent(entry.song) && entry.song?.playing == true
            switch family {
            case .systemSmall:
                // どこを押しても鳴らす（鳴っている間は止める）
                Button(intent: PlayItemIntent(item: item, shuffle: false)) {
                    cover(item)
                        .overlay(alignment: .bottomTrailing) { PlayMark(playing: playing, size: 26, onArtwork: true).padding(10) }
                }
                .buttonStyle(.plain)
            case .systemMedium:
                HStack(spacing: 14) {
                    Button(intent: PlayItemIntent(item: item, shuffle: false)) {
                        HStack(spacing: 14) {
                            cover(item).aspectRatio(1, contentMode: .fit)
                            names(item, titleFont: WidgetFont.title3.weight(.semibold))
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    buttons(item, playing: playing)
                }
            default:
                VStack(alignment: .leading, spacing: 12) {
                    Button(intent: PlayItemIntent(item: item, shuffle: false)) {
                        cover(item).frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    .buttonStyle(.plain)
                    HStack(spacing: 12) {
                        Button(intent: PlayItemIntent(item: item, shuffle: false)) { names(item, titleFont: WidgetFont.title2.weight(.semibold)).contentShape(Rectangle()) }
                            .buttonStyle(.plain)
                        buttons(item, playing: playing)
                    }
                }
            }
        } else {
            VStack(spacing: 6) {
                Image(systemName: "play.circle").font(WidgetFont.title)
                Text(WidgetText.t("長押しして「ウィジェットを編集」から、再生するものを選んでください", "Long-press and choose Edit Widget to pick what to play"))
                    .font(WidgetFont.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
        }
    }

    /// 絵（無ければ種類の印）。
    private func cover(_ item: WidgetItem) -> some View {
        Artwork(image: entry.cover)
            .overlay {
                if entry.cover == nil { Image(systemName: item.symbol).font(WidgetFont.title).foregroundStyle(.secondary) }
            }
    }

    /// 名前と、種類・アーティスト（アルバムの副題は種類を含む）。
    private func names(_ item: WidgetItem, titleFont: Font) -> some View {
        let subtitle = [item.kind == .live ? item.kindLabel : nil, item.kind == .playlist ? nil : item.subtitle]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " • ")
        return VStack(alignment: .leading, spacing: 2) {
            Text(item.title).font(titleFont).lineLimit(2)
            if !subtitle.isEmpty { Text(subtitle).font(WidgetFont.subheadline).foregroundStyle(.secondary).lineLimit(1) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// シャッフル（プレイリスト・アルバムだけ）と、再生ボタン。再生中の曲のウィジェット（Controls）と同じく、アイコンだけを置く。
    private func buttons(_ item: WidgetItem, playing: Bool) -> some View {
        HStack(spacing: 22) {
            if item.kind == .playlist || item.kind == .album {
                Button(intent: PlayItemIntent(item: item, shuffle: true)) {
                    Image(systemName: "shuffle").font(.system(size: 22)).frame(width: 32, height: 40).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Button(intent: PlayItemIntent(item: item, shuffle: false)) { PlayMark(playing: playing, size: 28).frame(width: 32, height: 40).contentShape(Rectangle()) }
                .buttonStyle(.plain)
        }
        .foregroundStyle(.primary)
    }
}

/// 再生（鳴っている間は一時停止）のアイコン。再生中の曲のウィジェットと同じく、丸を敷かずにそのまま置く。
/// 絵の上（小）では、明るい絵でも見えるよう白にして影を付ける。
private struct PlayMark: View {
    var playing: Bool
    var size: CGFloat
    var onArtwork = false

    var body: some View {
        Image(systemName: playing ? "pause.fill" : "play.fill")
            .font(.system(size: size))
            .foregroundStyle(onArtwork ? AnyShapeStyle(Color.white) : AnyShapeStyle(.primary))
            .shadow(color: .black.opacity(onArtwork ? 0.5 : 0), radius: 3)
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
