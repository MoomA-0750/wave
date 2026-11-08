import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Controls.impl
import wave

// 丸い再生のボタン（アクセント色。丸い部品なので Hyprland の丸みにかかわらず丸のまま）。
RoundButton {
    id: button
    property string iconName: "media-playback-start-symbolic"
    property string tip: "再生"
    highlighted: true
    implicitWidth: 52
    implicitHeight: 52
    Material.elevation: 0
    contentItem: Item {
        IconImage {
            anchors.centerIn: parent
            source: Style.icon(button.iconName)
            sourceSize.width: 24
            sourceSize.height: 24
            color: button.enabled ? Style.accentText : Style.dim
        }
    }
    background: Rectangle {
        radius: width / 2
        color: !button.enabled ? Style.raised : button.hovered ? Qt.lighter(Style.accent, 1.12) : Style.accent
    }
    ToolTip.visible: hovered && tip !== ""
    ToolTip.text: tip
    ToolTip.delay: 600
}
