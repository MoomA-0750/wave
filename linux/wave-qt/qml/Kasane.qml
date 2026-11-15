// 書き出したもの。直すのは kasane の tokens.css と themes/personal.css（scripts/build.mjs）
// kasane 1.1.0  source b09fbea03c805e10  seed #2B6CE0 #2B6CE0
import QtQuick

// kasane の値。登録はアプリの仕組みのまま（singleton にするか、1つ作って渡す）。明暗はアプリの Theme.dark で選ぶ
// 角丸は控え: Omarchy（Hyprland の decoration:rounding）の丸さが分かるときは、アプリがそちらを使う
QtObject {
    component Radii: QtObject {
        readonly property real sm: 6
        readonly property real base: 10
        readonly property real lg: 16
        readonly property real min: 2
        readonly property real control: 10
        readonly property real field: 10
        readonly property real card: 26
        readonly property real row: 26
    }
    component Spaces: QtObject {
        readonly property real s1: 4
        readonly property real s2: 8
        readonly property real s3: 12
        readonly property real s4: 16
        readonly property real s5: 20
        readonly property real s6: 24
        readonly property real s8: 32
        readonly property real s10: 40
    }
    component Palette: QtObject {
        required property color bg
        required property color surface
        required property color surface2
        required property color raised
        required property color input
        required property color fg
        required property color muted
        required property color faint
        required property color line
        required property color lineStrong
        required property color hover
        required property color active
        required property color accent
        required property color accentStrong
        required property color accentSoft
        required property color onAccent
        required property color fill
        required property color onFill
        required property color fillHover
        required property color link
        required property color focus
        required property color danger
        required property color dangerSoft
        required property color dangerFill
        required property color dangerFillHover
        required property color onDanger
        required property color warn
        required property color warnSoft
        required property color success
        required property color successSoft
        required property color codeBg
        required property color codeFg
        required property color backdrop
        required property color scrim
        required property color onMedia
        required property color mediaControl
    }

    readonly property Radii radius: Radii {}
    readonly property Spaces space: Spaces {}
    // 控えの色（Omarchy のテーマが無いとき）。色は #AARRGGBB
    readonly property Palette light: Palette {
        bg: "#FFEFF5FF"
        surface: "#FFFCFEFF"
        surface2: "#FFE4EDFC"
        raised: "#FFFDFEFF"
        input: "#FFFDFEFF"
        fg: "#FF212C3D"
        muted: "#FF4C5B76"
        faint: "#FF7C8DAA"
        line: "#21223964"
        lineStrong: "#8F152C55"
        hover: "#0F223964"
        active: "#1A223964"
        accent: "#FF2A5FBE"
        accentStrong: "#FF154AA7"
        accentSoft: "#242A5FBE"
        onAccent: "#FFFFFFFF"
        fill: "#FF2A5FBE"
        onFill: "#FFFFFFFF"
        fillHover: "#FF2959AF"
        link: "#FF2A5FBE"
        focus: "#FF2A5FBE"
        danger: "#FFC52940"
        dangerSoft: "#1AC52940"
        dangerFill: "#FFC52940"
        dangerFillHover: "#FFB12940"
        onDanger: "#FFFFFFFF"
        warn: "#FFA14F09"
        warnSoft: "#1FA14F09"
        success: "#FF007473"
        successSoft: "#1F007473"
        codeBg: "#2470819D"
        codeFg: "#FFB6303C"
        backdrop: "#66040B19"
        scrim: "#99000000"
        onMedia: "#FFFFFFFF"
        mediaControl: "#26FFFFFF"
    }
    readonly property Palette dark: Palette {
        bg: "#FF13171F"
        surface: "#FF1B1F27"
        surface2: "#FF202731"
        raised: "#FF272C33"
        input: "#FF161B22"
        fg: "#FFE2E8F3"
        muted: "#FFA7B2C4"
        faint: "#FF717B8C"
        line: "#1AE0EFFF"
        lineStrong: "#5CE0EFFF"
        hover: "#0FE0EFFF"
        active: "#1AE0EFFF"
        accent: "#FF6B9DF5"
        accentStrong: "#FF80B4FF"
        accentSoft: "#246B9DF5"
        onAccent: "#FF0B1628"
        fill: "#FF6B9DF5"
        onFill: "#FF0B1628"
        fillHover: "#FF79A6F5"
        link: "#FF6B9DF5"
        focus: "#FF6B9DF5"
        danger: "#FFFF757C"
        dangerSoft: "#1AFF757C"
        dangerFill: "#FFFF757C"
        dangerFillHover: "#FFFD838B"
        onDanger: "#FF2B0B09"
        warn: "#FFFFA156"
        warnSoft: "#1FFFA156"
        success: "#FF25AEA7"
        successSoft: "#1F25AEA7"
        codeBg: "#3370819D"
        codeFg: "#FFFF757C"
        backdrop: "#99000000"
        scrim: "#99000000"
        onMedia: "#FFFFFFFF"
        mediaControl: "#26FFFFFF"
    }

    function colors(isDark: bool): Palette { return isDark ? dark : light }
    // 同心の角丸: 中の角丸 = max(radius-min, 外 − 余白)。外がピルなら使わない
    function innerRadius(outer: real, padding: real): real { return Math.max(radius.min, outer - padding) }
    // Hyprland の丸さに従うとき: 下限もアプリが渡す（Omarchy の既定の角張った 0 なら 0 まで）
    function innerRadiusWith(outer: real, padding: real, minimum: real): real { return Math.max(minimum, outer - padding) }
}
