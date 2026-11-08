import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import wave

// 文字のボタン。地は自分で描く（Material の Button の地は影の効果で描かれ、software の描き方では出ないため）。
// highlighted はアクセント色、flat は地なし、ほかは見出しの帯・カードの色。角は Hyprland の丸み。
Button {
    id: button
    // 文字のリンクのような平らなボタン（アクセント色の文字。GTK 版の text-link）
    property bool link: false
    topInset: 4
    bottomInset: 4
    leftPadding: flat ? 12 : 18
    rightPadding: flat ? 12 : 18
    Material.roundedScale: Style.radius
    Material.elevation: 0
    background: Rectangle {
        implicitWidth: 64
        implicitHeight: 40
        radius: Style.radius
        color: !button.enabled ? (button.flat ? "transparent" : Style.raised)
             : button.highlighted ? (button.hovered ? Qt.lighter(Style.accent, 1.12) : Style.accent)
             : button.flat ? (button.hovered ? Style.hover : "transparent")
             : (button.hovered ? Qt.darker(Style.raised, Style.dark ? 0.82 : 1.06) : Style.raised)
        border.color: button.highlighted || button.flat ? "transparent" : Style.border
        opacity: button.enabled ? 1 : 0.6
    }
    contentItem: Label {
        text: button.text
        font: button.font
        color: !button.enabled ? Style.dim : button.highlighted ? Style.accentText : button.link ? Style.accent : Style.foreground
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
}
