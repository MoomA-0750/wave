import QtQuick
import QtQuick.Controls
import QtQuick.Effects
import QtQuick.Layouts
import wave

// 全画面の再生中の画面（F11・下の帯の全画面のボタン。Esc で戻る）: ぼかしたジャケットの上に、大きなジャケットと曲名・アーティスト。
Rectangle {
    id: view
    property var shell
    color: Style.background

    Image { id: backdrop; anchors.fill: parent; source: WaveApp.art; fillMode: Image.PreserveAspectCrop; visible: false; sourceSize.width: 256; sourceSize.height: 256 }
    MultiEffect { anchors.fill: parent; source: backdrop; blurEnabled: true; blur: 1.0; blurMax: 64; brightness: Style.dark ? -0.5 : 0.3; saturation: 0.2; visible: backdrop.status === Image.Ready }

    IconButton {
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 16
        iconName: "view-restore-symbolic"
        tip: "全画面を終える (Esc)"
        onClicked: view.shell.full = false
    }
    // 左にジャケット（か映像）、右に曲名・アーティスト・アルバムと流れる歌詞（GTK 版の全画面の再生中と同じ）。全体を窓の真ん中に置く
    readonly property real side: Math.min(view.height - 160, view.width * 0.42)
    Item {
        id: media
        anchors.verticalCenter: parent.verticalCenter
        x: Math.max(48, (view.width - width - 48 - info.width) / 2)
        width: WaveApp.showVideo ? Math.min(view.width * 0.55, (view.height - 160) * 16 / 9) : view.side
        height: WaveApp.showVideo ? width * 9 / 16 : width
        Art { anchors.fill: parent; visible: !WaveApp.showVideo; source: WaveApp.art; radius: Style.radius }
        VideoView { anchors.fill: parent; visible: WaveApp.showVideo; place: "full" }
    }
    ColumnLayout {
        id: info
        anchors.left: media.right
        anchors.leftMargin: 48
        anchors.verticalCenter: parent.verticalCenter
        width: Math.min(520, view.width - media.width - 144)
        height: Math.min(view.height - 120, Math.max(media.height, 360))
        spacing: 8
        Label { Layout.fillWidth: true; text: WaveApp.hasSong ? WaveApp.title : "何も再生していません"; font.pixelSize: 36; font.weight: Font.Bold; wrapMode: Text.Wrap; maximumLineCount: 2; elide: Text.ElideRight; color: Style.foreground }
        Label { Layout.fillWidth: true; text: WaveApp.artists; visible: text !== ""; font.pixelSize: 20; elide: Text.ElideRight; color: Style.dim }
        Label { Layout.fillWidth: true; text: WaveApp.album; visible: text !== ""; font.pixelSize: 15; elide: Text.ElideRight; color: Style.dim }
        Row {
            visible: WaveApp.hasVideo
            Layout.topMargin: 8
            spacing: 6
            Chip { text: "曲"; on: !WaveApp.showVideo; onClicked: WaveApp.setVideoOn(false) }
            Chip { text: "動画"; on: WaveApp.showVideo; onClicked: WaveApp.setVideoOn(true) }
        }
        LyricsFollower {
            id: follower
            big: true
            visible: found && view.visible
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.topMargin: 16
        }
        Item { visible: !follower.visible; Layout.fillHeight: true }
    }
}
