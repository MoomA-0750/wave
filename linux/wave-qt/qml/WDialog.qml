import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import wave

// ダイアログ。地は自分で描く（software の描き方でも出るように）。画面の真ん中に、後ろを暗くして出す。
Dialog {
    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    padding: 20
    background: Rectangle {
        radius: Style.radius
        color: Style.raised
        border.color: Style.border
    }
    Overlay.modal: Rectangle { color: Qt.rgba(0, 0, 0, 0.45) }
}
