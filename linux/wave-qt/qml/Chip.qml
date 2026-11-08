import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import wave

// 絞り込みのチップ（選んでいる間はアクセント色）。
Button {
    id: chip
    property bool on: false
    flat: !on
    highlighted: on
    checkable: false
    topInset: 0
    bottomInset: 0
    implicitHeight: 32
    leftPadding: 14
    rightPadding: 14
    font.pixelSize: 13
    font.weight: Font.DemiBold
    Material.roundedScale: Style.small
    Material.elevation: 0
    background: Rectangle {
        radius: Style.small
        color: chip.on ? Style.accent : (chip.hovered ? Qt.darker(Style.raised, Style.dark ? 0.85 : 1.06) : Style.raised)
    }
    contentItem: Label {
        text: chip.text
        font: chip.font
        color: chip.on ? Style.accentText : Style.foreground
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
    }
}
