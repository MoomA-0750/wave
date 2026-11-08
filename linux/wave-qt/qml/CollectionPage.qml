import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// アルバム・プレイリスト（高く評価した曲も）・再生履歴のページ: 上にジャケットと名前・補足・再生とシャッフル、下に曲の一覧。
// プレイリストは下の端で続きを読む。
ListView {
    id: page
    property string kind: "album"
    property string itemId: ""
    property string pageTitle: ""
    property string pageKind: kind
    property var info: ({})
    property var songs: []
    property string continuation: ""
    property bool loading: true
    property string error: ""
    property int token: -1

    clip: true
    boundsBehavior: Flickable.StopAtBounds
    // 見出しが後から伸びても、頭から見せる（今の行に寄せない）
    currentIndex: -1
    ScrollBar.vertical: ScrollBar {}
    model: songs
    spacing: 0
    bottomMargin: 32

    Component.onCompleted: token = kind === "history" ? WaveApp.request("history", "", "") : WaveApp.request(kind, itemId, "")
    onAtYEndChanged: if (atYEnd && continuation !== "" && !loading) { loading = true; token = WaveApp.request("playlist", itemId, continuation) }

    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== page.token) return
            page.loading = false
            const v = JSON.parse(json)
            if (v.error) { page.error = page.kind === "album" ? "アルバムを読み込めませんでした" : "読み込めませんでした"; return }
            if (page.kind === "history") {
                page.info = { title: "再生履歴", subtitle: "", art: "" }
                page.songs = v.sections.reduce((all, s) => all.concat(s.songs), [])
                return
            }
            if (page.songs.length === 0) page.info = v
            page.songs = page.songs.concat(v.songs)
            page.continuation = v.continuation || ""
        }
    }

    header: ColumnLayout {
        width: page.width
        spacing: 16
        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 24
            spacing: 24
            Art {
                visible: page.kind !== "history"
                Layout.preferredWidth: 200
                Layout.preferredHeight: 200
                source: page.info.art || ""
                radius: Style.radius
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 6
                Label { text: page.kind === "album" ? "アルバム" : page.kind === "history" ? "" : "プレイリスト"; visible: text !== ""; font.pixelSize: 12; font.weight: Font.DemiBold; color: Style.dim }
                Label {
                    Layout.fillWidth: true
                    text: page.info.title || page.pageTitle
                    font.pixelSize: 32
                    font.weight: Font.Bold
                    wrapMode: Text.Wrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                    color: Style.foreground
                }
                // アーティスト（押すとそのページ）と補足
                Flow {
                    Layout.fillWidth: true
                    spacing: 6
                    Repeater {
                        model: page.info.artists || []
                        delegate: Label {
                            text: modelData.name
                            lineHeightMode: Text.FixedHeight
                            lineHeight: 20
                            font.weight: Font.DemiBold
                            color: modelData.id ? Style.foreground : Style.dim
                            font.underline: artistHover.hovered && modelData.id !== ""
                            HoverHandler { id: artistHover; cursorShape: modelData.id ? Qt.PointingHandCursor : Qt.ArrowCursor }
                            TapHandler { enabled: modelData.id !== ""; onTapped: Style.nav.openArtist(modelData.id, modelData.name) }
                        }
                    }
                    Label { text: page.info.subtitle || ""; color: Style.dim; wrapMode: Text.Wrap; lineHeightMode: Text.FixedHeight; lineHeight: 20 }
                }
                RowLayout {
                    Layout.topMargin: 10
                    spacing: 8
                    visible: page.songs.length > 0
                    PlayButton {
                        onClicked: page.playFrom(0, false)
                    }
                    IconButton { iconName: "media-playlist-shuffle-symbolic"; tip: "シャッフル再生"; size: 22; onClicked: page.playFrom(0, true) }
                    IconButton {
                        visible: page.kind !== "history"
                        iconName: "folder-download-symbolic"
                        tip: "ダウンロード"
                        size: 22
                        onClicked: WaveApp.downloadCollection(page.kind === "album" ? "album" : page.itemId === "LM" ? "liked" : "playlist", page.itemId, page.info.title || page.pageTitle, page.info.art || "", JSON.stringify(page.songs))
                    }
                }
            }
        }
        Label {
            visible: page.error !== ""
            text: page.error
            Layout.leftMargin: 24
            color: Style.dim
        }
        BusyIndicator { visible: page.loading && page.songs.length === 0; running: visible; Layout.alignment: Qt.AlignHCenter }
    }

    function playFrom(index, shuffle) {
        const source = page.kind === "album" ? page.itemId : page.kind === "history" ? "" : page.itemId
        WaveApp.playSongs(JSON.stringify(page.songs), index, source, shuffle)
    }

    delegate: TrackRow {
        width: page.width - 32
        x: 16
        song: modelData
        number: page.kind === "album" ? index + 1 : 0
        showArt: page.kind !== "album"
        showAlbum: page.kind !== "album"
        subtitleText: page.kind === "album" ? (modelData.artists || "") : (showAlbum ? (modelData.artists || "") : (modelData.subtitle || ""))
        onPlay: page.playFrom(index, false)
        // 自分のプレイリストからは外せる
        removal: page.kind === "playlist" && page.info.editable && (modelData.setVideoId || "") !== ""
                 ? { label: "このプレイリストから削除", action: () => page.removeSong(index) } : null
    }
    property int removeToken: -1
    property int removing: -1
    function removeSong(index) {
        const song = page.songs[index]
        removing = index
        removeToken = WaveApp.removeFromPlaylist(page.itemId, song.id, song.setVideoId)
    }
    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== page.removeToken) return
            if (JSON.parse(json).ok && page.removing >= 0) {
                const next = page.songs.slice()
                next.splice(page.removing, 1)
                page.songs = next
            }
            page.removing = -1
        }
    }
    footer: BusyIndicator { width: page.width; visible: page.loading && page.songs.length > 0; running: visible }
}
