import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// 右の欄のキュー（GTK 版・Mac の QueueView と同じ）: 上に「キュー｜履歴」・キューを消去・×。今の曲はアクセント色、
// 押すとその曲へ、右クリックで曲のメニュー（キューから削除も）。見出しは「再生中」「キューの次の曲」「次に流れる曲」。
Item {
    id: pane
    property var shell
    property bool history: false
    readonly property var entries: JSON.parse(history ? (WaveApp.historyJson || "[]") : (WaveApp.queueJson || "[]"))
    readonly property bool hasUserQueue: entries.some(e => e.section === "キューの次の曲")

    ColumnLayout {
        anchors.fill: parent
        spacing: 4
        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 12
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            spacing: 6
            Chip { text: "キュー"; on: !pane.history; onClicked: pane.history = false }
            Chip { text: "履歴"; on: pane.history; onClicked: pane.history = true }
            Item { Layout.fillWidth: true }
            Btn { text: "キューを消去"; flat: true; visible: !pane.history && pane.hasUserQueue; onClicked: WaveApp.clearUserQueue() }
            IconButton { iconName: "window-close-symbolic"; tip: "閉じる"; size: 16; onClicked: pane.shell.setRight(true, "now") }
        }
        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.leftMargin: 8
            Layout.rightMargin: 8
            clip: true
            model: pane.entries
            ScrollBar.vertical: ScrollBar {}
            delegate: Column {
                width: ListView.view.width
                RowLayout {
                    width: parent.width
                    visible: modelData.section !== ""
                    height: visible ? 40 : 0
                    Label { text: modelData.section; font.weight: Font.Bold; Layout.leftMargin: 8; Layout.fillWidth: true; color: Style.foreground }
                    Btn { visible: modelData.flowHeader; text: "消去"; flat: true; onClicked: WaveApp.clearFlow() }
                }
                TrackRow {
                    width: parent.width
                    song: modelData
                    showAlbum: false
                    current: modelData.current
                    removal: pane.history || modelData.current ? null : { label: "キューから削除", action: () => WaveApp.removeQueueItem(modelData.itemId) }
                    onPlay: WaveApp.playQueueItem(modelData.index)
                    TapHandler { onTapped: WaveApp.playQueueItem(modelData.index) }
                }
            }
        }
        ColumnLayout {
            visible: pane.entries.length === 0
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.margins: 24
            spacing: 6
            Item { Layout.fillHeight: true }
            Label { Layout.alignment: Qt.AlignHCenter; text: pane.history ? "履歴はありません" : "キューは空です"; font.weight: Font.Bold; color: Style.foreground }
            Label { Layout.alignment: Qt.AlignHCenter; Layout.fillWidth: true; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap; text: pane.history ? "曲を聴くと、ここに並びます" : "曲を再生すると、次に流れる曲がここに並びます"; font.pixelSize: 12; color: Style.dim }
            Item { Layout.fillHeight: true }
        }
    }
}
