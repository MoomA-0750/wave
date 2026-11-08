import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import wave

// 記号の絵だけのボタン（GTK 版と同じ Adwaita の絵）。checked の間はアクセント色。
ToolButton {
    id: control
    property string iconName: ""
    property string tip: ""
    property int size: 20
    icon.source: Style.icon(iconName)
    icon.width: size
    icon.height: size
    icon.color: checked ? Style.accent : (enabled ? Style.foreground : Style.dim)
    display: AbstractButton.IconOnly
    padding: 6
    Material.roundedScale: Style.small
    ToolTip.visible: hovered && tip !== ""
    ToolTip.text: tip
    ToolTip.delay: 600
}
