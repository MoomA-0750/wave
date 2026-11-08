import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// アーティストのページ: 上に写真と名前・再生・シャッフル・登録、人気の曲（5曲）、アルバム・シングルの棚、動画の棚。
Flickable {
    id: page
    property string itemId: ""
    property string pageTitle: ""
    property string pageKind: "artist"
    property var info: null
    property bool failed: false
    property int subscribed: -1
    property int token: -1

    contentHeight: column.implicitHeight + Style.pageBottom
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}

    Component.onCompleted: token = WaveApp.request("artist", itemId, "")
    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== page.token) return
            const v = JSON.parse(json)
            if (v.error) { page.failed = true; return }
            page.info = v
            page.subscribed = v.subscribed
        }
    }

    ColumnLayout {
        id: column
        width: page.width - 32
        x: 16
        y: 24
        spacing: 28

        RowLayout {
            Layout.fillWidth: true
            spacing: 24
            Art { Layout.preferredWidth: 180; Layout.preferredHeight: 180; source: page.info ? page.info.art : ""; round: true; placeholder: "avatar-default-symbolic" }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 10
                Label { text: "アーティスト"; font.pixelSize: 12; font.weight: Font.DemiBold; color: Style.dim }
                Label { Layout.fillWidth: true; text: page.info ? page.info.title : page.pageTitle; font.pixelSize: 36; font.weight: Font.Bold; elide: Text.ElideRight; color: Style.foreground }
                RowLayout {
                    spacing: 8
                    visible: !!page.info && page.info.topSongs.length > 0
                    PlayButton {
                        onClicked: WaveApp.playItem(JSON.stringify({ kind: "artist", id: page.itemId }), false)
                    }
                    IconButton { iconName: "media-playlist-shuffle-symbolic"; tip: "シャッフル再生"; size: 22; onClicked: WaveApp.playItem(JSON.stringify({ kind: "artist", id: page.itemId }), true) }
                    Btn {
                        visible: WaveApp.loggedIn && !!page.info && page.info.channelId !== "" && page.subscribed >= 0
                        text: page.subscribed === 1 ? "登録済み" : "登録"
                        flat: page.subscribed === 1
                        onClicked: { const on = page.subscribed !== 1; page.subscribed = on ? 1 : 0; WaveApp.setSubscribed(page.info.channelId, on) }
                    }
                }
            }
        }
        BusyIndicator { visible: !page.info && !page.failed; running: visible; Layout.alignment: Qt.AlignHCenter }
        Label { visible: page.failed; text: "アーティストを読み込めませんでした"; color: Style.dim }

        ColumnLayout {
            visible: !!page.info && page.info.topSongs.length > 0
            Layout.fillWidth: true
            spacing: 4
            Section { text: "人気の曲" }
            Repeater {
                model: page.info ? page.info.topSongs.slice(0, 5) : []
                delegate: TrackRow {
                    Layout.fillWidth: true
                    song: modelData
                    number: index + 1
                    showAlbum: false
                    onPlay: WaveApp.playSongs(JSON.stringify(page.info.topSongs), index, page.itemId, false)
                }
            }
        }
        Repeater {
            model: page.info ? page.info.albumShelves : []
            delegate: Shelf { Layout.fillWidth: true; title: modelData.title; items: modelData.items }
        }
        Shelf { visible: !!page.info && page.info.videos.length > 0; Layout.fillWidth: true; title: "動画"; items: page.info ? page.info.videos : [] }
    }
}
