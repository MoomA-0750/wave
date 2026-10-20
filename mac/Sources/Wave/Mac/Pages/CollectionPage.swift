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
                TrackHeader(showAlbum: !model.request.isAlbum).padding(.horizontal, 12)
                if let error = model.error { Text(error).padding(EdgeInsets(top: 24, leading: 12, bottom: 24, trailing: 12)) }
                if !model.all.isEmpty && rows.isEmpty {
                    Text(L.t("一致する曲はありません", "No matching songs")).subtle().padding(24)
                }
                ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                    TrackRowView(row: row, selected: selected == row.id,
                                 removeFromPlaylist: model.editable && row.setVideoId != nil ? { model.remove(row) } : nil,
                                 onPlay: { model.play(rows: rows, start: i) }, onSelect: { selected = row.id })
                }
            }
            .padding(EdgeInsets(top: 0, leading: 12, bottom: 24, trailing: 12))
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
        HStack(alignment: .bottom, spacing: 24) {
            if model.isLiked {
                LikedCover(size: 220, radius: 8)
            } else {
                Cover(url: Ui.large(model.thumbnailUrl), size: 220, radius: 8)
            }
            // 題名・アーティスト（アルバムだけ。押すとアーティストのページ）・説明。下端をジャケットの下端にそろえる
            VStack(alignment: .leading, spacing: 0) {
                Text(model.title ?? "").font(.system(size: 56, weight: .bold)).lineLimit(2).minimumScaleFactor(0.5).padding(.bottom, 6)
                if let artist = model.artistText {
                    LinkText(text: artist) { if let id = model.albumArtist?.browseId { AppServices.nav.artist(id) } }
                        .font(.body.weight(.semibold)).padding(.bottom, 2)
                }
                if let detail = model.detail { Text(detail).foregroundStyle(.secondary).lineLimit(2) }
            }
        }
        .padding(EdgeInsets(top: 40, leading: 12, bottom: 24, trailing: 12))
    }

    private func controls(_ player: PlayerService) -> some View {
        HStack(spacing: 16) {
            // 押せるとき（曲があるとき）だけ出す
            if !model.rows.isEmpty {
                PlayCircleButton(action: {
                    if model.isPlayingHere { player.togglePlay() } else { model.play(rows: model.rows, shuffle: model.shufflePlay ? true : nil) }
                }) {
                    Image(systemName: model.isPlayingHere && player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 20))
                }
            }
            IconToggle(isOn: model.isPlayingHere ? player.shuffle : model.shufflePlay, help: L.t("シャッフル", "Shuffle")) {
                if model.isPlayingHere { player.toggleShuffle() } else { model.shufflePlay.toggle() }
            } label: { Image(systemName: "shuffle").font(.system(size: 20)) }
            Menu {
                Button(L.t("次に再生", "Play next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.queueAll(model.rows.map(\.song), next: true) }
                Button(L.t("キューに追加", "Add to queue"), systemImage: "text.badge.plus") { player.queueAll(model.rows.map(\.song), next: false) }
            } label: { Image(systemName: "ellipsis").font(.system(size: 20)) }
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
        .padding(EdgeInsets(top: 8, leading: 12, bottom: 16, trailing: 12))
    }

    // 曲の種類で絞る（動画や歌ってみたと、ふつうの曲を分ける。Android と同じ）。同じものをもう一度押すと外す
    private var kindChips: some View {
        HStack(spacing: 8) {
            Chip(title: L.t("すべて", "All"), isOn: model.kindFilter == nil) { model.kindFilter = nil }
            ForEach([(Song.kindSong, L.t("曲", "Songs")), (Song.kindMusicVideo, L.t("MV", "Music videos")),
                     (Song.kindUserVideo, L.t("その他の動画（歌ってみた・ライブなど）", "Other videos (covers, live…)"))], id: \.0) { kind, title in
                Chip(title: title, isOn: model.kindFilter == kind) { model.kindFilter = model.kindFilter == kind ? nil : kind }
            }
        }
        .padding(EdgeInsets(top: 0, leading: 12, bottom: 12, trailing: 12))
    }
}
#endif
