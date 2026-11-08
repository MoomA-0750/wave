import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// 下の帯（GTK 版・Mac と同じ）: 左に曲（ジャケットで右の欄の再生中を開け閉め・曲名でアルバム・アーティストでそのページ）と評価、
// 中央にシャッフル・前・再生・次・リピートと位置（動かし終えてから移る）、右に歌詞・キュー・端末・再生中の画面・全画面・ミュートと音量。
// 狭い窓では端末名、次に音量のバーをしまう。
Rectangle {
    id: bar
    property var shell
    property int position: 0
    property bool dragging: false
    implicitHeight: 96
    color: Style.background

    function time(ms) {
        const s = Math.floor(Math.max(0, ms) / 1000)
        const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, r = s % 60
        return (h > 0 ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(r).padStart(2, "0")
    }
    Timer { interval: 500; repeat: true; running: true; triggeredOnStart: true; onTriggered: if (!bar.dragging) bar.position = WaveApp.positionMs() }

    // 左: 曲
    RowLayout {
        id: left
        anchors.left: parent.left
        anchors.leftMargin: 16
        anchors.verticalCenter: parent.verticalCenter
        width: Math.min(bar.width * 0.3, 360)
        spacing: 12
        visible: WaveApp.hasSong
        Art {
            Layout.preferredWidth: 56
            Layout.preferredHeight: 56
            source: WaveApp.thumb
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: bar.shell.togglePane() }
        }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            Label {
                Layout.fillWidth: true
                text: WaveApp.title
                font.weight: Font.DemiBold
                elide: Text.ElideRight
                color: Style.foreground
                font.underline: songHover.hovered && WaveApp.albumId !== ""
                HoverHandler { id: songHover; cursorShape: WaveApp.albumId !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                TapHandler { enabled: WaveApp.albumId !== ""; onTapped: Style.nav.openAlbum(WaveApp.albumId, WaveApp.album) }
            }
            Label {
                Layout.fillWidth: true
                text: WaveApp.artists
                font.pixelSize: 12
                elide: Text.ElideRight
                color: Style.dim
                font.underline: byHover.hovered && WaveApp.artistId !== ""
                HoverHandler { id: byHover; cursorShape: WaveApp.artistId !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                TapHandler { enabled: WaveApp.artistId !== ""; onTapped: Style.nav.openArtist(WaveApp.artistId, WaveApp.artists) }
            }
        }
        IconButton {
            visible: WaveApp.loggedIn
            iconName: WaveApp.liked === 1 ? "object-select-symbolic" : "list-add-symbolic"
            tip: WaveApp.liked === 1 ? "高く評価した曲から削除" : "高く評価した曲に追加"
            checked: WaveApp.liked === 1
            onClicked: WaveApp.toggleLike()
        }
    }

    // 中央: 操作と位置
    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(560, bar.width - 2 * Math.max(left.width, right.implicitWidth) - 48)
        spacing: 2
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            spacing: 12
            IconButton { iconName: "media-playlist-shuffle-symbolic"; tip: "シャッフル"; checked: WaveApp.shuffle; enabled: WaveApp.hasSong; onClicked: WaveApp.toggleShuffle() }
            IconButton { iconName: "media-skip-backward-symbolic"; tip: "前へ"; enabled: WaveApp.hasSong; onClicked: WaveApp.previous() }
            PlayButton {
                enabled: WaveApp.hasSong
                iconName: WaveApp.playing ? "media-playback-pause-symbolic" : "media-playback-start-symbolic"
                tip: "再生/一時停止"
                onClicked: WaveApp.togglePlay()
            }
            IconButton { iconName: "media-skip-forward-symbolic"; tip: "次へ"; enabled: WaveApp.hasSong; onClicked: WaveApp.next() }
            IconButton {
                iconName: WaveApp.repeatMode === 2 ? "media-playlist-repeat-song-symbolic" : "media-playlist-repeat-symbolic"
                tip: "リピート"
                checked: WaveApp.repeatMode !== 0
                enabled: WaveApp.hasSong
                onClicked: WaveApp.cycleRepeat()
            }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            visible: !WaveApp.live
            Label { text: bar.time(seek.pressed ? seek.value : bar.position); font.pixelSize: 12; color: Style.dim; Layout.preferredWidth: 44; horizontalAlignment: Text.AlignRight; font.features: { "tnum": 1 } }
            Slider {
                id: seek
                Layout.fillWidth: true
                from: 0
                to: Math.max(1, WaveApp.durationMs)
                value: bar.position
                enabled: WaveApp.hasSong && WaveApp.durationMs > 0
                onPressedChanged: {
                    bar.dragging = pressed
                    // 動かし終えてから1度だけ移る
                    if (!pressed) { WaveApp.seek(value); bar.position = value }
                }
            }
            Label { text: bar.time(WaveApp.durationMs); font.pixelSize: 12; color: Style.dim; Layout.preferredWidth: 44; font.features: { "tnum": 1 } }
        }
        Label {
            visible: WaveApp.live
            Layout.alignment: Qt.AlignHCenter
            text: "LIVE"
            font.weight: Font.Bold
            font.pixelSize: 11
            color: "white"
            leftPadding: 8; rightPadding: 8; topPadding: 2; bottomPadding: 2
            background: Rectangle { color: Style.red; radius: Style.small }
        }
    }

    // 右: 歌詞・キュー・端末・再生中の画面・全画面・音量
    RowLayout {
        id: right
        anchors.right: parent.right
        anchors.rightMargin: 16
        anchors.verticalCenter: parent.verticalCenter
        spacing: 2
        IconButton { iconName: "text-x-generic-symbolic"; tip: "歌詞"; checked: bar.shell.visibleKind === "lyrics"; onClicked: bar.shell.toggleLyrics() }
        IconButton { iconName: "view-list-symbolic"; tip: "キュー"; checked: bar.shell.showRight && bar.shell.rightView === "queue"; onClicked: bar.shell.toggleQueue() }
        RowLayout {
            visible: WaveApp.connectConfigured
            spacing: 4
            IconButton { iconName: "audio-speakers-symbolic"; tip: "デバイスに接続"; checked: WaveApp.remoteLabel !== ""; onClicked: WaveApp.toast("端末の切り替えは GTK 版で行えます") }
            Label { visible: WaveApp.remoteLabel !== "" && bar.width >= 1400; text: WaveApp.remoteLabel; color: Style.accent; font.pixelSize: 12; font.weight: Font.Bold; elide: Text.ElideRight; Layout.maximumWidth: 120 }
        }
        IconButton { iconName: "sidebar-show-right-symbolic"; tip: "再生中の画面"; checked: bar.shell.showRight && bar.shell.rightView === "now"; onClicked: bar.shell.togglePane() }
        IconButton { iconName: "view-fullscreen-symbolic"; tip: "全画面 (F11)"; checked: bar.shell.full; onClicked: bar.shell.full = !bar.shell.full }
        IconButton {
            iconName: WaveApp.volume <= 0 ? "audio-volume-muted-symbolic" : WaveApp.volume < 0.34 ? "audio-volume-low-symbolic" : WaveApp.volume < 0.67 ? "audio-volume-medium-symbolic" : "audio-volume-high-symbolic"
            tip: "ミュート"
            onClicked: bar.width >= 1180 ? WaveApp.toggleMute() : volumePopup.open()
            Popup {
                id: volumePopup
                y: -height - 8
                x: -width / 2
                padding: 8
                background: Rectangle { color: Style.raised; radius: Style.radius; border.color: Style.border }
                RowLayout {
                    IconButton { iconName: "audio-volume-muted-symbolic"; tip: "ミュート"; onClicked: WaveApp.toggleMute() }
                    Slider { from: 0; to: 1; value: WaveApp.volume; implicitWidth: 120; onMoved: WaveApp.setVolumeLevel(value) }
                }
            }
        }
        Slider {
            visible: bar.width >= 1180
            implicitWidth: 110
            from: 0
            to: 1
            value: WaveApp.volume
            onMoved: WaveApp.setVolumeLevel(value)
        }
    }
}
