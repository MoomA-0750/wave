import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import wave

// メニュー。地は自分で描く（Material の Menu の地は影の効果で描かれ、software の描き方では出ないため）。
Menu {
    Material.roundedScale: Style.radius
    background: Rectangle {
        implicitWidth: 220
        implicitHeight: 40
        radius: Style.radius
        color: Style.raised
        border.color: Style.border
    }
}
