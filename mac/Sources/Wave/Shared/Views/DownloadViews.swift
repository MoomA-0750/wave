import SwiftUI
import WaveCore

/// 曲・エピソードのメニューの「ダウンロード」「ダウンロードを削除」（ADR-0016 の 16）。ライブ配信には出さない。
/// プレイリスト・アルバムで丸ごと取っている曲は、そのページのボタンで外す（ここには出さない）。
struct DownloadMenuItems: View {
    var song: Song
    var episode = false

    var body: some View {
        let downloads = AppServices.downloads!
        if !song.isLive {
            if downloads.canRemove(song.videoId) {
                Button(L.t("ダウンロードを削除", "Remove download"), systemImage: "arrow.down.circle.dotted") { downloads.remove(song.videoId) }
            } else if !downloads.isWanted(song.videoId) {
                Button(L.t("ダウンロード", "Download"), systemImage: "arrow.down.circle") {
                    downloads.download(song, episode: episode)
                    AppServices.toast.show(L.t("ダウンロードに追加しました", "Added to downloads"))
                }
            }
        }
    }
}

/// 取り終えた曲の行に出す小さな印。
struct DownloadedMark: View {
    var videoId: String
    var font: Font = .caption2

    var body: some View {
        if AppServices.downloads.isDownloaded(videoId) {
            Image(systemName: "arrow.down.circle.fill")
                .font(font)
                .foregroundStyle(.secondary)
                .accessibilityLabel(L.t("ダウンロード済み", "Downloaded"))
                .help(L.t("ダウンロード済み", "Downloaded"))
        }
    }
}

/// プレイリスト・アルバム・高く評価した曲のページの頭のダウンロードのボタン（取っていない／取っている途中（何曲中何曲）／取り終えた。ADR-0016 の 15）。
/// 取っているものを押すと、消すか確かめる。
struct CollectionDownloadButton: View {
    var model: CollectionModel
    var size: CGFloat = 22
    @State private var confirm = false

    var body: some View {
        let state = model.downloadState
        Button {
            if state == .none { model.download() } else { confirm = true }
        } label: {
            label(state)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(state == .none && !model.canDownload)
        .accessibilityLabel(accessibility(state))
        .help(accessibility(state))
        .confirmationDialog(L.t("ダウンロードを削除しますか？", "Remove download?"), isPresented: $confirm, titleVisibility: .visible) {
            Button(L.t("ダウンロードを削除", "Remove download"), role: .destructive) { model.removeDownload() }
            Button(L.t("キャンセル", "Cancel"), role: .cancel) {}
        } message: {
            Text(L.t("この端末に取った曲を消します（ほかのダウンロードにも入っている曲は残します）。",
                     "Removes the songs saved on this device (songs that are also in other downloads stay)."))
        }
    }

    @ViewBuilder
    private func label(_ state: Downloads.CollectionState) -> some View {
        switch state {
        case .none:
            Image(systemName: "arrow.down.circle").font(.system(size: size)).foregroundStyle(.secondary)  // apple-ui-allow: ボタンの大きさを渡して合わせる
        case .downloading(let done, let total):
            HStack(spacing: 6) {
                ZStack {
                    Circle().stroke(Color.secondary.opacity(0.3), lineWidth: 2.5)
                    Circle().trim(from: 0, to: total > 0 ? CGFloat(done) / CGFloat(total) : 0)
                        .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Image(systemName: "arrow.down").font(.system(size: size * 0.45, weight: .bold)).foregroundStyle(Color.accentColor)  // apple-ui-allow: 輪の大きさに合わせた記号
                }
                .frame(width: size, height: size)
                Text("\(done) / \(total)").font(.caption).monospacedDigit().foregroundStyle(.secondary)
            }
        case .done:
            Image(systemName: "arrow.down.circle.fill").font(.system(size: size)).foregroundStyle(Color.accentColor)  // apple-ui-allow: ボタンの大きさを渡して合わせる
        }
    }

    private func accessibility(_ state: Downloads.CollectionState) -> String {
        switch state {
        case .none: L.t("ダウンロード", "Download")
        case .downloading(let done, let total): L.t("ダウンロード中（\(total) 曲中 \(done) 曲）", "Downloading (\(done) of \(total))")
        case .done: L.t("ダウンロード済み", "Downloaded")
        }
    }
}
