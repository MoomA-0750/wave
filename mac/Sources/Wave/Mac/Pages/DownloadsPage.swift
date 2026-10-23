#if os(macOS)
import SwiftUI
import WaveCore

/// ダウンロードのページ（ADR-0016 の 13・14）: 頭に使っている容量と進み具合、スマートダウンロード（入り切り・曲数・分類のチップ・
/// 丸ごと再生とシャッフル・曲）、丸ごと取っているもの、1曲ずつ取った曲、エピソード。
struct DownloadsPageView: View {
    @State private var model = DownloadsModel()
    @State private var selected: String?

    var body: some View {
        let downloads = AppServices.downloads!
        let smartSongs = model.smartSongs
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                header(downloads)
                section(L.t("スマートダウンロード", "Smart downloads"))
                smartControls(downloads, smartSongs)
                if downloads.settings.smartDownloads {
                    if smartSongs.isEmpty {
                        Text(downloads.refreshingSmart ? L.t("曲を選んでいます…", "Choosing songs…")
                             : L.t("まだ選んだ曲はありません", "No songs chosen yet")).subtle().padding(EdgeInsets(top: 4, leading: 12, bottom: 12, trailing: 12))
                    }
                    rows(smartSongs, prefix: "smart")
                }
                if !model.collections.isEmpty {
                    section(L.t("プレイリスト・アルバム", "Playlists and albums"))
                    ForEach(model.collections) { c in collectionRow(c) }
                }
                if !model.singleSongs.isEmpty {
                    section(L.t("曲", "Songs"))
                    rows(model.singleSongs, prefix: "single")
                }
                if !model.episodes.isEmpty {
                    section(L.t("エピソード", "Episodes"))
                    rows(model.episodes, prefix: "episode", episode: true)
                }
                if model.collections.isEmpty && model.singleSongs.isEmpty && model.episodes.isEmpty && smartSongs.isEmpty {
                    Text(L.t("曲・プレイリスト・アルバムのメニューやページの頭のボタンから、ダウンロードできます。通信の無いところでも聴けます。",
                             "Download songs, playlists and albums from their menus or the button at the top of their pages, and listen without a connection."))
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        .padding(EdgeInsets(top: 16, leading: 12, bottom: 0, trailing: 12))
                }
            }
            .padding(EdgeInsets(top: 0, leading: 12, bottom: 24, trailing: 12))
        }
    }

    private func header(_ downloads: Downloads) -> some View {
        HStack(alignment: .bottom, spacing: 24) {
            DownloadsCover(size: 160, radius: 8)
            VStack(alignment: .leading, spacing: 6) {
                Text(L.t("ダウンロード", "Downloads")).font(.system(size: 48, weight: .bold))  // apple-ui-allow: ほかのページの見出しと同じ大きさ
                Text(model.statusText).foregroundStyle(.secondary).lineLimit(2)
                if downloads.active != nil {
                    ProgressView(value: downloads.activeProgress).frame(maxWidth: 320)
                }
            }
        }
        .padding(EdgeInsets(top: 32, leading: 12, bottom: 8, trailing: 12))
    }

    private func section(_ title: String) -> some View {
        Text(title).subtitleStyle().padding(EdgeInsets(top: 20, leading: 12, bottom: 8, trailing: 12))
    }

    private func smartControls(_ downloads: Downloads, _ songs: [Song]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L.t("聴きそうな曲を自動で取っておく", "Automatically download songs you might like"))
                    Text(L.t("いつも聴いている曲・好きそうな曲・おすすめから、毎日選び直します", "Picks from your favorites, something new and recommendations, refreshed daily"))
                        .subtle()
                }
                Spacer()
                if downloads.settings.smartDownloads {
                    Picker("", selection: Binding(get: { downloads.settings.smartDownloadCount }, set: { downloads.setSmartCount($0) })) {
                        ForEach(SmartDownloads.countChoices, id: \.self) { Text(DownloadsModel.countLabel($0)).tag($0) }
                    }
                    .labelsHidden().fixedSize()
                }
                Toggle("", isOn: Binding(get: { downloads.settings.smartDownloads }, set: { downloads.setSmart($0) })).toggleStyle(.switch).labelsHidden()
            }
            if downloads.settings.smartDownloads {
                HStack(spacing: 8) {
                    ForEach(DownloadsModel.chips, id: \.self) { c in
                        Chip(title: DownloadsModel.label(c), isOn: model.category == c) { model.category = c }
                    }
                    Spacer()
                    if downloads.refreshingSmart { ProgressView().controlSize(.small) }
                    Button { model.play(songs, shuffle: true) } label: { Image(systemName: "shuffle").font(.system(size: 18)) }  // apple-ui-allow: 一覧のページの操作の行と同じ大きさ
                        .buttonStyle(IconButtonStyle()).help(L.t("シャッフル再生", "Shuffle play")).disabled(songs.isEmpty)
                    PlayCircleButton(size: 44, action: { model.play(songs) }) { Image(systemName: "play.fill").font(.system(size: 16)) }  // apple-ui-allow: 一覧のページの再生ボタンと同じ大きさ
                        .disabled(songs.isEmpty)
                }
            }
        }
        .padding(EdgeInsets(top: 0, leading: 12, bottom: 8, trailing: 12))
    }

    private func rows(_ songs: [Song], prefix: String, episode: Bool = false) -> some View {
        ForEach(Array(songs.enumerated()), id: \.element.videoId) { i, song in
            let row = TrackRow(number: i + 1, song: song)
            TrackRowView(row: row, selected: selected == prefix + row.id, onPlay: { model.play(songs, start: i) }, onSelect: { selected = prefix + row.id })
                .opacity(AppServices.downloads.isDownloaded(song.videoId) ? 1 : 0.55)
        }
    }

    private func collectionRow(_ c: DownloadCollection) -> some View {
        DownloadsCollectionRow(title: c.title, subtitle: model.kindText(c) + " • " + model.countText(c), thumbnailUrl: c.thumbnailUrl, liked: c.kind == .liked) {
            model.open(c)
        }
        .contextMenu {
            Button(L.t("ダウンロードを削除", "Remove download"), systemImage: "arrow.down.circle.dotted") { AppServices.downloads.removeCollection(c.id) }
        }
    }
}

private struct DownloadsCollectionRow: View {
    var title: String
    var subtitle: String
    var thumbnailUrl: String?
    var liked: Bool
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: 12) {
            if liked { LikedCover(size: 48) } else { Cover(url: Ui.small(thumbnailUrl), size: 48) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                Text(subtitle).subtle()
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .frame(height: 60)
        .background(RoundedRectangle(cornerRadius: 6).fill(hover ? Color.primary.opacity(0.06) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(perform: action)
    }
}
#endif
