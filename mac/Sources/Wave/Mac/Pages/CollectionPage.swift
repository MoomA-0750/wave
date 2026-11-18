#if os(macOS)
import SwiftUI
import WaveCore

/// プレイリスト・アルバムのページ（Spotify と同じ: ジャケットの色のグラデーション、大きなジャケットと名前、再生ボタン、曲の表）。
struct CollectionPageView: View {
    @State private var model: CollectionModel
    @State private var filterOpen = false
    @State private var selected: String?
    @FocusState private var filterFocus: Bool

    init(request: CollectionRequest) {
        _model = State(initialValue: CollectionModel(request: request))
    }

    var body: some View {
        let player = AppServices.player!
        let rows = model.rows
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                header
                controls(player)
                kindChips
                TrackHeader(showAlbum: !model.request.isAlbum).padding(.horizontal, Kasane.Space.s3)
                if let error = model.error { Text(error).padding(EdgeInsets(top: Kasane.Space.s6, leading: Kasane.Space.s3, bottom: Kasane.Space.s6, trailing: Kasane.Space.s3)) }
                if !model.all.isEmpty && rows.isEmpty {
                    Text(L.t("一致する曲はありません", "No matching songs")).subtle().padding(Kasane.Space.s6)
                }
                ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                    TrackRowView(row: row, selected: selected == row.id,
                                 removeFromPlaylist: model.editable && row.setVideoId != nil ? { model.remove(row) } : nil,
                                 onPlay: { model.play(rows: rows, start: i) }, onSelect: { selected = row.id })
                }
                // 自分のプレイリストの一番下: YouTube Music の候補（「追加」で足す・「更新」で替える）。絞っている間は出さない
                if !model.suggestions.isEmpty && model.filter.isEmpty && model.kindFilter == nil {
                    PlaylistSuggestionsView(model: model)
                }
            }
            .padding(EdgeInsets(top: 0, leading: Kasane.Space.s3, bottom: Kasane.Space.s6, trailing: Kasane.Space.s3))
        }
        .background(alignment: .top) {
            LinearGradient(colors: [(model.gradient ?? .clear).opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).frame(height: 360)
                // 上の帯（検索の欄）の裏まで伸ばす
                .ignoresSafeArea(edges: .top)
        }
        .onKeyPress(.return) {
            // 一覧の中を探す欄で変換中の Return は確定に使わせる
            guard !TextInput.composing, let selected, let i = rows.firstIndex(where: { $0.id == selected }) else { return .ignored }
            model.play(rows: rows, start: i)
            return .handled
        }
        .onAppear { if model.all.isEmpty { model.load() } }
    }

    private var header: some View {
        HStack(alignment: .bottom, spacing: Kasane.Space.s6) {
            if model.isLiked {
                LikedCover(size: 220, radius: Kasane.Radius.base)
            } else {
                Cover(url: Ui.large(model.thumbnailUrl), size: 220, radius: Kasane.Radius.base)
            }
            // 題名・アーティスト（アルバムだけ。押すとアーティストのページ）・説明。下端をジャケットの下端にそろえる
            VStack(alignment: .leading, spacing: 0) {
                Text(model.title ?? "").font(.system(size: 56, weight: .bold)).lineLimit(2).minimumScaleFactor(0.5).padding(.bottom, Kasane.Space.s2) // apple-ui-allow: 見出し・歌詞の大きさ（Windows 版と揃える）
                if let artist = model.artistText {
                    LinkText(text: artist) { if let id = model.albumArtist?.browseId { AppServices.nav.artist(id) } }
                        .font(.body.weight(.semibold)).padding(.bottom, 2)
                }
                if let detail = model.detail { Text(detail).foregroundStyle(.secondary).lineLimit(2) }
            }
        }
        .padding(EdgeInsets(top: Kasane.Space.s10, leading: Kasane.Space.s3, bottom: Kasane.Space.s6, trailing: Kasane.Space.s3))
    }

    private func controls(_ player: PlayerService) -> some View {
        HStack(spacing: Kasane.Space.s4) {
            // 押せるとき（曲があるとき）だけ出す
            if !model.rows.isEmpty {
                PlayCircleButton(action: {
                    if model.isPlayingHere { player.togglePlay() } else { model.play(rows: model.rows, shuffle: model.shufflePlay ? true : nil) }
                }) {
                    Image(systemName: model.isPlayingHere && player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20)) // apple-ui-allow: 記号の大きさ（Windows 版と揃える）
                }
            }
            IconToggle(isOn: model.isPlayingHere ? player.shuffle : model.shufflePlay, help: L.t("シャッフル", "Shuffle")) {
                if model.isPlayingHere { player.toggleShuffle() } else { model.shufflePlay.toggle() }
            } label: { Image(systemName: "shuffle").font(.system(size: 20)) } // apple-ui-allow: 記号の大きさ（Windows 版と揃える）
            // アルバムをライブラリに保存・外す（しおり。YouTube Music と同じ）。ログインしていない（状態が分からない）ときは出さない
            if model.request.isAlbum, let saved = model.albumSaved {
                let help = saved ? L.t("ライブラリから削除", "Remove from library") : L.t("ライブラリに保存", "Save to library")
                Button { model.toggleAlbumSaved() } label: {
                    Image(systemName: saved ? "bookmark.fill" : "bookmark").font(.system(size: 20)).foregroundStyle(.secondary)  // apple-ui-allow: 隣の再生・シャッフル・その他と同じ大きさにそろえる
                }
                .buttonStyle(IconButtonStyle())
                .disabled(model.savingAlbum)
                .help(help)
                .accessibilityLabel(help)
                .accessibilityAddTraits(saved ? .isSelected : [])
            }
            // ダウンロード（取っていない／途中／取り終えた。ADR-0016 の 15）。アーティストで絞った高く評価した曲には出さない
            if model.request.artist == nil { CollectionDownloadButton(model: model, size: 20) }
            Menu {
                Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.queueAll(model.rows.map(\.song), next: true) }
                Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.queueAll(model.rows.map(\.song), next: false) }
            } label: { Image(systemName: "ellipsis").font(.system(size: 20)) } // apple-ui-allow: 記号の大きさ（Windows 版と揃える）
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().help(L.t("その他", "More"))
            if model.loading { ProgressView().controlSize(.small) }
            Spacer()
            // 右端: 一覧の中を探す（🔍で開く）と並べ替え（Spotify と同じ）
            Button {
                filterOpen.toggle()
                if filterOpen { filterFocus = true } else { model.filter = "" }
            } label: { Image(systemName: "magnifyingglass") }
                .buttonStyle(IconButtonStyle()).help(L.t("この一覧の中を検索", "Search in this list"))
            if filterOpen {
                TextField(L.t("この一覧の中を検索", "Search in this list"), text: $model.filter)
                    .textFieldStyle(.roundedBorder).frame(width: 240).focused($filterFocus)
                    .onKeyPress(.escape) {
                        if TextInput.composing { return .ignored }  // 変換中の Esc は変換の取り消しに使わせる
                        model.filter = ""
                        filterOpen = false
                        return .handled
                    }
            }
            Menu {
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
            } label: { Text(CollectionModel.label(model.sort)).font(.callout).foregroundStyle(.secondary) }
                .menuStyle(.borderlessButton).fixedSize()
        }
        .padding(EdgeInsets(top: Kasane.Space.s2, leading: Kasane.Space.s3, bottom: Kasane.Space.s4, trailing: Kasane.Space.s3))
    }

    // 曲の種類で絞る（動画や歌ってみたと、ふつうの曲を分ける。Android と同じ）。同じものをもう一度押すと外す
    private var kindChips: some View {
        // 狭い幅では横に送る（チップは縮まないので、はみ出すとページ全体が左右の欄の下へ広がる）
        ScrollView(.horizontal, showsIndicators: false) {
        HStack(spacing: Kasane.Space.s2) {
            Chip(title: L.t("すべて", "All"), isOn: model.kindFilter == nil) { model.kindFilter = nil }
            ForEach([(Song.kindSong, L.t("曲", "Songs")), (Song.kindMusicVideo, L.t("MV", "Music videos")),
                     (Song.kindUserVideo, L.t("その他の動画（歌ってみた・ライブなど）", "Other videos (covers, live…)"))], id: \.0) { kind, title in
                Chip(title: title, isOn: model.kindFilter == kind) { model.kindFilter = model.kindFilter == kind ? nil : kind }
            }
        }
        .padding(EdgeInsets(top: 0, leading: Kasane.Space.s3, bottom: Kasane.Space.s3, trailing: Kasane.Space.s3))
        }
    }
}

