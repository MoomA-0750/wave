import QtQuick
import QtQuick.Effects
import QtQuick.Controls.impl
import wave

// ジャケット・写真。読めるまでは地の色。round なら丸、そうでなければ丸み radius で切り抜く。
Item {
    id: art
    property string source: ""
    property bool round: false
    property int radius: Style.small
    property string placeholder: "folder-music-symbolic"
    readonly property int clipRadius: round ? Math.min(width, height) / 2 : radius

    Rectangle {
        id: base
        anchors.fill: parent
        radius: art.clipRadius
        color: Style.raised
        IconImage {
            anchors.centerIn: parent
            visible: image.status !== Image.Ready && art.placeholder !== ""
            source: art.placeholder !== "" ? Style.icon(art.placeholder) : ""
            readonly property int side: Math.max(16, Math.round(Math.min(art.width, art.height) * 0.4))
            sourceSize.width: side
            sourceSize.height: side
            color: Style.dim
        }
    }
    Image {
        id: image
        anchors.fill: parent
        source: art.source
        fillMode: Image.PreserveAspectCrop
        asynchronous: true
        cache: true
        visible: art.clipRadius === 0 && status === Image.Ready
        sourceSize.width: Math.ceil(width * 2)
        sourceSize.height: Math.ceil(height * 2)
    }
    // 丸める: 画像を地の形で切り抜く（丸みが 0 なら切り抜かない）
    MultiEffect {
        anchors.fill: parent
        visible: art.clipRadius > 0 && image.status === Image.Ready
        source: image
        maskEnabled: true
        maskSource: mask
        maskThresholdMin: 0.5
        maskSpreadAtMin: 1.0
    }
    Item {
        id: mask
        anchors.fill: parent
        layer.enabled: true
        visible: false
        Rectangle { anchors.fill: parent; radius: art.clipRadius; color: "black" }
    }
}
