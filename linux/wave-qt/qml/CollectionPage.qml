import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Controls.impl
import QtQuick.Layouts
import wave

// アルバム・プレイリスト（高く評価した曲も）・再生履歴のページ（GTK 版と同じ並び）: 上にジャケットと名前・アーティスト（押すとそのページ）・
// 補足（曲数と長さ）・再生・シャッフル・ダウンロード、その下に一覧の中の検索・曲の種類のチップ・並べ替え、下に曲の一覧。
// プレイリストは全部読んでから出す。並べ方はプレイリストごとに覚える（追加した日・リリース日は YouTube Music に並べてもらう）。
ListView {
    id: page
    property string kind: "album"
    property string itemId: ""
    property string pageTitle: ""
    property string pageKind: kind
    property var info: ({})
    property var songs: []
    property var shown: []
    property bool loading: true
    property string error: ""
    property int token: -1
    property string order: kind === "playlist" ? WaveApp.playlistOrder(itemId) : ""
    property string filter: ""
    property string songKind: ""
    property var sort: JSON.parse(WaveApp.sortChoices(!!info.sortable, order))

    clip: true
    boundsBehavior: Flickable.StopAtBounds
    // 見出しが後から伸びても、頭から見せる（今の行に寄せない）
    currentIndex: -1
    ScrollBar.vertical: ScrollBar {}
    model: shown
    spacing: 0
    bottomMargin: Style.pageBottom

    function load() {
        moreLoading = false
        loading = true
        error = ""
        token = kind === "history" ? WaveApp.request("history", "", "") : WaveApp.request(kind, itemId, kind === "playlist" ? order : "")
    }
    function arrange() {
        const indices = JSON.parse(WaveApp.arrangeSongs(JSON.stringify(songs), filter, songKind, order))
        shown = indices.map(i => Object.assign({}, songs[i], { at: i }))
    }
    function choose(key) {
        const choice = sort.choices.find(c => c.name === key)
        const before = sort.choices.find(c => c.mark !== "")
        const next = WaveApp.chooseOrder(order, key)
        order = next
        if (kind === "playlist") WaveApp.setPlaylistOrder(itemId, next)
        // YouTube Music に並べてもらう並べ方に替えた・外したときは読み直す
        if (kind === "playlist" && ((choice && choice.server) || (before && before.server))) load()
        else arrange()
    }
    Component.onCompleted: load()
    onFilterChanged: arrange()
    onSongKindChanged: arrange()

    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== page.token) return
            page.loading = false
            const v = JSON.parse(json)
            if (v.error && page.moreLoading) { page.moreLoading = false; return }
            if (v.error) { page.error = page.kind === "album" ? "アルバムを読み込めませんでした" : (v.error.indexOf("ログイン") >= 0 ? v.error : "読み込めませんでした"); return }
            if (page.kind === "history") {
                page.info = { title: "再生履歴", subtitle: "", art: "" }
                page.songs = v.sections.reduce((all, s) => all.concat(s.songs), [])
            } else if (v.continuation !== undefined && page.moreLoading) {
                // 続き: 届いた分を足し、まだあれば次を読む
                page.moreLoading = false
                page.songs = page.songs.concat(v.songs)
                page.arrange()
                page.loadMore(v.continuation)
                return
            } else {
                page.info = v
                page.songs = v.songs
                page.pages = 1
                // 並べ替えられないプレイリストでは、YouTube Music に並べてもらう並べ方は選べない
                if (!v.sortable && page.order !== "" && (page.order.startsWith("Added") || page.order.startsWith("Published"))) page.order = ""
                if (page.kind === "playlist") page.loadMore(v.continuation || "")
            }
            page.arrange()
            Qt.callLater(() => page.positionViewAtBeginning())
        }
    }
    // プレイリストの続きを、届いた分から順に足す（GTK 版と同じく 100 ページまで）
    property bool moreLoading: false
    property int pages: 0
    function loadMore(continuation) {
        if (continuation === "" || pages > 100) {
            // 読み終えた。サーバーの補足が無ければ、読めた曲から数える
            if (kind === "playlist" && !info.hasSubtitle) info = Object.assign({}, info, { subtitle: WaveApp.countAndLength(JSON.stringify(songs)) })
            return
        }
        pages++
        moreLoading = true
        token = WaveApp.request("playlist.more", itemId, continuation)
    }

    // 見出しが後から伸びても（文が折り返す・ボタンが出る）、まだ動かしていなければ頭から見せる
    property bool userScrolled: false
    onMovementStarted: userScrolled = true
    header: ColumnLayout {
        width: page.width
        onHeightChanged: if (!page.userScrolled) Qt.callLater(() => page.positionViewAtBeginning())
        spacing: 12
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 24
            Layout.rightMargin: 24
            Layout.topMargin: 24
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
                // アーティスト（押すとそのページ）と補足を1行の文にする（並べた別々の文だとベースラインがずれる）
                Label {
                    Layout.fillWidth: true
                    textFormat: Text.RichText
                    wrapMode: Text.Wrap
                    color: Style.dim
                    linkColor: Style.foreground
                    text: {
                        const esc = (t) => (t || "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
                        const artists = (page.info.artists || []).map(a => a.id ? "<a href=\"" + a.id + "\"><b>" + esc(a.name) + "</b></a>" : "<b>" + esc(a.name) + "</b>").join(", ")
                        // リンクは下線を引かず、文字の色だけ変える（GTK 版と同じ見た目）
                        return "<style>a { text-decoration: none; color: " + Style.foreground + "; }</style>" + [artists, esc(page.info.subtitle)].filter(t => t !== "").join("  ")
                    }
                    onLinkActivated: (id) => { const a = page.info.artists.find(x => x.id === id); Style.nav.openArtist(id, a ? a.name : "") }
                    HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                }
                RowLayout {
                    Layout.topMargin: 10
                    Layout.fillWidth: true
                    spacing: 8
                    visible: page.songs.length > 0
                    PlayButton { onClicked: page.playFrom(0, false) }
                    IconButton { iconName: "media-playlist-shuffle-symbolic"; tip: "シャッフル再生"; size: 22; onClicked: page.playFrom(0, true) }
                    IconButton {
                        visible: page.kind !== "history"
                        iconName: "folder-download-symbolic"
                        tip: "ダウンロード"
                        size: 22
                        onClicked: WaveApp.downloadCollection(page.kind === "album" ? "album" : page.itemId === "LM" ? "liked" : "playlist", page.itemId, page.info.title || page.pageTitle, page.info.art || "", JSON.stringify(page.songs))
                    }
                    BusyIndicator { visible: page.moreLoading; running: visible; Layout.preferredWidth: 28; Layout.preferredHeight: 28 }
                    Item { Layout.fillWidth: true }
                }
            }
        }
        // 一覧の中の検索（虫眼鏡で開く）・並べ替え（GTK 版と同じく右に）、その下に曲の種類のチップ（横に送れる帯）
        RowLayout {
            visible: page.songs.length > 0 && page.kind !== "history"
            Layout.fillWidth: true
            Layout.leftMargin: 24
            Layout.rightMargin: 24
            spacing: 8
            Item { Layout.fillWidth: true }
            IconButton { iconName: "system-search-symbolic"; tip: "一覧の中を検索"; checked: find.visible; onClicked: { find.visible = !find.visible; if (find.visible) find.forceActiveFocus(); else find.text = "" } }
            TextField {
                id: find
                visible: false
                Layout.preferredWidth: Math.min(240, page.width / 3)
                Layout.preferredHeight: 36
                placeholderText: "一覧の中を検索"
                topPadding: 0
                bottomPadding: 0
                verticalAlignment: TextInput.AlignVCenter
                background: Rectangle { radius: Style.small; color: Style.raised; border.color: find.activeFocus ? Style.accent : "transparent" }
                onTextChanged: page.filter = text
                Keys.onEscapePressed: { text = ""; visible = false }
            }
            Btn {
                id: sortButton
                flat: true
                text: page.sort.label
                onClicked: sortMenu.popup(sortButton, 0, sortButton.height)
                WMenu {
                    id: sortMenu
                    Repeater {
                        model: page.sort.choices
                        delegate: MenuItem {
                            text: modelData.label
                            icon.source: modelData.mark !== "" ? Style.icon(modelData.mark) : ""
                            onTriggered: page.choose(modelData.name)
                        }
                    }
                }
            }
        }
        Flickable {
            visible: page.songs.length > 0 && page.kind !== "history"
            Layout.fillWidth: true
            Layout.leftMargin: 24
            Layout.rightMargin: 24
            Layout.preferredHeight: 34
            contentWidth: kinds.implicitWidth
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Row {
                id: kinds
                spacing: 6
                Repeater {
                    model: [["", "すべて"], ["MUSIC_VIDEO_TYPE_ATV", "曲"], ["MUSIC_VIDEO_TYPE_OMV", "MV"], ["MUSIC_VIDEO_TYPE_UGC", "その他の動画（歌ってみた・ライブなど）"]]
                    delegate: Chip { text: modelData[1]; on: page.songKind === modelData[0]; onClicked: page.songKind = (modelData[0] === "" || page.songKind === modelData[0]) ? "" : modelData[0] }
                }
            }
        }
        Label {
            visible: page.error !== ""
            text: page.error
            Layout.leftMargin: 24
            color: Style.dim
        }
        Label {
            visible: !page.loading && page.songs.length > 0 && page.shown.length === 0
            text: "合う曲がありません"
            Layout.leftMargin: 24
            color: Style.dim
        }
        BusyIndicator { visible: page.loading; running: visible; Layout.alignment: Qt.AlignHCenter }
    }

    function playFrom(index, shuffle) {
        const source = page.kind === "history" ? "" : page.itemId
        WaveApp.playSongs(JSON.stringify(page.shown), index, source, shuffle)
    }

    delegate: TrackRow {
        width: page.width - 32
        x: 16
        song: modelData
        number: page.kind === "album" ? modelData.at + 1 : 0
        showArt: page.kind !== "album"
        showAlbum: page.kind !== "album"
        subtitleText: page.kind === "album" ? (modelData.artists || "") : (showAlbum ? (modelData.artists || "") : (modelData.subtitle || ""))
        onPlay: page.playFrom(index, false)
        // 自分のプレイリストからは外せる
        removal: page.kind === "playlist" && page.info.editable && (modelData.setVideoId || "") !== ""
                 ? { label: "このプレイリストから削除", action: () => page.removeSong(modelData.at) } : null
    }
    property int removeToken: -1
    property int removing: -1
    function removeSong(at) {
        const song = page.songs[at]
        removing = at
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
                page.arrange()
            }
            page.removing = -1
        }
    }
}
