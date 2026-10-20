#if os(macOS)
import SwiftUI
import WaveCore

/// 右: 再生中の曲（Spotify の「再生中の画面」）・キュー・Claude。
struct RightPane: View {
    @State private var model = MainWindowModel.shared

    /// 見出しの行の右の空き（上の帯の右端のアカウントのボタンと重ならないように）。
    static let headerTrailing: CGFloat = 48

    var body: some View {
        // 上の帯の裏まで伸ばし、見出しの行を帯と同じ高さに置く（帯の下に空白を作らない）
        Group {
            switch model.rightView {
            case .nowPlaying: NowPlayingView()
            case .queue: QueueView()
            case .ai: AiView()
            }
        }
        .ignoresSafeArea(edges: .top)
    }
}

/// 評価のボタンの絵・色・説明（Windows と同じ）。
/// 評価済みは✓、まだなら＋、読めていない（ログイン中で評価が分からない）なら↻（押すと読み直す。Android と同じ）。
extension LikeButton {
    /// RSS のエピソード（ADR-0012）は YouTube の曲ではないので出さない。
    @ViewBuilder
    static func view(_ player: PlayerService, size: CGFloat) -> some View {
        if available(player) {
            Button { press(player) } label: {
                Image(systemName: symbol(player)).font(.system(size: size)).foregroundStyle(accent(player) ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(IconButtonStyle())
            .help(tip(player))
        }
    }
}

/// 曲名を押すとアルバムへ、アーティストを押すとアーティストのページへ（複数なら選ぶ）。
@MainActor
enum SongLinks {
    static func openAlbum() {
        guard let id = AppServices.player.current?.album?.browseId else { return }
        MainWindowModel.shared.openFromFull { AppServices.nav.album(id) }
    }

    static func artists() -> [ArtistRef] { AppServices.player.current?.artists.filter { $0.browseId != nil } ?? [] }
}

/// アーティストのリンク（1人ならそのページへ、複数なら選ぶメニュー。Android と同じ）。
struct ArtistLink: View {
    var font: Font = .body
    var color: Color = .secondary

    var body: some View {
        let player = AppServices.player!
        let artists = SongLinks.artists()
        if artists.count > 1 {
            Menu {
                ForEach(artists, id: \.self) { a in
                    Button(a.name, systemImage: "person") { MainWindowModel.shared.openFromFull { AppServices.nav.artist(a.browseId!) } }
                }
            } label: {
                Text(player.current?.artistsText ?? "").font(font).foregroundStyle(color).lineLimit(1)
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize(horizontal: false, vertical: true)
        } else {
            LinkText(text: player.current?.artistsText ?? "") {
                if let a = artists.first { MainWindowModel.shared.openFromFull { AppServices.nav.artist(a.browseId!) } }
            }
            .font(font)
            .foregroundStyle(color)
            .lineLimit(1)
        }
    }
}

/// 再生中の画面（右の欄）。
struct NowPlayingView: View {
    @State private var model = MainWindowModel.shared

    var body: some View {
        let player = AppServices.player!
        let song = player.current
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Claude は上の帯のボタンから開く（右の欄には置かない）
                Text(song?.album?.title.isEmpty == false ? song!.album!.title : L.t("再生中", "Now playing"))
                    .font(.body.weight(.semibold)).lineLimit(1)
                    .frame(minHeight: 28)
                    .padding(.trailing, RightPane.headerTrailing - 16)
                // ジャケットか、MV のある曲では映像（Spotify と同じ「曲｜動画」の切り替え）
                ZStack(alignment: .top) {
                    VideoOrCover(size: 306, inFullView: false)
                    if player.hasVideo { SongVideoSwitch().padding(.top, 10) }
                }
                .frame(height: 306)
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        LinkText(text: song?.title ?? L.t("何も再生していません", "Nothing playing"), action: SongLinks.openAlbum)
                            .font(.title2.weight(.semibold))
                            .lineLimit(2)
                        ArtistLink(font: .body, color: .primary.opacity(0.75))
                    }
                    Spacer(minLength: 0)
                    if song != nil { LikeButton.view(player, size: 20) }
                }
                // 次の曲（Android と同じく歌詞の上）
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(L.t("次の曲", "Next up")).font(.body.weight(.semibold))
                        Spacer()
                        Button(L.t("キューを開く", "Open queue")) { model.showQueue() }.buttonStyle(.textLink)
                    }
                    if player.index + 1 < player.queue.count {
                        let next = player.queue[player.index + 1]
                        HStack(spacing: 12) {
                            Cover(url: Ui.small(next.thumbnailUrl), size: 44, radius: 4)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(next.title).lineLimit(1)
                                Text(next.artistsText).subtle()
                            }
                        }
                    }
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
                // 歌詞（無い曲では箱ごと出さない）。全文を中で送り、歌っている行へ滑らかに動かす
                if case .found(let lyrics) = player.lyrics, song != nil {
                    Button { AppServices.nav.lyrics() } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L.t("歌詞", "Lyrics")).font(.body.weight(.semibold))
                            LyricsFollower(lyrics: lyrics, positionMs: Int64(player.position * 1000), fontSize: 16)
                                .frame(height: 190, alignment: .top)
                                .clipped()
                                .allowsHitTesting(false)
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
        }
    }
}

