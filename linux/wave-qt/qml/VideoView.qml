import QtQuick
import QtQuick.Controls
import wave
import wave.video

// MV（音を消した映像）。読み込み中・読めない・今の位置に映像が無いときは、その旨を出す。
Rectangle {
    id: view
    color: "black"
    radius: Style.radius
    clip: true
    VideoFrame { anchors.fill: parent; visible: WaveApp.videoState === "showing" }
    BusyIndicator { anchors.centerIn: parent; visible: WaveApp.videoState === "loading"; running: visible }
    Label {
        anchors.centerIn: parent
        width: parent.width - 32
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        visible: WaveApp.videoState === "failed" || WaveApp.videoState === "gap"
        text: WaveApp.videoState === "failed" ? "動画を読み込めませんでした" : "この位置には映像がありません"
        color: "white"
    }
}
