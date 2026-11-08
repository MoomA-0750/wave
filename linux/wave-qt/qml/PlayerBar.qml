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
        // 狭い窓（720〜800 ほど）では、中央の操作と右のボタンの分を先に取り、残りまで縮める（曲名は省略される）
        width: Math.max(0, Math.min(bar.width * 0.3, 360, bar.width - 16 - right.implicitWidth - 16 - transport.implicitWidth - 2 * bar.gap))
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

    // 中央: 操作と位置。ふだんは帯の真ん中。狭い窓では左の曲と右のボタンの間に収まるよう寄せ、
    // 幅は操作の並び（シャッフル〜リピート）より狭くしない（狭くすると並びがはみ出し、右のボタンと重なる）
    readonly property int gap: 16
    readonly property real centerStart: left.visible ? left.x + left.width + gap : gap
    readonly property real centerEnd: right.x - gap
    ColumnLayout {
        id: center
        anchors.verticalCenter: parent.verticalCenter
        width: Math.max(transport.implicitWidth,
                        Math.min(bar.shell.level <= 1 ? 560 : 380, bar.width - 2 * Math.max(left.width, right.implicitWidth) - 48,
                                 bar.centerEnd - bar.centerStart))
        x: Math.max(bar.centerStart, Math.min((bar.width - width) / 2, bar.centerEnd - width))
        spacing: 2
        RowLayout {
            id: transport
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
                value: WaveApp.durationMs > 0 ? bar.position : 0
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
            IconButton {
                id: deviceButton
                iconName: "audio-speakers-symbolic"
                tip: "デバイスに接続"
                checked: WaveApp.remoteLabel !== ""
                onClicked: { devicePopup.d = JSON.parse(WaveApp.devicesJson()); devicePopup.open() }
                DevicePopup { id: devicePopup; y: -height - 8; x: -width / 2 }
            }
            Label { visible: WaveApp.remoteLabel !== "" && bar.shell.level === 0; text: WaveApp.remoteLabel; color: Style.accent; font.pixelSize: 12; font.weight: Font.Bold; elide: Text.ElideRight; Layout.maximumWidth: 120 }
        }
        IconButton { iconName: "sidebar-show-right-symbolic"; tip: "再生中の画面"; checked: bar.shell.showRight && bar.shell.rightView === "now"; onClicked: bar.shell.togglePane() }
        IconButton { iconName: "view-fullscreen-symbolic"; tip: "全画面 (F11)"; checked: bar.shell.full; onClicked: bar.shell.full = !bar.shell.full }
        IconButton {
            iconName: WaveApp.volume <= 0 ? "audio-volume-muted-symbolic" : WaveApp.volume < 0.34 ? "audio-volume-low-symbolic" : WaveApp.volume < 0.67 ? "audio-volume-medium-symbolic" : "audio-volume-high-symbolic"
            tip: "ミュート"
            onClicked: bar.shell.level <= 1 ? WaveApp.toggleMute() : volumePopup.open()
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
            visible: bar.shell.level <= 1
            implicitWidth: 110
            from: 0
            to: 1
            value: WaveApp.volume
            onMoved: WaveApp.setVolumeLevel(value)
        }
    }

    // 再生する端末を選ぶ小窓（GTK 版・Mac と同じ）: 今鳴らしている端末、ほかのデバイス、端末の連携の設定
    component DevicePopup: Popup {
        id: popup
        property var d: ({})
        width: 300
        padding: 10
        background: Rectangle { color: Style.raised; radius: Style.radius; border.color: Style.border }
        contentItem: ColumnLayout {
            spacing: 4
            Label { text: "再生中のデバイス"; font.weight: Font.Bold; color: Style.foreground }
            Label { text: popup.d.mirror ? popup.d.mirror.label : "この PC"; color: Style.accent; font.weight: Font.DemiBold }
            Label { visible: (popup.d.pickable || []).length > 0; text: "ほかのデバイスを選択"; font.weight: Font.Bold; Layout.topMargin: 8; color: Style.foreground }
            Repeater {
                model: popup.d.pickable || []
                delegate: ItemDelegate {
                    Layout.fillWidth: true
                    enabled: modelData.self || modelData.online
                    text: (modelData.self ? "この PC" : modelData.name) + (enabled ? "" : "（オフライン）")
                    icon.source: Style.icon(modelData.icon)
                    onClicked: {
                        popup.close()
                        if (modelData.self) WaveApp.connectAction("transfer_here", "")
                        else WaveApp.connectAction("play_on_device", modelData.id)
                    }
                }
            }
            Btn { text: "端末の連携の設定"; flat: true; Layout.topMargin: 6; onClicked: { popup.close(); bar.shell.full = false; bar.shell.openDevices() } }
        }
    }
}
