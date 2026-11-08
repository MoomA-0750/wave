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
    RowLayout {
        anchors.centerIn: parent
        width: Math.min(parent.width - 96, 1100)
        spacing: 48
        Art {
            Layout.preferredWidth: Math.min(view.height - 120, view.width * 0.45)
            Layout.preferredHeight: Layout.preferredWidth
            source: WaveApp.art
            radius: Style.radius
        }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 8
            Label { Layout.fillWidth: true; text: WaveApp.hasSong ? WaveApp.title : "何も再生していません"; font.pixelSize: 40; font.weight: Font.Bold; wrapMode: Text.Wrap; maximumLineCount: 3; elide: Text.ElideRight; color: Style.foreground }
            Label { Layout.fillWidth: true; text: WaveApp.artists; font.pixelSize: 22; elide: Text.ElideRight; color: Style.dim }
            Label { Layout.fillWidth: true; text: WaveApp.album; visible: text !== ""; font.pixelSize: 16; elide: Text.ElideRight; color: Style.dim }
        }
    }
}
