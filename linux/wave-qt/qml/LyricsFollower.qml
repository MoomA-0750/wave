import QtQuick
import QtQuick.Controls
import wave

// 流れる歌詞（GTK 版の lyrics_follower と同じ）: 右の欄の再生中の歌詞の箱（小）と、全画面の再生中（大）で使う。
// 歌っている行を明るくして寄せる。大きい方は行を押すとその位置へ。歌詞が無ければ found が false。
ListView {
    id: follower
    property bool big: false
    property var info: ({ state: "loading" })
    property bool found: info.state === "synced" || info.state === "plain"
    property int current: -1
    property string shownFor: ""
    property int token: -1

    function reload() {
        shownFor = WaveApp.videoId
        current = -1
        info = { state: "loading" }
        if (WaveApp.videoId !== "") token = WaveApp.request("lyrics", "", "")
    }
    Component.onCompleted: reload()
    Connections {
        target: WaveApp
        function onVideoIdChanged() { if (WaveApp.videoId !== follower.shownFor) follower.reload() }
        function onLoaded(t, json) { if (t === follower.token) follower.info = JSON.parse(json) }
    }
    Timer {
        interval: 250
        repeat: true
        running: follower.visible && follower.info.state === "synced"
        onTriggered: {
            const ms = WaveApp.positionMs()
            const lines = follower.info.lines
            let at = -1
            for (let i = 0; i < lines.length; i++) { if (lines[i].ms <= ms + 150) at = i; else break }
            while (at > 0 && lines[at].sub) at--
            if (at !== follower.current) {
                follower.current = at
                if (at >= 0) follower.positionViewAtIndex(at, follower.big ? ListView.Center : ListView.Beginning)
            }
        }
    }
    clip: true
    interactive: big
    spacing: big ? 14 : 6
    bottomMargin: big ? height * 0.5 : 120
    model: info.state === "synced" ? info.lines : info.state === "plain" ? [{ text: info.text, ms: -1, sub: false }] : []
    delegate: Label {
        width: ListView.view.width
        text: modelData.text
        wrapMode: Text.Wrap
        font.pixelSize: follower.big ? (modelData.sub ? 18 : 26) : (modelData.sub ? 13 : 16)
        font.weight: Font.Bold
        color: Style.foreground
        opacity: modelData.ms < 0 ? 0.9 : index === follower.current || (modelData.sub && index > follower.current && follower.current >= 0 && index - follower.current <= 2) ? 1 : (index < follower.current ? 0.55 : 0.35)
        Behavior on opacity { NumberAnimation { duration: 200 } }
        HoverHandler { enabled: follower.big && modelData.ms >= 0; cursorShape: Qt.PointingHandCursor }
        TapHandler { enabled: follower.big && modelData.ms >= 0; onTapped: WaveApp.seek(modelData.ms) }
    }
}
