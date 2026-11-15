import QtQuick
import wave

// 横に送れるチップの帯。入りきらないときは、続きのある側の端を地の色へ薄く消す（文字の途中で切れて見えないように）。
// 中身は default の Row に並べる。fadeColor は帯の下の地の色。
Flickable {
    id: strip
    default property alias chips: row.data
    property alias spacing: row.spacing
    property color fadeColor: Style.view
    implicitHeight: 34
    contentWidth: row.implicitWidth
    contentHeight: height
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    flickableDirection: Flickable.HorizontalFlick

    Row { id: row; spacing: Style.space.s2; height: strip.height }

    // 縦の回しで横に送る（指の回しで送れないと、右端のチップへ届かない）
    WheelHandler {
        acceptedDevices: PointerDevice.Mouse
        orientation: Qt.Vertical
        onWheel: (e) => {
            if (strip.contentWidth <= strip.width) { e.accepted = false; return }
            strip.contentX = Math.max(0, Math.min(strip.contentWidth - strip.width, strip.contentX - e.angleDelta.y))
        }
    }

    Rectangle {
        parent: strip
        anchors { left: parent.left; top: parent.top; bottom: parent.bottom }
        width: 32
        visible: strip.contentX > 1
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0; color: strip.fadeColor }
            GradientStop { position: 1; color: Qt.rgba(strip.fadeColor.r, strip.fadeColor.g, strip.fadeColor.b, 0) }
        }
    }
    Rectangle {
        parent: strip
        anchors { right: parent.right; top: parent.top; bottom: parent.bottom }
        width: 40
        visible: strip.contentX + strip.width < strip.contentWidth - 1
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0; color: Qt.rgba(strip.fadeColor.r, strip.fadeColor.g, strip.fadeColor.b, 0) }
            GradientStop { position: 1; color: strip.fadeColor }
        }
    }
}
