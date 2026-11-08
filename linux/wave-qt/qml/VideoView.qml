import QtQuick
import QtQuick.Controls
import wave
import wave.video

// MV（音を消した映像）。読み込み中・読めない（もう一度）・今の位置に映像が無いときは、その旨を出す。
// place: "side"（右の欄）・"full"（全画面の再生中）・"layer"（映像だけ）・"pip"（小窓）。右の欄と全画面の再生中には、
// 右下に「拡大」（右の欄は映像だけを窓いっぱいに、全画面の再生中も同じ）と「小窓」のボタン（GTK 版と同じ）。
Rectangle {
    id: view
    property string place: "side"
    color: "black"
    radius: place === "layer" || place === "pip" ? 0 : Style.radius
    clip: true
    VideoFrame { anchors.fill: parent; visible: WaveApp.videoState === "showing" }
    BusyIndicator { anchors.centerIn: parent; visible: WaveApp.videoState === "loading"; running: visible }
    Column {
        anchors.centerIn: parent
        width: parent.width - 32
        spacing: 8
        visible: WaveApp.videoState === "failed" || WaveApp.videoState === "gap"
        Label {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            text: WaveApp.videoState === "failed" ? "動画を読み込めませんでした" : "この位置には映像がありません"
            color: "white"
        }
        Btn {
            visible: WaveApp.videoState === "failed"
            anchors.horizontalCenter: parent.horizontalCenter
            text: "もう一度"
            onClicked: { WaveApp.setVideoOn(false); WaveApp.setVideoOn(true) }
        }
    }
    Row {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.margins: 8
        spacing: 6
        visible: (view.place === "side" || view.place === "full") && (hover.hovered || view.place === "full")
        Osd { iconName: "view-fullscreen-symbolic"; tip: view.place === "side" ? "映像を大きく見る" : "映像だけを窓いっぱいに"; onClicked: Style.nav.expandVideo() }
        Osd { iconName: Style.nav && Style.nav.pipOpen ? "view-conceal-symbolic" : "view-restore-symbolic"; tip: Style.nav && Style.nav.pipOpen ? "小窓を閉じる" : "小窓で見る"; onClicked: Style.nav.togglePip() }
    }
    HoverHandler { id: hover }

    // 映像の上に重ねる丸いボタン（暗い地に白い絵）
    component Osd: RoundButton {
        property string iconName
        property string tip
        implicitWidth: 36
        implicitHeight: 36
        icon.source: Style.icon(iconName)
        icon.color: "white"
        background: Rectangle { radius: width / 2; color: Qt.rgba(0, 0, 0, parent.hovered ? 0.75 : 0.55) }
        ToolTip.visible: hovered
        ToolTip.text: tip
    }
}
