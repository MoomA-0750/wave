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
    readonly property bool hasUserQueue: entries.some(e => e.section === Style.t("キューの次の曲", "Next in queue"))

    ColumnLayout {
        anchors.fill: parent
        spacing: 4
        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 12
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            spacing: 6
            Chip { text: Style.t("キュー", "Queue"); on: !pane.history; onClicked: pane.history = false }
            Chip { text: Style.t("履歴", "History"); on: pane.history; onClicked: pane.history = true }
            Item { Layout.fillWidth: true }
            Btn { text: Style.t("キューを消去", "Clear queue"); flat: true; visible: !pane.history && pane.hasUserQueue; onClicked: WaveApp.clearUserQueue() }
            // キューをプレイリストとして保存（ログインしていて手元のキューに曲があるとき。ほかの端末を映している間は出さない）
            IconButton { iconName: "bookmark-new-symbolic"; tip: Style.t("キューをプレイリストとして保存", "Save queue as playlist"); size: 16; visible: !pane.history && WaveApp.loggedIn && WaveApp.remoteLabel === "" && pane.entries.length > 0; onClicked: pane.askSave() }
            IconButton { iconName: "window-close-symbolic"; tip: Style.t("閉じる", "Close"); size: 16; onClicked: pane.shell.setRight(true, "now") }
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
                    Btn { visible: modelData.flowHeader; text: Style.t("消去", "Clear"); flat: true; onClicked: WaveApp.clearFlow() }
                }
                TrackRow {
                    width: parent.width
                    song: modelData
                    showAlbum: false
                    current: modelData.current
                    removal: pane.history || modelData.current ? null : { label: Style.t("キューから削除", "Remove from queue"), action: () => WaveApp.removeQueueItem(modelData.itemId) }
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
            Label { Layout.alignment: Qt.AlignHCenter; text: pane.history ? Style.t("まだ流れた曲はありません", "Nothing played yet") : Style.t("キューは空です", "Your queue is empty"); font.weight: Font.Bold; color: Style.foreground }
            Label { Layout.alignment: Qt.AlignHCenter; Layout.fillWidth: true; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap; text: pane.history ? Style.t("流れた曲は、ここに新しい順に並びます", "Songs you've played will show up here") : Style.t("曲を再生すると、ここに次の曲が並びます", "Play something and upcoming songs will show up here"); font.pixelSize: 12; color: Style.dim }
            Item { Layout.fillHeight: true }
        }
    }

    // キューをプレイリストとして保存する（名前を聞く。既定は「〈キューの先頭の曲名〉のラジオ」）
    property int infoToken: -1
    property int saveToken: -1
    property int saveCount: 0
    function askSave() { infoToken = WaveApp.request("queue.saveInfo", "", "") }
    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t === pane.infoToken) {
                const v = JSON.parse(json)
                if (!v.count) { WaveApp.toast(Style.t("保存できる曲がキューにありません", "No songs in the queue to save")); return }
                pane.saveCount = v.count
                saveTitle.text = v.title
                saveDialog.open()
                saveTitle.forceActiveFocus()
                saveTitle.selectAll()
            }
        }
    }
    WDialog {
        id: saveDialog
        title: Style.t("キューをプレイリストとして保存", "Save queue as playlist")
        ColumnLayout {
            width: parent ? parent.width : 400
            spacing: 10
            Label { Layout.fillWidth: true; wrapMode: Text.Wrap; text: Style.ja ? "キューの " + pane.saveCount + " 曲を、新しい非公開のプレイリストに入れます。" : "Puts the " + Style.songs(pane.saveCount) + " in the queue into a new private playlist."; color: Style.dim }
            // 名前の見出しは欄の外に置く（Material の placeholderText は文字が入ると枠の上へ浮き、自前の背景では文字に重なる）
            Label { text: Style.t("プレイリスト名", "Playlist name"); color: Style.dim; font.pixelSize: 12 }
            TextField {
                id: saveTitle
                Layout.fillWidth: true
                background: Rectangle { radius: Style.small; color: Style.view; border.color: saveTitle.activeFocus ? Style.accent : Style.border }
                onAccepted: if (text.trim() !== "") saveDialog.accept()
            }
        }
        footer: DialogButtonBox {
            background: null
            Btn { text: Style.t("キャンセル", "Cancel"); flat: true; DialogButtonBox.buttonRole: DialogButtonBox.RejectRole }
            Btn { text: Style.t("保存", "Save"); highlighted: true; enabled: saveTitle.text.trim() !== ""; DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole }
        }
        onAccepted: pane.saveToken = WaveApp.request("queue.save", saveTitle.text.trim(), "")
    }
}
