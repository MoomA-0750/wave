import QtQuick
import QtQuick.Controls
import QtQuick.Window
import wave

// 映像の小窓（GTK 版の mini_window と同じ）: 400×225 の別の窓に映像だけを出す。指を乗せると前・再生・次と「wave に戻して大きく見る」、
// ダブルクリックでも戻す。閉じたら shell の pipOpen を下ろす。
Window {
    id: pip
    property var shell
    width: 400
    height: 225
    minimumWidth: 240
    minimumHeight: 135
    title: "wave の小窓"
    color: "black"
    flags: Qt.Window | Qt.WindowStaysOnTopHint
    onClosing: shell.pipClosed()

    VideoView { id: video; anchors.fill: parent; place: "pip" }
    HoverHandler { id: hover }
    TapHandler { onDoubleTapped: pip.shell.pipBackToApp() }
    Row {
        anchors.centerIn: parent
        spacing: 18
        visible: hover.hovered || WaveApp.demo === "pip"
        Osd { iconName: "media-skip-backward-symbolic"; tip: "前へ"; onClicked: WaveApp.previous() }
        Osd { iconName: WaveApp.playing ? "media-playback-pause-symbolic" : "media-playback-start-symbolic"; tip: WaveApp.playing ? "一時停止" : "再生"; onClicked: WaveApp.togglePlay() }
        Osd { iconName: "media-skip-forward-symbolic"; tip: "次へ"; onClicked: WaveApp.next() }
    }
    Osd {
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 6
        visible: hover.hovered || WaveApp.demo === "pip"
        iconName: "view-fullscreen-symbolic"
        tip: "wave に戻して大きく見る"
        onClicked: pip.shell.pipBackToApp()
    }

    component Osd: RoundButton {
        property string iconName
        property string tip
        implicitWidth: 40
        implicitHeight: 40
        icon.source: Style.icon(iconName)
        icon.color: "white"
        background: Rectangle { radius: width / 2; color: Qt.rgba(0, 0, 0, parent.hovered ? 0.75 : 0.55) }
        ToolTip.visible: hovered
        ToolTip.text: tip
    }
}
