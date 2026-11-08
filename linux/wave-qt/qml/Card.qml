import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import wave

// 棚・格子の1枚（ジャケットと名前・補足）。押すと開き（曲は1曲＋ラジオで鳴らす）、指を乗せると再生のボタン、右クリックでメニュー。
Item {
    id: card
    property var item: ({})
    property int size: 168
    width: size
    implicitHeight: column.implicitHeight

    Column {
        id: column
        width: parent.width
        spacing: 6
        Art {
            id: art
            width: card.size
            height: card.size
            source: card.item.art || card.item.thumb || ""
            round: !!card.item.round
            radius: Style.radius
            PlayButton {
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 8
                implicitWidth: 44
                implicitHeight: 44
                visible: hover.hovered && card.item.kind !== "podcast"
                onClicked: WaveApp.playItem(JSON.stringify(card.item), false)
            }
        }
        Label {
            width: parent.width
            text: card.item.title || ""
            font.weight: Font.DemiBold
            elide: Text.ElideRight
            maximumLineCount: 2
            wrapMode: Text.Wrap
            color: Style.foreground
        }
        Label {
            width: parent.width
            text: card.item.subtitle || ""
            visible: text !== ""
            font.pixelSize: 12
            elide: Text.ElideRight
            maximumLineCount: 2
            wrapMode: Text.Wrap
            color: Style.dim
        }
    }
    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler {
        acceptedButtons: Qt.LeftButton
        onTapped: Style.nav.openItem(card.item)
    }
    TapHandler {
        acceptedButtons: Qt.RightButton
        onTapped: (point) => menu.popup()
    }
    SongMenu { id: menu; item: card.item }
}
