#if os(iOS)
import SwiftUI
import WaveCore

/// キュー（Android の QueueSheet・Mac の QueueView と同じ並び）。一番上に今の曲、その下に自分で足した曲（「キューを消去」でまとめて外せる）、
/// その下に元から流れる予定の曲。もう流れた曲は「履歴」に分ける。
/// 次の曲は長押しのドラッグ（または「編集」のつまみ）で並べ替え、左へ払うと外し、押すとその曲へ。ほかの端末のキューを映している間は並べ替え・外すは出さない。
struct QueueView: View {
    @State private var history = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let player = AppServices.player!
        NavigationStack {
            List {
                Section {
                    QueueModeControls(history: $history)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                }
                if history {
                    historySection(player)
                } else {
                    upcomingSections(player)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(L.t("キュー", "Queue"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(L.t("閉じる", "Close"))
                }
                if !history && !player.isRemote && player.queue.count > player.index + 1 {
                    ToolbarItem(placement: .topBarTrailing) { EditButton() }
                }
            }
        }
        .presentationDragIndicator(.visible)
    }

    // MARK: 次の曲

    @ViewBuilder
    private func upcomingSections(_ player: PlayerService) -> some View {
        let count = player.queue.count
        let current = player.index
        let userEnd = player.userQueueEnd
        if current >= 0 && current < count {
            Section(L.t("再生中", "Now playing")) {
                QueueSongRow(position: current, isCurrent: true)
            }
        }
        if current + 1 >= count {
            // 次の曲が無い
            Section {
                QueueEmptyNote(title: L.t("キューは空です", "Your queue is empty"),
                               detail: L.t("曲を再生すると、ここに次の曲が並びます", "Play something and upcoming songs will show up here"))
            }
        }
        if current >= 0 && userEnd > current + 1 {
            Section {
                rows(player, Array(current + 1..<min(userEnd, count)))
            } header: {
                HStack {
                    Text(L.t("キューの次の曲", "Next in queue"))
                    Spacer()
                    if !player.isRemote {
                        Button(L.t("キューを消去", "Clear queue")) { player.clearUserQueue() }
                            .font(.footnote.weight(.semibold))
                            .textCase(nil)
                    }
                }
            }
        }
        if userEnd < count {
            Section(L.t("次に流れる曲", "Next up")) {
                rows(player, Array(userEnd..<count))
            }
        }
    }

    /// 1つのかたまりの行。並べ替え・外すはかたまりの中で行う（今の曲より前には置かない）。
    private func rows(_ player: PlayerService, _ positions: [Int]) -> some View {
        let start = positions.first ?? 0
        return ForEach(positions.map { QueuePosition(position: $0, id: QueueView.itemId(player, $0)) }) { item in
            QueueSongRow(position: item.position, isCurrent: false)
        }
        .onMove { from, to in
            guard let first = from.first else { return }
            let dest = to > first ? to - 1 : to
            player.moveUpcoming(start + first, start + dest)
        }
        .onDelete { offsets in
            // 後ろから外す（前を外すと後ろの位置がずれる）
            for offset in offsets.sorted(by: >) { player.removeAt(start + offset) }
        }
        .moveDisabled(player.isRemote)
        .deleteDisabled(player.isRemote)
    }

    // MARK: 履歴

    @ViewBuilder
    private func historySection(_ player: PlayerService) -> some View {
        let played = Array((0..<max(0, min(player.index, player.queue.count))).reversed())
        if played.isEmpty {
            Section {
                QueueEmptyNote(title: L.t("まだ流れた曲はありません", "Nothing played yet"),
                               detail: L.t("流れた曲は、ここに新しい順に並びます", "Songs you've played will show up here"))
            }
        } else {
            Section(L.t("履歴", "History")) {
                ForEach(played.map { QueuePosition(position: $0, id: QueueView.itemId(player, $0)) }) { item in
                    QueueSongRow(position: item.position, isCurrent: false)
                }
            }
        }
    }

    /// 行の見分け（キューの項目の ID。並べ替えても同じ行として動かす）。
    static func itemId(_ player: PlayerService, _ position: Int) -> String {
        position < player.itemMeta.count ? player.itemMeta[position].itemId : "p\(position)"
    }
}

private struct QueuePosition: Identifiable {
    var position: Int
    var id: String
}

/// 上の切り替え: キュー｜履歴、シャッフル・リピート。
private struct QueueModeControls: View {
    @Binding var history: Bool

    var body: some View {
        let player = AppServices.player!
        HStack(spacing: 12) {
            Picker("", selection: $history) {
                Text(L.t("キュー", "Queue")).tag(false)
                Text(L.t("履歴", "History")).tag(true)
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 220)
            Spacer(minLength: 0)
            Button(action: player.toggleShuffle) {
                Image(systemName: "shuffle")
                    .foregroundStyle(player.shuffle ? Color.accentColor : Color.primary)
                    .frame(width: 44, height: 44)
                    .glassCard(22, interactive: true)
            }
            .accessibilityLabel(L.t("シャッフル", "Shuffle"))
            .accessibilityValue(player.shuffle ? L.t("オン", "On") : L.t("オフ", "Off"))
            Button(action: player.cycleRepeat) {
                Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                    .foregroundStyle(player.repeatMode != .off ? Color.accentColor : Color.primary)
                    .frame(width: 44, height: 44)
                    .glassCard(22, interactive: true)
            }
            .accessibilityLabel(L.t("リピート", "Repeat"))
        }
        .buttonStyle(.plain)
    }
}

/// キューの1行（ジャケット・曲名・アーティスト・「…」）。押すとその曲へ（今の曲は何もしない）。
private struct QueueSongRow: View {
    var position: Int
    var isCurrent: Bool

    var body: some View {
        let player = AppServices.player!
        if position < player.queue.count {
            let song = player.queue[position]
            HStack(spacing: 12) {
                Cover(url: Ui.small(song.thumbnailUrl), size: 44, radius: 4)
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title).lineLimit(1)
                        .fontWeight(isCurrent ? .semibold : .regular)
                        .foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                    Text(song.artistsText).subtle()
                }
                Spacer(minLength: 0)
                if isCurrent && player.isPlaying {
                    Image(systemName: "waveform").foregroundStyle(Color.accentColor).symbolEffect(.variableColor.iterative)
                }
                Menu {
                    // 外せるのは今の曲より後ろだけ（ほかの端末のキューは外せない。Android と同じ）
                    SongMenuItems(song: song, queueIndex: !isCurrent && position > player.index && !player.isRemote ? position : nil)
                } label: {
                    Image(systemName: "ellipsis").frame(width: 32, height: 44).contentShape(Rectangle())
                }
                .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
            .onTapGesture { if !isCurrent { player.playAt(position) } }
        }
    }
}

private struct QueueEmptyNote: View {
    var title: String
    var detail: String

    var body: some View {
        VStack(spacing: 6) {
            Text(title).font(.headline)
            Text(detail).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
    }
}
#endif
