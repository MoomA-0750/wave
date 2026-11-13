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
    property string pageTitle: Style.t("ダウンロード", "Downloads")
    property string category: ""
    property var d: JSON.parse(WaveApp.downloadsJson(category))
    contentHeight: column.implicitHeight + Style.pageBottom
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
                Label { text: Style.t("プレイリスト • 自動", "Playlist • Auto"); font.pixelSize: 12; font.weight: Font.DemiBold; color: Style.dim }
                Label { text: Style.t("ダウンロード", "Downloads"); font.pixelSize: 32; font.weight: Font.Bold; color: Style.foreground }
                Label { Layout.fillWidth: true; text: page.d.status || ""; wrapMode: Text.Wrap; color: Style.dim }
                ProgressBar { visible: page.d.progress >= 0; value: Math.max(0, page.d.progress); Layout.fillWidth: true }
                Btn { text: Style.t("すべて削除", "Delete all"); enabled: !page.d.empty; onClicked: clearDialog.open() }
            }
        }

        Section { text: Style.t("スマートダウンロード", "Smart downloads"); Layout.topMargin: 12 }
        RowLayout {
            Layout.fillWidth: true
            spacing: 16
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Label { text: Style.t("聴きそうな曲を自動で取っておく", "Automatically download songs you're likely to play"); color: Style.foreground }
                Label { Layout.fillWidth: true; text: Style.t("いつも聴いている曲・好きそうな曲・おすすめから、毎日選び直します", "Picked again every day from songs you play often, songs you might like and recommendations"); wrapMode: Text.Wrap; font.pixelSize: 12; color: Style.dim }
            }
            ComboBox {
                visible: !!page.d.smartOn
                implicitWidth: 120
                Material.roundedScale: Style.radius
                model: (page.d.counts || []).map(c => Style.songs(c))
                currentIndex: Math.max(0, (page.d.counts || []).indexOf(page.d.smartCount))
                onActivated: (i) => WaveApp.setSetting("smart_download_count", JSON.stringify(page.d.counts[i]))
            }
            Switch { checked: !!page.d.smartOn; onToggled: WaveApp.setSetting("smart_downloads", JSON.stringify(checked)) }
        }
        Row {
            visible: !!page.d.smartOn
            spacing: 8
            Repeater {
                model: [["", Style.t("すべて", "All")], ["Frequent", Style.t("いつも聴いている", "Played often")], ["Likely", Style.t("好きそう", "You might like")], ["Recommended", Style.t("おすすめ", "Recommended")]]
                delegate: Chip { text: modelData[1]; on: page.category === modelData[0]; onClicked: page.category = modelData[0] }
            }
        }
        RowLayout {
            visible: !!page.d.smartOn
            spacing: 8
            PlayButton { enabled: (page.d.smart || []).length > 0; onClicked: WaveApp.playSongs(JSON.stringify(page.d.smart), 0, "", false) }
            IconButton { iconName: "media-playlist-shuffle-symbolic"; tip: Style.t("シャッフル再生", "Shuffle play"); enabled: (page.d.smart || []).length > 0; onClicked: WaveApp.playSongs(JSON.stringify(page.d.smart), 0, "", true) }
        }
        Label { visible: !!page.d.smartOn && (page.d.smart || []).length === 0; text: Style.t("まだ選んだ曲はありません", "Nothing chosen yet"); color: Style.dim }
        Repeater {
            model: page.d.smartOn ? page.d.smart : []
            delegate: DownloadRow { Layout.fillWidth: true; song: modelData; onPlay: WaveApp.playSongs(JSON.stringify(page.d.smart), index, "", false) }
        }

        Section { visible: (page.d.collections || []).length > 0; text: Style.t("プレイリスト・アルバム", "Playlists and albums"); Layout.topMargin: 12 }
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
                    IconButton { iconName: "user-trash-symbolic"; tip: Style.t("ダウンロードを削除", "Remove download"); size: 16; onClicked: WaveApp.removeCollection(modelData.id) }
                }
                onClicked: Style.nav.openItem(modelData)
            }
        }

        Section { visible: (page.d.songs || []).length > 0; text: Style.t("曲", "Songs"); Layout.topMargin: 12 }
        Repeater {
            model: page.d.songs || []
            delegate: DownloadRow { Layout.fillWidth: true; song: modelData; onPlay: WaveApp.playSongs(JSON.stringify(page.d.songs), index, "", false) }
        }
        Section { visible: (page.d.episodes || []).length > 0; text: Style.t("エピソード", "Episodes"); Layout.topMargin: 12 }
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
            text: Style.t("曲・プレイリスト・アルバムのメニューやページの頭のボタンから、ダウンロードできます。通信の無いところでも聴けます。", "Download songs, playlists and albums from their menu or the button at the top of their page, and listen without a connection.")
        }
    }

    WDialog {
        id: clearDialog
        title: Style.t("ダウンロードをすべて削除しますか？", "Delete all downloads?")
        Label { width: 440; wrapMode: Text.Wrap; text: Style.t("この端末に取った曲・エピソードをすべて消します。", "This removes every song and episode saved on this PC.") }
        footer: DialogButtonBox {
            background: null
            Btn { text: Style.t("キャンセル", "Cancel"); flat: true; DialogButtonBox.buttonRole: DialogButtonBox.RejectRole }
            Btn { text: Style.t("すべて削除", "Delete all"); highlighted: true; DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole }
        }
        onAccepted: WaveApp.clearDownloads()
    }

    // 取った曲の行（取った・待っている・取っている の印と、ダウンロードを削除）
    component DownloadRow: TrackRow {
        showAlbum: false
        removal: ({ label: Style.t("ダウンロードを削除", "Remove download"), action: () => WaveApp.removeDownload(song.id) })
        subtitleText: (song.state === 3 ? "" : song.state === 2 ? Style.t("取っています • ", "Downloading • ") : Style.t("待っています • ", "Waiting • ")) + (song.subtitle || "")
    }
}
