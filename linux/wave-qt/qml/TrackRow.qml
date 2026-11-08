import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import wave

// 曲の1行（番号かジャケット・曲名・アーティスト・アルバム・長さ・⋯）。ダブルクリックで鳴らし、右クリック・⋯でメニュー。
Rectangle {
    id: row
    property var song: ({})
    property int number: 0
    property bool showArt: true
    property bool showAlbum: true
    property bool current: WaveApp.videoId !== "" && song.id === WaveApp.videoId
    property var removal: null
    // 2行目（既定はアルバムの列があればアーティスト、無ければ「アーティスト • アルバム」）
    property string subtitleText: showAlbum ? (song.artists || "") : (song.subtitle || "")
    signal play()
    implicitHeight: 56
    radius: Style.small
    color: hover.hovered ? Style.hover : "transparent"

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 8
        anchors.rightMargin: 8
        spacing: 12
        Label {
            visible: row.number > 0
            text: row.number
            Layout.preferredWidth: 28
            horizontalAlignment: Text.AlignRight
            color: row.current ? Style.accent : Style.dim
            font.features: { "tnum": 1 }
        }
        Art {
            visible: row.showArt
            Layout.preferredWidth: 40
            Layout.preferredHeight: 40
            source: row.song.thumb || ""
        }
        ColumnLayout {
            spacing: 2
            Layout.fillWidth: true
            Layout.preferredWidth: 3
            Label {
                Layout.fillWidth: true
                text: row.song.title || ""
                elide: Text.ElideRight
                lineHeightMode: Text.FixedHeight
                lineHeight: 20
                verticalAlignment: Text.AlignVCenter
                color: row.current ? Style.accent : Style.foreground
            }
            Label {
                Layout.fillWidth: true
                text: row.subtitleText
                visible: text !== ""
                elide: Text.ElideRight
                lineHeightMode: Text.FixedHeight
                lineHeight: 16
                verticalAlignment: Text.AlignVCenter
                font.pixelSize: 12
                color: Style.dim
            }
        }
        Label {
            visible: row.showAlbum && row.width > 640
            Layout.fillWidth: true
            Layout.preferredWidth: 2
            text: row.song.album || ""
            elide: Text.ElideRight
            color: Style.dim
            font.pixelSize: 13
        }
        // 長さ（指を乗せている間は同じ場所に ⋯）
        Item {
            Layout.preferredWidth: 44
            Layout.fillHeight: true
            Label {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: !hover.hovered
                text: row.song.live ? "LIVE" : (row.song.duration || "")
                color: row.song.live ? Style.red : Style.dim
                font.pixelSize: 12
                font.features: { "tnum": 1 }
            }
            IconButton {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                visible: hover.hovered
                iconName: "view-more-symbolic"
                tip: "その他"
                size: 16
                onClicked: menu.popup()
            }
        }
    }
    HoverHandler { id: hover }
    TapHandler {
        acceptedButtons: Qt.LeftButton
        onDoubleTapped: row.play()
    }
    TapHandler {
        acceptedButtons: Qt.RightButton
        onTapped: menu.popup()
    }
    SongMenu { id: menu; item: row.song; removal: row.removal }
}
