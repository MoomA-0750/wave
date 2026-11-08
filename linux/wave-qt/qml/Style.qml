pragma Singleton
import QtQuick
import wave

// 色・丸み・行き先。Omarchy なら colors.toml の色（Theme）、無ければ Qt の明暗と Material の既定の色。
// 色の対応は omarchy-gtk-tint と同じ（窓の地・一覧と横の欄・見出しの帯とカード・文字・境目の線）。
QtObject {
    readonly property bool omarchy: Theme.omarchy
    readonly property bool dark: omarchy ? Theme.dark : Qt.styleHints.colorScheme !== Qt.ColorScheme.Light
    readonly property color accent: omarchy ? Theme.accent : (dark ? "#d0bcff" : "#6750a4")
    readonly property color accentText: omarchy ? Theme.accentText : (dark ? "#381e72" : "#ffffff")
    readonly property color background: omarchy ? Theme.background : (dark ? "#1c1b1f" : "#fffbfe")
    readonly property color view: omarchy ? Theme.view : (dark ? "#211f26" : "#f7f2fa")
    readonly property color raised: omarchy ? Theme.raised : (dark ? "#2b2930" : "#ece6f0")
    readonly property color foreground: omarchy ? Theme.foreground : (dark ? "#e6e1e5" : "#1c1b1f")
    readonly property color border: omarchy ? Theme.border : (dark ? "#49454f" : "#cac4d0")
    readonly property color red: omarchy ? Theme.red : "#b3261e"
    readonly property color green: omarchy ? Theme.green : "#2e7d32"
    readonly property color yellow: omarchy ? Theme.yellow : "#f9a825"
    // 薄い文字（補足・時刻）
    readonly property color dim: Qt.rgba(foreground.r, foreground.g, foreground.b, 0.62)
    // 指を乗せた行の地
    readonly property color hover: Qt.rgba(foreground.r, foreground.g, foreground.b, 0.07)

    // 角の丸み: Hyprland の decoration:rounding（Omarchy の既定は 0）。Hyprland でなければ 12
    readonly property int radius: Theme.rounding >= 0 ? Theme.rounding : 12
    // 小さい部品（チップ・行・小さな画像）は min(4, 丸み)
    readonly property int small: Math.min(4, radius)

    // ページの行き先（Shell が入れる）。Style.nav.openAlbum(id, title) などで開く
    property var nav: null

    function icon(name) {
        return Theme.icon(name)
    }
}
