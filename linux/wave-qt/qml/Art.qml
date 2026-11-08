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
    // 丸める: 画像を地の形で切り抜く（丸みが 0 なら切り抜かない）。GPU で描くときは MultiEffect の型抜き、
    // software で描くとき（効果が使えない）は Canvas で角を丸めた形に切って描く
    readonly property bool software: GraphicsInfo.api === GraphicsInfo.Software
    MultiEffect {
        anchors.fill: parent
        visible: !art.software && art.clipRadius > 0 && image.status === Image.Ready
        source: image
        maskEnabled: true
        maskSource: mask
        maskThresholdMin: 0.5
        maskSpreadAtMin: 1.0
    }
    Item {
        id: mask
        anchors.fill: parent
        layer.enabled: !art.software
        visible: false
        Rectangle { anchors.fill: parent; radius: art.clipRadius; color: "black" }
    }
    Canvas {
        id: canvas
        anchors.fill: parent
        visible: art.software && art.clipRadius > 0 && image.status === Image.Ready
        property string loadedUrl: ""
        function drawWhenReady() {
            if (!visible) return
            if (loadedUrl !== art.source) {
                if (loadedUrl !== "") unloadImage(loadedUrl)
                loadedUrl = art.source
                loadImage(art.source)
            }
            requestPaint()
        }
        onVisibleChanged: drawWhenReady()
        onWidthChanged: requestPaint()
        onImageLoaded: requestPaint()
        Connections { target: art; function onSourceChanged() { canvas.drawWhenReady() } }
        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            if (!isImageLoaded(loadedUrl)) return
            const r = art.clipRadius, w = width, h = height
            ctx.beginPath()
            ctx.roundedRect(0, 0, w, h, r, r)
            ctx.clip()
            // 縦横の比を保って枠いっぱいに（はみ出す方を切る）
            const img = ctx.createImageData(loadedUrl)
            const iw = img ? img.width : w, ih = img ? img.height : h
            const scale = Math.max(w / iw, h / ih)
            const dw = iw * scale, dh = ih * scale
            ctx.drawImage(loadedUrl, (w - dw) / 2, (h - dh) / 2, dw, dh)
        }
    }
}
