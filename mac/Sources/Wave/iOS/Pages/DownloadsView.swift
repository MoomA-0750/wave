#if os(iOS)
import SwiftUI
import WaveCore

/// ダウンロードの画面（ADR-0016 の 13・14。Mac の DownloadsPageView と同じ中身）: 使っている容量と進み具合、
/// スマートダウンロード（入り切り・曲数・分類のチップ・再生とシャッフル・曲）、丸ごと取っているもの、1曲ずつ取った曲、エピソード。
struct DownloadsView: View {
    @State private var model = DownloadsModel()

    var body: some View {
        let downloads = AppServices.downloads!
        let smartSongs = model.smartSongs
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(model.statusText).font(.footnote).foregroundStyle(.secondary)
                    if downloads.active != nil { ProgressView(value: downloads.activeProgress) }
                }
                .listRowSeparator(.hidden)
            }
            Section {
                Toggle(isOn: Binding(get: { downloads.settings.smartDownloads }, set: { downloads.setSmart($0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L.t("スマートダウンロード", "Smart downloads"))
                        Text(L.t("いつも聴いている曲・好きそうな曲・おすすめから、毎日選び直します", "Picks from your favorites, something new and recommendations, refreshed daily"))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if downloads.settings.smartDownloads {
                    Picker(L.t("曲数", "Songs"), selection: Binding(get: { downloads.settings.smartDownloadCount }, set: { downloads.setSmartCount($0) })) {
                        ForEach(SmartDownloads.countChoices, id: \.self) { Text(DownloadsModel.countLabel($0)).tag($0) }
                    }
                    chips.listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
                    HStack(spacing: 12) {
                        DownloadsBigButton(title: L.t("再生", "Play"), icon: "play.fill") { model.play(smartSongs) }
                        DownloadsBigButton(title: L.t("シャッフル", "Shuffle"), icon: "shuffle") { model.play(smartSongs, shuffle: true) }
                    }
                    .disabled(smartSongs.isEmpty)
                    .listRowSeparator(.hidden)
                    if smartSongs.isEmpty {
                        Text(downloads.refreshingSmart ? L.t("曲を選んでいます…", "Choosing songs…") : L.t("まだ選んだ曲はありません", "No songs chosen yet")).subtle()
                    }
                    songRows(smartSongs)
                }
            } header: {
                Text(L.t("スマートダウンロード", "Smart downloads"))
            }
            if !model.collections.isEmpty {
                Section(L.t("プレイリスト・アルバム", "Playlists and albums")) {
                    ForEach(model.collections) { c in
                        Button { model.open(c) } label: {
                            HStack(spacing: 12) {
                                if c.kind == .liked { LikedCover(size: 56) } else { Cover(url: Ui.small(c.thumbnailUrl), size: 56) }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(c.title).lineLimit(1)
                                    Text(model.kindText(c) + " • " + model.countText(c)).subtle()
                                }
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .tint(.primary)
                        .swipeActions(edge: .trailing) {
                            Button(L.t("削除", "Remove"), systemImage: "trash", role: .destructive) { downloads.removeCollection(c.id) }
                        }
                    }
                }
            }
            if !model.singleSongs.isEmpty {
                Section(L.t("曲", "Songs")) { songRows(model.singleSongs, removable: true) }
            }
            if !model.episodes.isEmpty {
                Section(L.t("エピソード", "Episodes")) { songRows(model.episodes, removable: true) }
            }
            if model.collections.isEmpty && model.singleSongs.isEmpty && model.episodes.isEmpty && smartSongs.isEmpty {
                Text(L.t("曲・プレイリスト・アルバムのメニューやページの頭のボタンから、ダウンロードできます。通信の無いところでも聴けます。",
                         "Download songs, playlists and albums from their menus or the button at the top of their pages, and listen without a connection."))
                    .font(.footnote).foregroundStyle(.secondary).listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .navigationTitle(L.t("ダウンロード", "Downloads"))
    }

    // 分類のチップ（すべて・いつも聴いている・好きそう・おすすめ）
    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(DownloadsModel.chips, id: \.self) { c in
                    Button { model.category = c } label: {
                        Text(DownloadsModel.label(c))
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .foregroundStyle(model.category == c ? Color.white : Color.primary)
                            .background(Capsule().fill(model.category == c ? Color.accentColor : Color.primary.opacity(0.08)))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
    }

    private func songRows(_ songs: [Song], removable: Bool = false) -> some View {
        ForEach(Array(songs.enumerated()), id: \.element.videoId) { i, song in
            SongRow(song: song) { model.play(songs, start: i) }
                .opacity(AppServices.downloads.isDownloaded(song.videoId) ? 1 : 0.55)
                .swipeActions(edge: .trailing) {
                    if removable {
                        Button(L.t("削除", "Remove"), systemImage: "trash", role: .destructive) { AppServices.downloads.remove(song.videoId) }
                    }
                }
        }
    }
}

private struct DownloadsBigButton: View {
    var title: String
    var icon: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.accentColor)
        .glassCard(14, interactive: true)
    }
}
#endif
