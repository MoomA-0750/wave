#if os(iOS)
import SwiftUI
import WaveCore

/// Liquid Glass の板（iOS 26 より前はすりガラス）。
struct GlassCard: ViewModifier {
    var radius: CGFloat = Kasane.Radius.lg
    var interactive = false

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(interactive ? .regular.interactive() : .regular, in: RoundedRectangle(cornerRadius: radius, style: Kasane.cornerStyle))
        } else {
            content.background(.regularMaterial, in: RoundedRectangle(cornerRadius: radius, style: Kasane.cornerStyle)) // apple-ui-allow: Liquid Glass（26）より前の代わりのすりガラス
        }
    }
}

extension View {
    func glassCard(_ radius: CGFloat = Kasane.Radius.lg, interactive: Bool = false) -> some View { modifier(GlassCard(radius: radius, interactive: interactive)) }

    /// 小さな灰色の文字。
    func subtle() -> some View { font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
}

/// 丸いガラスの地（iOS 26 より前は薄い丸）。押すと光って弾む。アーティスト・アルバム・プレイリストの頭のボタン。
/// 並べる側（ArtistView・CollectionView）が GlassEffectContainer で包む。GlassCard は1枚の板なので包まない
struct GlassCircle: ViewModifier {
    /// 3つとも同じ大きさ
    var size: CGFloat = 50
    var tint: Color? = nil

    func body(content: Content) -> some View {
        let framed = content.frame(width: size, height: size).contentShape(Circle())
        if #available(iOS 26.0, *) {
            framed.glassEffect((tint.map { Glass.regular.tint($0) } ?? .regular).interactive(), in: .circle)
        } else {
            framed.background(Circle().fill(tint ?? Color.white.opacity(0.15)))
        }
    }
}

/// ジャケットの枠（アーティストは丸、ほかは角の少し丸い四角。Android と同じ）。
struct Cover: View {
    var url: URL?
    var size: CGFloat
    var round = false
    var radius: CGFloat = Kasane.Radius.sm

    var body: some View {
        RemoteImage(url: url)
            .frame(width: size, height: size)
            .background(Color.primary.opacity(0.08))
            .clipShape(round ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: radius, style: Kasane.cornerStyle)))
    }
}

/// 高く評価した曲の絵（紫から緑のグラデーションにハート）。
struct LikedCover: View {
    var size: CGFloat
    var radius: CGFloat = Kasane.Radius.sm

