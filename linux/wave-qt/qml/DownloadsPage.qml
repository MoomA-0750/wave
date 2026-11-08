import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// ダウンロードのページ（GTK 版と同じ並び）: 使っている容量と進み具合・すべて削除、スマートダウンロード（入り切り・曲数・種類）、
// 丸ごと取ったプレイリスト・アルバム、1曲ずつ取った曲、エピソード。
Flickable {
    id: page
    property string pageKind: "downloads"
    property string pageTitle: "ダウンロード"
    property string category: ""
    property var d: JSON.parse(WaveApp.downloadsJson(category))
    contentHeight: column.implicitHeight + 48
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}
    onCategoryChanged: d = JSON.parse(WaveApp.downloadsJson(category))
    Connections {
        target: WaveApp
        function onDownloadsVersionChanged() { page.d = JSON.parse(WaveApp.downloadsJson(page.category)) }
        function onSettingsVersionChanged() { page.d = JSON.parse(WaveApp.downloadsJson(page.category)) }
    }

    ColumnLayout {
        id: column
        x: 24
        y: 24
        width: page.width - 48
        spacing: 12

        RowLayout {
            Layout.fillWidth: true
            spacing: 20
            Rectangle {
                Layout.preferredWidth: 140
                Layout.preferredHeight: 140
                radius: Style.radius
                gradient: Gradient {
                    GradientStop { position: 0; color: "#0f6b5c" }
                    GradientStop { position: 1; color: "#1aa48c" }
                }
                IconButton { anchors.centerIn: parent; iconName: "folder-download-symbolic"; size: 56; enabled: false; icon.color: "white" }
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 6
                Label { text: "プレイリスト • 自動"; font.pixelSize: 12; font.weight: Font.DemiBold; color: Style.dim }
                Label { text: "ダウンロード"; font.pixelSize: 32; font.weight: Font.Bold; color: Style.foreground }
                Label { Layout.fillWidth: true; text: page.d.status || ""; wrapMode: Text.Wrap; color: Style.dim }
                ProgressBar { visible: page.d.progress >= 0; value: Math.max(0, page.d.progress); Layout.fillWidth: true }
                Btn { text: "すべて削除"; enabled: !page.d.empty; onClicked: clearDialog.open() }
            }
        }

        Section { text: "スマートダウンロード"; Layout.topMargin: 12 }
        RowLayout {
            Layout.fillWidth: true
            spacing: 16
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Label { text: "聴きそうな曲を自動で取っておく"; color: Style.foreground }
                Label { Layout.fillWidth: true; text: "いつも聴いている曲・好きそうな曲・おすすめから、毎日選び直します"; wrapMode: Text.Wrap; font.pixelSize: 12; color: Style.dim }
            }
            ComboBox {
                visible: !!page.d.smartOn
                implicitWidth: 120
                Material.roundedScale: Style.radius
                model: (page.d.counts || []).map(c => c + " 曲")
                currentIndex: Math.max(0, (page.d.counts || []).indexOf(page.d.smartCount))
                onActivated: (i) => WaveApp.setSetting("smart_download_count", JSON.stringify(page.d.counts[i]))
            }
            Switch { checked: !!page.d.smartOn; onToggled: WaveApp.setSetting("smart_downloads", JSON.stringify(checked)) }
        }
        Row {
            visible: !!page.d.smartOn
            spacing: 8
            Repeater {
                model: [["", "すべて"], ["Frequent", "いつも聴いている"], ["Likely", "好きそう"], ["Recommended", "おすすめ"]]
                delegate: Chip { text: modelData[1]; on: page.category === modelData[0]; onClicked: page.category = modelData[0] }
            }
        }
        RowLayout {
            visible: !!page.d.smartOn
            spacing: 8
            PlayButton { enabled: (page.d.smart || []).length > 0; onClicked: WaveApp.playSongs(JSON.stringify(page.d.smart), 0, "", false) }
            IconButton { iconName: "media-playlist-shuffle-symbolic"; tip: "シャッフル再生"; enabled: (page.d.smart || []).length > 0; onClicked: WaveApp.playSongs(JSON.stringify(page.d.smart), 0, "", true) }
        }
        Label { visible: !!page.d.smartOn && (page.d.smart || []).length === 0; text: "まだ選んだ曲はありません"; color: Style.dim }
        Repeater {
            model: page.d.smartOn ? page.d.smart : []
            delegate: DownloadRow { Layout.fillWidth: true; song: modelData; onPlay: WaveApp.playSongs(JSON.stringify(page.d.smart), index, "", false) }
        }

        Section { visible: (page.d.collections || []).length > 0; text: "プレイリスト・アルバム"; Layout.topMargin: 12 }
        Repeater {
            model: page.d.collections || []
            delegate: ItemDelegate {
                Layout.fillWidth: true
                height: 60
                contentItem: RowLayout {
                    spacing: 12
                    Art { Layout.preferredWidth: 46; Layout.preferredHeight: 46; source: modelData.thumb }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 1
                        Label { Layout.fillWidth: true; text: modelData.title; elide: Text.ElideRight; color: Style.foreground }
                        Label { Layout.fillWidth: true; text: modelData.subtitle; elide: Text.ElideRight; font.pixelSize: 12; color: Style.dim }
                    }
                    IconButton { iconName: "user-trash-symbolic"; tip: "ダウンロードを削除"; size: 16; onClicked: WaveApp.removeCollection(modelData.id) }
                }
                onClicked: Style.nav.openItem(modelData)
            }
        }

        Section { visible: (page.d.songs || []).length > 0; text: "曲"; Layout.topMargin: 12 }
        Repeater {
            model: page.d.songs || []
            delegate: DownloadRow { Layout.fillWidth: true; song: modelData; onPlay: WaveApp.playSongs(JSON.stringify(page.d.songs), index, "", false) }
        }
        Section { visible: (page.d.episodes || []).length > 0; text: "エピソード"; Layout.topMargin: 12 }
        Repeater {
            model: page.d.episodes || []
            delegate: DownloadRow { Layout.fillWidth: true; song: modelData; onPlay: WaveApp.playSongs(JSON.stringify(page.d.episodes), index, "", false) }
        }
        Label {
            visible: (page.d.collections || []).length === 0 && (page.d.songs || []).length === 0 && (page.d.episodes || []).length === 0
            Layout.fillWidth: true
            Layout.topMargin: 16
            wrapMode: Text.Wrap
            color: Style.dim
            text: "曲・プレイリスト・アルバムのメニューやページの頭のボタンから、ダウンロードできます。通信の無いところでも聴けます。"
        }
    }

    WDialog {
        id: clearDialog
        title: "ダウンロードをすべて削除しますか？"
        Label { width: 380; wrapMode: Text.Wrap; text: "この端末に取った曲・エピソードをすべて消します。" }
        footer: DialogButtonBox {
            background: null
            Btn { text: "キャンセル"; flat: true; DialogButtonBox.buttonRole: DialogButtonBox.RejectRole }
            Btn { text: "すべて削除"; highlighted: true; DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole }
        }
        onAccepted: WaveApp.clearDownloads()
    }

    // 取った曲の行（取った・待っている・取っている の印と、ダウンロードを削除）
    component DownloadRow: TrackRow {
        showAlbum: false
        removal: ({ label: "ダウンロードを削除", action: () => WaveApp.removeDownload(song.id) })
        subtitleText: (song.state === 3 ? "" : song.state === 2 ? "取っています • " : "待っています • ") + (song.subtitle || "")
    }
}
