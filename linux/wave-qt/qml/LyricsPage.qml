import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Effects
import QtQuick.Layouts
import wave

// 歌詞のページ（中央のページ。GTK 版・Mac と同じ）。時刻付きなら歌っている行を明るくして真ん中より少し上へ寄せ、
// 行を押すとその位置へ。背景はジャケットをぼかして暗くしたもの。曲が変わったら読み直す。
Item {
    id: page
    property string pageKind: "lyrics"
    property string pageTitle: "歌詞"
    property var info: ({ state: "loading" })
    property int token: -1
    property int current: -1
    property string shownFor: ""

    function reload() {
        shownFor = WaveApp.videoId
        info = { state: "loading", art: info.art || "" }
        current = -1
        token = WaveApp.request("lyrics", "", "")
    }
    Component.onCompleted: reload()
    Connections {
        target: WaveApp
        function onVideoIdChanged() { if (WaveApp.videoId !== page.shownFor) page.reload() }
        function onLoaded(t, json) { if (t === page.token) page.info = JSON.parse(json) }
    }
    Timer {
        interval: 200
        repeat: true
        running: page.visible && page.info.state === "synced"
        onTriggered: {
            const ms = WaveApp.positionMs()
            const lines = page.info.lines
            let at = -1
            for (let i = 0; i < lines.length; i++) { if (lines[i].ms <= ms + 150) at = i; else break }
            // 添える行（読み・訳）は前の行と一緒に明るくする
            while (at > 0 && lines[at].sub) at--
            if (at !== page.current) {
                page.current = at
                if (at >= 0) list.positionViewAtIndex(at, ListView.Center)
            }
        }
    }

    // 背景: ジャケットをぼかして暗くしたもの
    Image { id: backdrop; anchors.fill: parent; source: page.info.art || ""; fillMode: Image.PreserveAspectCrop; visible: false; sourceSize.width: 256; sourceSize.height: 256 }
    MultiEffect { anchors.fill: parent; source: backdrop; blurEnabled: true; blur: 1.0; blurMax: 64; brightness: Style.dark ? -0.45 : 0.35; saturation: 0.2; visible: backdrop.status === Image.Ready }
    Rectangle { anchors.fill: parent; color: Style.view; opacity: backdrop.status === Image.Ready ? 0.35 : 1 }

    ListView {
        id: list
        anchors.fill: parent
        anchors.leftMargin: 48
        anchors.rightMargin: 48
        anchors.bottomMargin: 28
        visible: page.info.state === "synced"
        clip: true
        model: page.info.state === "synced" ? page.info.lines : []
        topMargin: height * 0.3
        bottomMargin: height * 0.5
        highlightMoveDuration: 300
        delegate: Label {
            width: ListView.view.width
            topPadding: modelData.sub ? 2 : 22
            text: modelData.text
            wrapMode: Text.Wrap
            font.pixelSize: modelData.sub ? 20 : 30
            font.weight: Font.Bold
            color: Style.foreground
            opacity: index === page.current || (modelData.sub && index > page.current && page.current >= 0 && index - page.current <= 2) ? 1 : (index < page.current ? 0.55 : 0.32)
            Behavior on opacity { NumberAnimation { duration: 200 } }
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: WaveApp.seek(modelData.ms) }
        }
    }
    Flickable {
        anchors.fill: parent
        anchors.margins: 48
        visible: page.info.state === "plain"
        contentHeight: plain.implicitHeight
        clip: true
        Label { id: plain; width: parent.width; text: page.info.text || ""; wrapMode: Text.Wrap; font.pixelSize: 22; lineHeight: 1.3; color: Style.foreground }
    }
    ColumnLayout {
        anchors.centerIn: parent
        visible: page.info.state !== "synced" && page.info.state !== "plain"
        spacing: 12
        BusyIndicator { visible: page.info.state === "loading"; running: visible; Layout.alignment: Qt.AlignHCenter }
        Label {
            Layout.alignment: Qt.AlignHCenter
            text: page.info.state === "loading" ? "歌詞を探しています…" : (page.info.message || "曲を再生すると、ここに歌詞が出ます")
            font.pixelSize: 18
            color: Style.foreground
        }
        Button { Material.roundedScale: Style.radius; visible: !!page.info.retry; text: "もう一度読み込む"; Layout.alignment: Qt.AlignHCenter; onClicked: page.reload() }
    }
    Label {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.margins: 12
        text: page.info.source || ""
        font.pixelSize: 11
        color: Style.dim
    }
}