/// 「曲｜動画」の切り替え。
struct SongVideoSwitch: View {
    @State private var model = MainWindowModel.shared

    var body: some View {
        HStack(spacing: 0) {
            segment(L.t("曲", "Song"), on: !model.showVideo) { model.showVideo = false }
            segment(L.t("動画", "Video"), on: model.showVideo) { model.showVideo = true }
        }
        .padding(3)
        .background(Capsule().fill(Color.black.opacity(0.6)))
    }

    private func segment(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.caption).padding(.horizontal, 14).padding(.vertical, 4)
                .foregroundStyle(on ? Color.black : Color.white)
                .background(Capsule().fill(on ? Color.white : Color.clear))
        }
        .buttonStyle(.plain)
    }
}

/// キュー（Spotify と同じ）と履歴。次の曲はドラッグで並べ替えられる（今の曲は動かせない。Android と同じ）。
struct QueueView: View {
    @State private var model = MainWindowModel.shared

    var body: some View {
        let player = AppServices.player!
        let entries = model.queueEntries()
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Chip(title: L.t("キュー", "Queue"), isOn: !model.queueHistory) { model.queueHistory = false }
                Chip(title: L.t("履歴", "History"), isOn: model.queueHistory) { model.queueHistory = true }
                Spacer()
                if !model.queueHistory && player.userQueueEnd > player.index + 1 {
                    Button(L.t("キューを消去", "Clear queue")) { player.clearUserQueue() }.buttonStyle(.textLink)
                }
                Button { model.setRightPane(open: true, queue: false) } label: { Image(systemName: "xmark").font(.system(size: 11)) }
                    .buttonStyle(IconButtonStyle()).help(L.t("閉じる", "Close"))
            }
            .padding(EdgeInsets(top: 12, leading: 16, bottom: 4, trailing: RightPane.headerTrailing))
            if entries.isEmpty {
                Spacer()
                VStack(spacing: 6) {
                    Text(model.queueHistory ? L.t("まだ流れた曲はありません", "Nothing played yet") : L.t("キューは空です", "Your queue is empty"))
                        .font(.body.weight(.semibold))
                    Text(model.queueHistory ? L.t("流れた曲は、ここに新しい順に並びます", "Songs you've played will show up here")
                        : L.t("曲を再生すると、ここに次の曲が並びます", "Play something and upcoming songs will show up here"))
                        .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .padding(24)
                Spacer()
            } else {
                List {
                    ForEach(entries) { entry in
                        QueueRow(entry: entry)
                            .moveDisabled(model.queueHistory || entry.isCurrent)
                            .listRowSeparator(.hidden)
                    }
                    .onMove { from, to in
                        // 表示の並びでの位置から、キューでの行き先を求める（今の曲より前には置かない）
                        guard let first = from.first, !model.queueHistory else { return }
                        let moved = entries[first]
                        let dest = to > first ? to - 1 : to
                        player.moveUpcoming(moved.index, player.index + max(1, dest))
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }
}

private struct QueueRow: View {
    var entry: QueueEntry
    @State private var hover = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // かたまりの見出し（最初の行にだけ出す）
            if let section = entry.section {
                Text(section).font(.body.weight(.semibold)).padding(.top, 10).padding(.bottom, 2)
            }
            HStack(spacing: 12) {
                Cover(url: Ui.small(entry.song.thumbnailUrl), size: 44, radius: 4)
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.song.title).lineLimit(1).foregroundStyle(entry.isCurrent ? Color.accentColor : Color.primary)
                    Text(entry.song.artistsText).subtle()
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 6).fill(hover ? Color.primary.opacity(0.06) : .clear).padding(.horizontal, -6))
            .contentShape(Rectangle())
            .onHover { hover = $0 }
            .onTapGesture { if !entry.isCurrent { AppServices.player.playAt(entry.index) } }
        }
        // ほかの端末を映している間の「キューから削除」は、その端末へ送る
        .contextMenu { SongMenuItems(song: entry.song, queueIndex: entry.isCurrent ? nil : entry.index) }
    }
}
#endif