    var body: some View {
        ZStack {
            Ui.likedGradient
            Image(systemName: "heart.fill").font(.system(size: size * 0.36)).foregroundStyle(.white) // apple-ui-allow: 記号の大きさ（ボタンの枠に合わせる）
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: Kasane.cornerStyle))
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
        HStack(spacing: Kasane.Space.s3) {
            if let number {
                Text("\(number)").foregroundStyle(.secondary).monospacedDigit().frame(width: 28)
            } else {
                Cover(url: Ui.small(song.thumbnailUrl), size: 48, radius: Kasane.Radius.sm)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).lineLimit(1).foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                HStack(spacing: Kasane.Space.s1) {
                    if song.isLive { LiveBadge(font: .caption2.weight(.bold), dot: 6) }
                    if AppServices.library.isLiked(song.videoId) {
                        Image(systemName: "checkmark.circle.fill").font(.caption2).foregroundStyle(Color.accentColor)
                    }
                    DownloadedMark(videoId: song.videoId)
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
    /// ポッドキャストのエピソード（ダウンロードの画面で曲と分けて出す）。
    var episode = false

    var body: some View {
        let player = AppServices.player!
        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(song) }
        Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.addToQueue(song) }
        if let queueIndex {
            Button(L.t("キューから削除", "Remove from queue"), systemImage: "minus.circle", role: .destructive) { player.removeAt(queueIndex) }
        }
        DownloadMenuItems(song: song, episode: episode)
        // RSS のエピソードは YouTube の曲ではない（プレイリスト・ラジオ・曲の画面・共有のリンクが無い。ADR-0012）
        let external = Feed.isExternal(song.videoId)
        if !external {
            Button(L.t("プレイリストに保存", "Save to playlist"), systemImage: "plus") { Dialogs.shared.addToPlaylist(song) }
                .disabled(!AppServices.client.isLoggedIn)
        }
        // 高く評価した曲に足す・外す（Spotify の「お気に入りに追加」。Android・Windows と同じ）
        if !external && AppServices.client.isLoggedIn {
            let liked = player.isLiked(song)
            Button(liked ? L.t("高く評価した曲から削除", "Remove from liked music") : L.t("高く評価した曲に追加", "Add to liked music"),
                   systemImage: liked ? "checkmark.circle.fill" : "plus.circle") { player.setLiked(song, !liked) }
        }
        if let removeFromPlaylist {
            Button(L.t("このプレイリストから削除", "Remove from this playlist"), systemImage: "trash", role: .destructive, action: removeFromPlaylist)
        }
        if !external {
            Button(L.t("ラジオを開始", "Start radio"), systemImage: "dot.radiowaves.left.and.right") { player.playSong(song) }
        }
        if let albumId = song.album?.browseId {
            Button(L.t("アルバムへ移動", "Go to album"), systemImage: "square.stack") { AppServices.nav.album(albumId) }
        }
        ForEach(song.artists.filter { $0.browseId != nil }, id: \.self) { artist in
            Button(song.artists.count > 1 ? L.t("アーティストへ移動: \(artist.name)", "Go to artist: \(artist.name)") : L.t("アーティストへ移動", "Go to artist"),
                   systemImage: "person") { AppServices.nav.artist(artist.browseId!) }
        }
        if !external {
            Button(L.t("曲の画面を開く", "Go to song"), systemImage: "music.note") { AppServices.nav.song(song) }
            ShareLink(item: SongActions.url(song), subject: Text(song.title), message: Text(SongActions.shareText(song))) {
                Label(L.t("共有", "Share"), systemImage: "square.and.arrow.up")
            }
        }
    }
}

/// 評価のボタン（＋と✓。Android・Mac と同じ動き）。
struct LikeButtonView: View {
    var size: CGFloat = 22
    /// 押せる枠の一辺（隣の丸いボタンと大きさをそろえるときに渡す）。
    var box: CGFloat = 44

    var body: some View {
        let player = AppServices.player!
        // RSS のエピソード（ADR-0012）は YouTube の曲ではないので出さない
        if LikeButton.available(player) {
            Button { LikeButton.press(player) } label: {
                // iPhone は丸の囲いの無い＋と✓を、どちらも白（地の文字の色）で出す
                Image(systemName: Self.plain(LikeButton.symbol(player)))
                    .font(.system(size: size, weight: .semibold)) // apple-ui-allow: 記号・絵に合わせた大きさ
                    .foregroundStyle(.primary)
                    .frame(width: box, height: box)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(LikeButton.tip(player))
            .accessibilityAddTraits(LikeButton.accent(player) ? .isSelected : [])
        }
    }

    private static func plain(_ symbol: String) -> String {
        switch symbol {
        case "checkmark.circle.fill": "checkmark"
        case "plus.circle": "plus"
        default: symbol
        }
    }
}

/// 大きな丸い再生ボタン（アクセントの色）。
struct PlayCircle: View {
    var size: CGFloat = 52
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "play.fill")
                .font(.system(size: size * 0.38)) // apple-ui-allow: 記号・絵に合わせた大きさ
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
            VStack(spacing: Kasane.Space.s3) {
                Text(failure).foregroundStyle(.secondary).multilineTextAlignment(.center)
                if let retry { Button(L.t("もう一度", "Retry"), action: retry).buttonStyle(.bordered) }
            }
            .frame(maxWidth: .infinity, minHeight: 200)
            .padding()
        }
    }
}
#endif
