#if os(iOS)
import SwiftUI
import WaveCore

/// Liquid Glass の板（iOS 26 より前はすりガラス）。
struct GlassCard: ViewModifier {
    var radius: CGFloat = 16
    var interactive = false

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(interactive ? .regular.interactive() : .regular, in: RoundedRectangle(cornerRadius: radius))
        } else {
            content.background(.regularMaterial, in: RoundedRectangle(cornerRadius: radius))
        }
    }
}

extension View {
    func glassCard(_ radius: CGFloat = 16, interactive: Bool = false) -> some View { modifier(GlassCard(radius: radius, interactive: interactive)) }

    /// 小さな灰色の文字。
    func subtle() -> some View { font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
}

/// ジャケットの枠（アーティストは丸、ほかは角の少し丸い四角。Android と同じ）。
struct Cover: View {
    var url: URL?
    var size: CGFloat
    var round = false
    var radius: CGFloat = 6

    var body: some View {
        RemoteImage(url: url)
            .frame(width: size, height: size)
            .background(Color.primary.opacity(0.08))
            .clipShape(round ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: radius)))
    }
}

/// 高く評価した曲の絵（紫から緑のグラデーションにハート）。
struct LikedCover: View {
    var size: CGFloat
    var radius: CGFloat = 6

    var body: some View {
        ZStack {
            Ui.likedGradient
            Image(systemName: "heart.fill").font(.system(size: size * 0.36)).foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: radius))
    }
}

/// 曲の一覧の1行（Android の SongRow と同じ: ジャケット・曲名・アーティスト・評価済みの印・「…」）。押すと再生、長押しでメニュー。
struct SongRow: View {
    var song: Song
    /// 番号（アルバムの曲順など）。nil ならジャケットを出す。
    var number: Int?
    var removeFromPlaylist: (() -> Void)?
    var onPlay: () -> Void

    var body: some View {
        let player = AppServices.player!
        let isCurrent = player.current?.videoId == song.videoId
        HStack(spacing: 12) {
            if let number {
                Text("\(number)").foregroundStyle(.secondary).monospacedDigit().frame(width: 28)
            } else {
                Cover(url: Ui.small(song.thumbnailUrl), size: 48, radius: 4)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).lineLimit(1).foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                HStack(spacing: 4) {
                    if song.isLive { LiveBadge(font: .caption2.weight(.bold), dot: 6) }
                    if AppServices.library.isLiked(song.videoId) {
                        Image(systemName: "checkmark.circle.fill").font(.caption2).foregroundStyle(Color.accentColor)
                    }
                    Text(song.artistsText).subtle()
                }
            }
            Spacer(minLength: 0)
            Menu {
                SongMenuItems(song: song, removeFromPlaylist: removeFromPlaylist)
            } label: {
                Image(systemName: "ellipsis").frame(width: 32, height: 44).contentShape(Rectangle())
            }
            .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onPlay)
        .contextMenu { SongMenuItems(song: song, removeFromPlaylist: removeFromPlaylist) }
    }
}

/// 曲のメニュー（Android の SongMenu と同じ並び）。
struct SongMenuItems: View {
    var song: Song
    var removeFromPlaylist: (() -> Void)?
    var queueIndex: Int?

    var body: some View {
        let player = AppServices.player!
        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(song) }
        Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.addToQueue(song) }
        if let queueIndex {
            Button(L.t("キューから削除", "Remove from queue"), systemImage: "minus.circle", role: .destructive) { player.removeAt(queueIndex) }
        }
        Button(L.t("プレイリストに追加", "Add to playlist"), systemImage: "plus") { Dialogs.shared.addToPlaylist(song) }
            .disabled(!AppServices.client.isLoggedIn)
        if let removeFromPlaylist {
            Button(L.t("このプレイリストから削除", "Remove from this playlist"), systemImage: "trash", role: .destructive, action: removeFromPlaylist)
        }
        Button(L.t("曲のラジオを再生", "Go to song radio"), systemImage: "dot.radiowaves.left.and.right") { player.playSong(song) }
        if let albumId = song.album?.browseId {
            Button(L.t("アルバムへ移動", "Go to album"), systemImage: "square.stack") { AppServices.nav.album(albumId) }
        }
        ForEach(song.artists.filter { $0.browseId != nil }, id: \.self) { artist in
            Button(song.artists.count > 1 ? L.t("アーティストへ移動: \(artist.name)", "Go to artist: \(artist.name)") : L.t("アーティストへ移動", "Go to artist"),
                   systemImage: "person") { AppServices.nav.artist(artist.browseId!) }
        }
        Button(L.t("曲の画面を開く", "Open song"), systemImage: "music.note") { AppServices.nav.song(song) }
        ShareLink(item: SongActions.url(song), subject: Text(song.title), message: Text(SongActions.shareText(song))) {
            Label(L.t("共有", "Share"), systemImage: "square.and.arrow.up")
        }
    }
}

/// 評価のボタン（＋と✓。Android・Mac と同じ動き）。
struct LikeButtonView: View {
    var size: CGFloat = 22

    var body: some View {
        let player = AppServices.player!
        Button { LikeButton.press(player) } label: {
            Image(systemName: LikeButton.symbol(player))
                .font(.system(size: size))
                .foregroundStyle(LikeButton.accent(player) ? Color.accentColor : Color.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(LikeButton.tip(player))
    }
}

/// 大きな丸い再生ボタン（アクセントの色）。
struct PlayCircle: View {
    var size: CGFloat = 52
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "play.fill")
                .font(.system(size: size * 0.38))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(Circle().fill(Color.accentColor))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L.t("再生", "Play"))
    }
}

/// 読み込み中・失敗・空のときの中身。
struct LoadState: View {
    var loading: Bool
    var failure: String?
    var retry: (() -> Void)?

    var body: some View {
        if loading {
            ProgressView().frame(maxWidth: .infinity, minHeight: 200)
        } else if let failure {
            VStack(spacing: 12) {
                Text(failure).foregroundStyle(.secondary).multilineTextAlignment(.center)
                if let retry { Button(L.t("もう一度", "Retry"), action: retry).buttonStyle(.bordered) }
            }
            .frame(maxWidth: .infinity, minHeight: 200)
            .padding()
        }
    }
}
#endif
