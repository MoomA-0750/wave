#if os(macOS)
import SwiftUI
import WaveCore

/// 曲の一覧の行（Spotify の表と同じ列: # / タイトル / アルバム / 時間 / …）。ダブルクリックで再生、右クリックで曲のメニュー。
struct TrackRowView: View {
    var row: TrackRow
    var selected = false
    var removeFromPlaylist: (() -> Void)?
    var onPlay: () -> Void
    var onSelect: () -> Void = {}

    @State private var hover = false

    var body: some View {
        let player = AppServices.player!
        let isCurrent = player.current?.videoId == row.song.videoId
        let isLiked = AppServices.library.isLiked(row.song.videoId)
        HStack(spacing: 12) {
            Group {
                if isCurrent {
                    Image(systemName: "speaker.wave.2.fill").font(.system(size: 12)).foregroundStyle(Color.accentColor)
                } else {
                    Text("\(row.number)").foregroundStyle(.secondary).monospacedDigit()
                }
            }
            .frame(width: 32, alignment: .trailing)
            HStack(spacing: 12) {
                if row.showArtwork { Cover(url: Ui.small(row.song.thumbnailUrl), size: 40, radius: 4) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.song.title).lineLimit(1).foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                    Text(row.song.artistsText).subtle()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .layoutPriority(3)
            if row.showAlbum {
                Text(row.albumTitle).lineLimit(1).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .layoutPriority(2)
            }
            HStack(spacing: 8) {
                // 高く評価した曲は長さの左に✓（Spotify と同じ）
                if isLiked {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 13)).foregroundStyle(Color.accentColor).help(L.t("高く評価済み", "Liked"))
                }
                if row.song.isLive {
                    LiveBadge()
                } else {
                    Text(Ui.duration(row.song.durationSeconds)).foregroundStyle(.secondary).monospacedDigit()
                }
            }
            .frame(width: 80, alignment: .trailing)
            Menu {
                SongMenuItems(song: row.song, removeFromPlaylist: removeFromPlaylist)
            } label: {
                Image(systemName: "ellipsis")
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .frame(width: 36)
            .opacity(hover || selected ? 1 : 0.0001)
            .help(L.t("その他", "More"))
        }
        .padding(.horizontal, 12)
        .frame(height: 56)
        .background(RoundedRectangle(cornerRadius: 6).fill(selected ? Color.primary.opacity(0.12) : hover ? Color.primary.opacity(0.06) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(count: 2, perform: onPlay)
        .simultaneousGesture(TapGesture().onEnded(onSelect))
        .contextMenu { SongMenuItems(song: row.song, removeFromPlaylist: removeFromPlaylist) }
    }
}

/// 一覧の上の見出しの行。
struct TrackHeader: View {
    var showAlbum: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Text("#").frame(width: 32, alignment: .trailing)
                Text(L.t("タイトル", "Title")).frame(maxWidth: .infinity, alignment: .leading).layoutPriority(3)
                if showAlbum { Text(L.t("アルバム", "Album")).frame(maxWidth: .infinity, alignment: .leading).layoutPriority(2) }
                Image(systemName: "clock").frame(width: 80, alignment: .trailing)
                Spacer().frame(width: 36)
            }
            .subtle()
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            Divider()
        }
    }
}

/// 棚のカード（Spotify と同じ: 画像・名前・補足。乗せると再生ボタンが出る）。
struct MediaCard: View {
    var item: HomeItem
    @State private var hover = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .bottomTrailing) {
                Cover(url: Ui.img(item.thumbnailUrl), size: 160, round: item.isArtist)
                PlayCircleButton(size: 44, action: { AppServices.nav.play(item) }) {
                    Image(systemName: "play.fill").font(.system(size: 16))
                }
                .shadow(radius: 4)
                .padding(6)
                .opacity(hover ? 1 : 0)
                .animation(.easeOut(duration: 0.15), value: hover)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.body.weight(.semibold)).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .frame(width: 160, alignment: .leading)
        }
        .padding(8)
        .frame(width: 176)
        .background(RoundedRectangle(cornerRadius: 8).fill(hover ? Color.primary.opacity(0.07) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture { AppServices.nav.openCard(item) }
        .contextMenu {
            if case .song(let song) = item { SongMenuItems(song: song) }
        }
    }

    private var subtitle: String {
        if case .artist = item { return L.t("アーティスト", "Artist") }
        return item.subtitle ?? ""
    }
}

/// 横に流れる棚（見出しと、左右に送るボタン）。
struct ShelfView: View {
    var title: String
    var strapline: String?
    var items: [HomeItem]

    @State private var position = ScrollPosition(idType: Int.self)
    @State private var first = 0
    /// 見えている最後のカード（分かるまでは -1。送るボタンは、その向きに続きがあるときだけ出す）。
    @State private var last = -1

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 0) {
                    if let strapline, !strapline.isEmpty { Text(strapline).subtle() }
                    Text(title).subtitleStyle().lineLimit(1)
                }
                Spacer()
                // 押せないほうは見えなくする（場所は残し、見出しの高さが変わらないようにする）
                Button { scroll(-4) } label: { Image(systemName: "chevron.left").font(.system(size: 11, weight: .semibold)) }
                    .buttonStyle(IconButtonStyle()).help(L.t("前へ", "Previous"))
                    .opacity(first > 0 ? 1 : 0).disabled(first <= 0)
                Button { scroll(4) } label: { Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)) }
                    .buttonStyle(IconButtonStyle()).help(L.t("次へ", "Next"))
                    .opacity(canScrollRight ? 1 : 0).disabled(!canScrollRight)
            }
            .padding(.horizontal, 8)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                        MediaCard(item: item).id(i)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollPosition($position)
            .onScrollTargetVisibilityChange(idType: Int.self) { visible in
                first = visible.min() ?? 0
                last = visible.max() ?? -1
            }
        }
    }

    private var canScrollRight: Bool { last >= 0 && last < items.count - 1 }

    private func scroll(_ by: Int) {
        let target = min(max(0, first + by), max(0, items.count - 1))
        withAnimation(.easeOut(duration: 0.3)) { position.scrollTo(id: target, anchor: .leading) }
    }
}
#endif
