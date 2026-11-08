import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import wave

// 横に流れる棚（見出しと、続きがある向きだけの左右の送りボタン）。
ColumnLayout {
    id: shelf
    property string title: ""
    property string strapline: ""
    property var items: []
    property int cardSize: 168
    spacing: 10

    RowLayout {
        Layout.fillWidth: true
        ColumnLayout {
            spacing: 0
            Layout.fillWidth: true
            Label { text: shelf.strapline.toUpperCase(); visible: shelf.strapline !== ""; font.pixelSize: 11; color: Style.dim }
            Section { text: shelf.title; Layout.fillWidth: true }
        }
        IconButton { iconName: "go-previous-symbolic"; tip: "前へ"; opacity: list.atXBeginning ? 0 : 1; enabled: !list.atXBeginning; onClicked: list.flick(2400, 0) }
        IconButton { iconName: "go-next-symbolic"; tip: "次へ"; opacity: list.atXEnd ? 0 : 1; enabled: !list.atXEnd; onClicked: list.flick(-2400, 0) }
    }
    ListView {
        id: list
        Layout.fillWidth: true
        Layout.preferredHeight: shelf.cardSize + 86
        orientation: ListView.Horizontal
        spacing: 16
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        model: shelf.items
        delegate: Card { item: modelData; size: shelf.cardSize }
    }
}
