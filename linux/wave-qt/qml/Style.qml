pragma Singleton
import QtQuick
import wave

// 色・丸み・余白・行き先。Omarchy なら colors.toml の色（Theme）、無ければ Qt の明暗と kasane の控えの色（Kasane.qml。アイコンの色が種）。
// 色の対応は omarchy-gtk-tint と同じ（窓の地・一覧と横の欄・見出しの帯とカード・文字・境目の線）。
QtObject {
    readonly property bool omarchy: Theme.omarchy
    readonly property bool dark: omarchy ? Theme.dark : Qt.styleHints.colorScheme !== Qt.ColorScheme.Light
    // kasane の値（角丸・余白・控えの色）。kasane-sync で写したもの。手で直さない
    readonly property Kasane kasane: Kasane {}
    readonly property var k: kasane.colors(dark)
    readonly property color accent: omarchy ? Theme.accent : k.accent
    readonly property color accentText: omarchy ? Theme.accentText : k.onAccent
    readonly property color background: omarchy ? Theme.background : k.bg
    readonly property color view: omarchy ? Theme.view : k.surface
    readonly property color raised: omarchy ? Theme.raised : k.surface2
    readonly property color foreground: omarchy ? Theme.foreground : k.fg
    readonly property color border: omarchy ? Theme.border : k.lineStrong
    readonly property color red: omarchy ? Theme.red : k.danger
    readonly property color green: omarchy ? Theme.green : k.success
    readonly property color yellow: omarchy ? Theme.yellow : k.warn
    // 薄い文字（補足・時刻）
    readonly property color dim: Qt.rgba(foreground.r, foreground.g, foreground.b, 0.62)
    // 指を乗せた行の地
    readonly property color hover: Qt.rgba(foreground.r, foreground.g, foreground.b, 0.07)

    // 文字: 本体の UI の文字（GNOME の font-name。GTK 版と同じ）。読めなければ Qt の既定
    readonly property string fontFamily: Theme.fontFamily !== "" ? Theme.fontFamily : Qt.application.font.family
    readonly property real fontPoints: Theme.fontPoints

    // 画面の言語（日本語・英語）。文言は t("日本語", "English") で書く（Windows の L.T と同じ）
    readonly property bool ja: Theme.ja
    function t(jaText, enText) { return ja ? jaText : enText }
    // 曲数（英語は単数・複数を分ける）
    function songs(n) { return ja ? n + " 曲" : (n === 1 ? "1 song" : n + " songs") }

    // 角の丸み: Hyprland の decoration:rounding（Omarchy の既定は 0）。Hyprland でなければ kasane の base（10）
    readonly property int radius: Theme.rounding >= 0 ? Theme.rounding : kasane.radius.base
    // 小さい部品（チップ・行・小さな画像）は min(4, 丸み)。Hyprland でなければ kasane の sm（6）
    readonly property int small: Theme.rounding >= 0 ? Math.min(4, radius) : kasane.radius.sm
    // 余白の段（kasane。s4 = 16）
    readonly property var space: kasane.space

    // ページの下の余白（いちばん下の行・ボタンを下の端から離す）
    readonly property int pageBottom: 64

    // ページの行き先（Shell が入れる）。Style.nav.openAlbum(id, title) などで開く
    property var nav: null

    function icon(name) {
        return Theme.icon(name)
    }
}
