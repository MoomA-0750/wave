import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import wave

// 記号の絵だけのボタン（GTK 版と同じ Adwaita の絵）。checked の間はアクセント色。
ToolButton {
    id: control
    property string iconName: ""
    property string tip: ""
    property int size: compact ? 18 : 20
    // 小さい型（下の帯など、並べて使う所）: Material の既定の 48px 四方の場所を取らない
    property bool compact: false
    icon.source: Style.icon(iconName)
    icon.width: size
    icon.height: size
    icon.color: checked ? Style.accent : (enabled ? Style.foreground : Style.dim)
    display: AbstractButton.IconOnly
    padding: 6
    topInset: compact ? 0 : 6
    bottomInset: compact ? 0 : 6
    implicitWidth: compact ? 36 : Math.max(implicitBackgroundWidth + leftInset + rightInset, implicitContentWidth + leftPadding + rightPadding)
    implicitHeight: compact ? 36 : Math.max(implicitBackgroundHeight + topInset + bottomInset, implicitContentHeight + topPadding + bottomPadding)
    Material.roundedScale: Style.small
    ToolTip.visible: hovered && tip !== ""
    ToolTip.text: tip
    ToolTip.delay: 600
}
