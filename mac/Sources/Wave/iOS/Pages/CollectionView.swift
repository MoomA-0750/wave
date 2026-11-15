#if os(iOS)
import SwiftUI
import WaveCore

/// プレイリスト・アルバム・高く評価した曲のページ（Android の PlaylistScreen・AlbumScreen と同じ並び: 上にジャケットと名前・補足、
/// 再生・シャッフル再生、曲の種類のチップ、曲の一覧）。一覧の中の絞り込みは iOS の検索欄、並べ替えとその他は右上のメニュー。
struct CollectionView: View {
    var request: CollectionRequest
    @State private var model: CollectionModel
    /// 上の大きなジャケットが流れきったか（それまでは上の帯の題を出さない。ジャケットの上の題と二重になる）
    @State private var pastHero = false

    init(request: CollectionRequest) {
        self.request = request
        _model = State(initialValue: CollectionModel(request: request))
    }

    var body: some View {
        let player = AppServices.player!
        let rows = model.rows
        List {
            header(player, rows).listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
            kindChips.listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
            if model.loading && model.all.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding().listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else if let error = model.error {
                LoadState(loading: false, failure: error) { model.load() }.listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else if !model.all.isEmpty && rows.isEmpty {
                Text(L.t("一致する曲はありません", "No matching songs")).subtle().frame(maxWidth: .infinity).padding().listRowSeparator(.hidden).listRowBackground(Color.clear)
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                let remove: (() -> Void)? = model.editable && row.setVideoId != nil ? { model.remove(row) } : nil
                SongRow(song: row.song, number: request.isAlbum ? row.number : nil, removeFromPlaylist: remove) { model.play(rows: rows, start: i) }
                    .listRowBackground(Color.clear)
                    .swipeActions(edge: .trailing) {
                        if let remove {
                            Button(L.t("削除", "Remove"), systemImage: "trash", role: .destructive, action: remove)
                        }
                    }
                    .swipeActions(edge: .leading) {
                        Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(row.song) }.tint(.indigo)
                    }
            }
            // 自分のプレイリストの一番下: YouTube Music の候補（＋で足す・「更新」で替える）。絞っている間は出さない
            if !model.suggestions.isEmpty && model.filter.isEmpty && model.kindFilter == nil {
                Section {
                    ForEach(model.suggestions) { song in
                        SuggestionRow(song: song, onAdd: { model.addSuggestion(song) }) { model.playSuggestion(song) }
                            .listRowBackground(Color.clear)
                    }
                } header: {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L.t("おすすめの曲", "Recommended songs")).font(.title3.weight(.bold)).foregroundStyle(.primary)
                            Text(L.t("このプレイリストの曲をもとに", "Based on what's in this playlist")).font(.footnote).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(L.t("更新", "Refresh")) { model.refreshSuggestions() }
                            .font(.subheadline.weight(.semibold))
                            .disabled(model.suggestionsLoading)
                    }
                    .textCase(nil)
                    .padding(.top, Kasane.Space.s5)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // 大きなジャケットは上の帯の裏まで伸ばす（アーティストのページと同じ）
        .ignoresSafeArea(edges: .top)
        .onScrollGeometryChange(for: Bool.self) { geo in
            geo.contentOffset.y + geo.contentInsets.top > heroHeight - 120
        } action: { _, past in
            withAnimation(.easeOut(duration: 0.2)) { pastHero = past }
        }
        // 地は再生画面と同じ（ジャケットを大きくぼかして暗くしたもの）。高く評価した曲はジャケットが無いので、その色を上に重ねる。
        // 暗い地なので文字は常に暗い表示の色にする
        .background {
            ZStack(alignment: .top) {
                ArtworkBackdrop(url: model.isLiked ? nil : model.thumbnailUrl)
                if model.isLiked {
                    LinearGradient(colors: [(model.gradient ?? .clear).opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
                        .frame(height: 460)
                        .ignoresSafeArea()
                }
            }
        }
        .environment(\.colorScheme, .dark)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .navigationTitle(pastHero ? (model.title ?? "") : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar) // apple-ui-allow: iOS 18 でジャケットの上の帯を塗らないため（26 からはガラスになる）
        .modifier(TopEdgeEffect(hidden: !pastHero))
        .searchable(text: $model.filter, placement: .navigationBarDrawer(displayMode: .automatic), prompt: L.t("この一覧の中を検索", "Search in this list"))
        .toolbar { ToolbarItem(placement: .topBarTrailing) { moreMenu(player) } }
        .refreshable { await model.reload() }
        .onAppear { if model.all.isEmpty && model.error == nil { model.load() } }
    }

    private var heroHeight: CGFloat { 420 }

    /// 上の大きなジャケット（横いっぱい。下の端は地へ溶かす）に、題・アーティスト・補足と、ダウンロード・シャッフル・再生を重ねる
    /// （アーティストのページ・再生画面と同じ見せ方）。この一覧を流していれば再生は一時停止・再開になる。
    private func header(_ player: PlayerService, _ rows: [TrackRow]) -> some View {
        let playing = model.isPlayingHere && player.isPlaying
        return Color.clear
            .frame(height: heroHeight)
            .overlay {
                ZStack {
                    if model.isLiked {
                        ZStack {
                            Ui.likedGradient
                            Image(systemName: "heart.fill").font(.system(size: 120)).foregroundStyle(.white.opacity(0.9)) // apple-ui-allow: 記号の大きさ（ボタンの枠に合わせる）
                        }
                    } else {
                        RemoteImage(url: Ui.large(model.thumbnailUrl))
                    }
                    LinearGradient(stops: [.init(color: .clear, location: 0.4), .init(color: .black.opacity(0.35), location: 0.8)], startPoint: .top, endPoint: .bottom)
                }
                .mask(LinearGradient(stops: [.init(color: .black, location: 0.35), .init(color: .black.opacity(0.9), location: 0.5),
                                             .init(color: .black.opacity(0.6), location: 0.65), .init(color: .black.opacity(0.3), location: 0.8),
                                             .init(color: .black.opacity(0.08), location: 0.92), .init(color: .clear, location: 1)],
                                     startPoint: .top, endPoint: .bottom))
            }
            .overlay(alignment: .bottom) {
                HStack(alignment: .bottom, spacing: Kasane.Space.s3) {
                    VStack(alignment: .leading, spacing: Kasane.Space.s1) {
                        Text(model.title ?? "").font(.title.bold()).foregroundStyle(.white).lineLimit(2).minimumScaleFactor(0.6)
                        if let artist = model.artistText {
                            Button(artist) { if let id = model.albumArtist?.browseId { AppServices.nav.artist(id) } }
                                .font(.headline)
                                .buttonStyle(.plain)
                                .foregroundStyle(.white.opacity(0.85))
                                .disabled(model.albumArtist?.browseId == nil)
                        }
                        if let detail = model.detail { Text(detail).font(.footnote).foregroundStyle(.white.opacity(0.7)).lineLimit(2) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    actions(player, rows, playing: playing)
                }
                .padding(.horizontal, Kasane.Space.s4)
                .padding(.bottom, Kasane.Space.s3)
            }
            .clipped()
    }

    /// ダウンロード（取っていない／途中／取り終えた。ADR-0016 の 15。アーティストで絞った高く評価した曲には出さない）・シャッフル・再生。
    /// アーティストのページと同じ大きさのガラスの丸を1つの入れ物にまとめ、色は再生だけ。
    @ViewBuilder
    private func actions(_ player: PlayerService, _ rows: [TrackRow], playing: Bool) -> some View {
        let buttons = HStack(spacing: 10) {
            // アルバムだけ。ログインしていない（保存の状態が分からない）ときは出さない。しおりは YouTube Music と同じ
            if model.request.isAlbum, let saved = model.albumSaved {
                Button { model.toggleAlbumSaved() } label: {
                    Image(systemName: saved ? "bookmark.fill" : "bookmark").font(.title3.weight(.semibold)).modifier(GlassCircle())
                }
                .buttonStyle(.plain)
                .disabled(model.savingAlbum)
                .accessibilityLabel(saved ? L.t("ライブラリから削除", "Remove from library") : L.t("ライブラリに保存", "Save to library"))
                .accessibilityAddTraits(saved ? .isSelected : [])
            }
            if model.request.artist == nil {
                CollectionDownloadButton(model: model, size: 18, iconOnly: true)
                    .frame(width: 50, height: 50)
                    .modifier(GlassCircle())
            }
            Button { model.play(rows: rows, shuffle: true) } label: {
                Image(systemName: "shuffle").font(.title3.weight(.semibold)).modifier(GlassCircle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.t("シャッフル", "Shuffle"))
            Button {
                if model.isPlayingHere { player.togglePlay() } else { model.play(rows: rows) }
            } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill").font(.title3).modifier(GlassCircle(tint: .accentColor))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(playing ? L.t("一時停止", "Pause") : L.t("再生", "Play"))
        }
        .foregroundStyle(.white)
        .disabled(rows.isEmpty)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 10) { buttons }
        } else {
            buttons
        }
    }

    // 曲の種類で絞る（動画や歌ってみたと、ふつうの曲を分ける。Android と同じ）。同じものをもう一度押すと外す
    private var kindChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Kasane.Space.s2) {
                CollectionViewChip(title: L.t("すべて", "All"), isOn: model.kindFilter == nil) { model.kindFilter = nil }
                ForEach([(Song.kindSong, L.t("曲", "Songs")), (Song.kindMusicVideo, L.t("MV", "Music videos")),
                         (Song.kindUserVideo, L.t("その他の動画", "Other videos"))], id: \.0) { kind, title in
                    CollectionViewChip(title: title, isOn: model.kindFilter == kind) { model.kindFilter = model.kindFilter == kind ? nil : kind }
                }
            }
            .padding(.horizontal, Kasane.Space.s4)
            .padding(.vertical, Kasane.Space.s2)
        }
    }

    private func moreMenu(_ player: PlayerService) -> some View {
        Menu {
            Section(L.t("並べ替え", "Sort")) {
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

private struct CollectionViewChip: View {
    var title: String
    var isOn: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, Kasane.Space.s4)
                .padding(.vertical, Kasane.Space.s2)
                .foregroundStyle(isOn ? Color.white : Color.primary)
                .background(Capsule().fill(isOn ? Color.accentColor : Color.primary.opacity(0.08)))
        }
        .buttonStyle(.plain)
    }
}
/// おすすめの曲の行（押すと流す。右の「＋」でプレイリストに足す）。
private struct SuggestionRow: View {
    var song: Song
    var onAdd: () -> Void
    var onPlay: () -> Void

    var body: some View {
        HStack(spacing: Kasane.Space.s3) {
            Cover(url: Ui.small(song.thumbnailUrl), size: 48, radius: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).lineLimit(1)
                Text(song.artistsText).subtle()
            }
            Spacer(minLength: 0)
            Button(action: onAdd) {
                Image(systemName: "plus.circle").font(.title2).foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L.t("プレイリストに保存", "Save to playlist"))
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onPlay)
    }
}
#endif