/// プレイリストのページの一番下の「おすすめの曲」（Spotify のおすすめと同じ並び）。
private struct PlaylistSuggestionsView: View {
    var model: CollectionModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L.t("おすすめの曲", "Recommended songs")).font(.title2.weight(.bold))
                Text(L.t("このプレイリストの曲をもとに", "Based on what's in this playlist")).font(.callout).foregroundStyle(.secondary)
            }
            .padding(EdgeInsets(top: Kasane.Space.s8, leading: Kasane.Space.s3, bottom: Kasane.Space.s2, trailing: Kasane.Space.s3))
            ForEach(model.suggestions) { song in
                SuggestionRow(song: song, onAdd: { model.addSuggestion(song) }, onPlay: { model.playSuggestion(song) })
            }
            HStack {
                Spacer()
                Button(L.t("更新", "Refresh")) { model.refreshSuggestions() }
                    .buttonStyle(.bordered).buttonBorderShape(.capsule)
                    .disabled(model.suggestionsLoading)
                    .help(L.t("別のおすすめに替える", "Show other recommendations"))
            }
            .padding(.horizontal, Kasane.Space.s3).padding(.top, Kasane.Space.s2)
        }
    }
}

private struct SuggestionRow: View {
    var song: Song
    var onAdd: () -> Void
    var onPlay: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: Kasane.Space.s3) {
            Cover(url: Ui.small(song.thumbnailUrl), size: 40, radius: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).lineLimit(1)
                Text(song.artistsText).subtle()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .layoutPriority(3)
            if let album = song.album?.title {
                Text(album).lineLimit(1).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .layoutPriority(2)
            }
            Button(L.t("追加", "Add"), action: onAdd)
                .buttonStyle(.bordered).buttonBorderShape(.capsule).controlSize(.small)
                // 曲名・アルバムの欄に押しつぶされないよう、文字の幅を保つ
                .fixedSize()
                .help(L.t("このプレイリストに追加", "Add to this playlist"))
        }
        .padding(.horizontal, Kasane.Space.s3).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: Kasane.Radius.sm, style: Kasane.cornerStyle).fill(hover ? Color.primary.opacity(0.06) : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        // 曲の行と同じく、ダブルクリックで流す
        .onTapGesture(count: 2, perform: onPlay)
    }
}
#endif
