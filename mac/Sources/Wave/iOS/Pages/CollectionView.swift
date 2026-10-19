#if os(iOS)
import SwiftUI
import WaveCore

/// プレイリスト・アルバム・高く評価した曲のページ（Android の PlaylistScreen・AlbumScreen と同じ並び: 上にジャケットと名前・補足、
/// 再生・シャッフル再生、曲の種類のチップ、曲の一覧）。一覧の中の絞り込みは iOS の検索欄、並べ替えとその他は右上のメニュー。
struct CollectionView: View {
    var request: CollectionRequest
    @State private var model: CollectionModel

    init(request: CollectionRequest) {
        self.request = request
        _model = State(initialValue: CollectionModel(request: request))
    }

    var body: some View {
        let player = AppServices.player!
        let rows = model.rows
        List {
            header(player, rows).listRowSeparator(.hidden).listRowBackground(Color.clear)
            kindChips.listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
            if model.loading && model.all.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding().listRowSeparator(.hidden)
            } else if let error = model.error {
                LoadState(loading: false, failure: error) { model.load() }.listRowSeparator(.hidden)
            } else if !model.all.isEmpty && rows.isEmpty {
                Text(L.t("一致する曲はありません", "No matching songs")).subtle().frame(maxWidth: .infinity).padding().listRowSeparator(.hidden)
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                let remove: (() -> Void)? = model.editable && row.setVideoId != nil ? { model.remove(row) } : nil
                SongRow(song: row.song, number: request.isAlbum ? row.number : nil, removeFromPlaylist: remove) { model.play(rows: rows, start: i) }
                    .swipeActions(edge: .trailing) {
                        if let remove {
                            Button(L.t("削除", "Remove"), systemImage: "trash", role: .destructive, action: remove)
                        }
                    }
                    .swipeActions(edge: .leading) {
                        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(row.song) }.tint(.indigo)
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(alignment: .top) {
            LinearGradient(colors: [(model.gradient ?? .clear).opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 460)
                .ignoresSafeArea()
        }
        .navigationTitle(model.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $model.filter, placement: .navigationBarDrawer(displayMode: .automatic), prompt: L.t("この一覧の中を検索", "Search in this list"))
        .toolbar { ToolbarItem(placement: .topBarTrailing) { moreMenu(player) } }
        .refreshable { await model.reload() }
        .onAppear { if model.all.isEmpty && model.error == nil { model.load() } }
    }

    private func header(_ player: PlayerService, _ rows: [TrackRow]) -> some View {
        VStack(spacing: 8) {
            Group {
                if model.isLiked {
                    LikedCover(size: 240, radius: 10)
                } else {
                    Cover(url: Ui.large(model.thumbnailUrl), size: 240, radius: 10)
                }
            }
            .shadow(color: .black.opacity(0.25), radius: 16, y: 8)
            .padding(.bottom, 8)
            Text(model.title ?? "").font(.title2.weight(.bold)).multilineTextAlignment(.center).lineLimit(3)
            if let artist = model.artistText {
                Button(artist) { if let id = model.albumArtist?.browseId { AppServices.nav.artist(id) } }
                    .font(.headline)
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.accentColor)
                    .disabled(model.albumArtist?.browseId == nil)
            }
            if let detail = model.detail { Text(detail).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center).lineLimit(3) }
            // 再生・シャッフル再生（iOS のミュージックと同じ大きな2つのボタン）。この一覧を流していれば再生は一時停止・再開になる
            HStack(spacing: 12) {
                CollectionViewBigButton(title: model.isPlayingHere && player.isPlaying ? L.t("一時停止", "Pause") : L.t("再生", "Play"),
                                        icon: model.isPlayingHere && player.isPlaying ? "pause.fill" : "play.fill") {
                    if model.isPlayingHere { player.togglePlay() } else { model.play(rows: rows) }
                }
                CollectionViewBigButton(title: L.t("シャッフル", "Shuffle"), icon: "shuffle") { model.play(rows: rows, shuffle: true) }
            }
            .disabled(rows.isEmpty)
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }

    // 曲の種類で絞る（動画や歌ってみたと、ふつうの曲を分ける。Android と同じ）。同じものをもう一度押すと外す
    private var kindChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                CollectionViewChip(title: L.t("すべて", "All"), isOn: model.kindFilter == nil) { model.kindFilter = nil }
                ForEach([(Song.kindSong, L.t("曲", "Songs")), (Song.kindMusicVideo, L.t("MV", "Music videos")),
                         (Song.kindUserVideo, L.t("その他の動画", "Other videos"))], id: \.0) { kind, title in
                    CollectionViewChip(title: title, isOn: model.kindFilter == kind) { model.kindFilter = model.kindFilter == kind ? nil : kind }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
    }

    private func moreMenu(_ player: PlayerService) -> some View {
        Menu {
            Section(L.t("並べ替え", "Sort by")) {
                ForEach(model.sortChoices, id: \.self) { key in
                    Button {
                        model.choose(key)
                    } label: {
                        if key == model.sort {
                            Label(CollectionModel.label(key), systemImage: key == .custom ? "checkmark" : model.descending ? "arrow.down" : "arrow.up")
                        } else {
                            Text(CollectionModel.label(key))
                        }
                    }
                }
            }
            Section {
                Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.queueAll(model.rows.map(\.song), next: true) }
                Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.queueAll(model.rows.map(\.song), next: false) }
            }
            .disabled(model.rows.isEmpty)
        } label: {
            Image(systemName: "ellipsis")
        }
        .accessibilityLabel(L.t("その他", "More"))
    }
}

private struct CollectionViewBigButton: View {
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

private struct CollectionViewChip: View {
    var title: String
    var isOn: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .foregroundStyle(isOn ? Color.white : Color.primary)
                .background(Capsule().fill(isOn ? Color.accentColor : Color.primary.opacity(0.08)))
        }
        .buttonStyle(.plain)
    }
}
#endif
