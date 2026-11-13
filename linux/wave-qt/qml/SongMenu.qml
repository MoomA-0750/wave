import QtQuick
import QtQuick.Controls
import wave

// 曲のメニュー（次に再生・キューに追加・評価・プレイリストに追加・アルバム・アーティスト）。曲でないカードは「再生」だけ。
// removal があれば「キューから削除」も（{ label, action }）。
WMenu {
    id: menu
    property var item: ({})
    property var removal: null
    readonly property bool isSong: item.kind === "song"
    property var playlists: []
    onAboutToShow: playlists = isSong && WaveApp.loggedIn ? JSON.parse(WaveApp.editablePlaylists()) : []

    MenuItem { text: Style.t("再生", "Play"); visible: !menu.isSong; height: visible ? implicitHeight : 0; onTriggered: WaveApp.playItem(JSON.stringify(menu.item), false) }
    MenuItem { text: Style.t("次に再生", "Play next"); visible: menu.isSong; height: visible ? implicitHeight : 0; onTriggered: WaveApp.playNextSong(JSON.stringify(menu.item)) }
    MenuItem { text: Style.t("キューに追加", "Add to queue"); visible: menu.isSong; height: visible ? implicitHeight : 0; onTriggered: WaveApp.addToQueue(JSON.stringify(menu.item)) }
    MenuItem { text: Style.t("ラジオを開始", "Start radio"); visible: menu.isSong; height: visible ? implicitHeight : 0; onTriggered: WaveApp.playItem(JSON.stringify(menu.item), false) }
    MenuSeparator { visible: menu.isSong && WaveApp.loggedIn; height: visible ? implicitHeight : 0 }
    MenuItem { text: Style.t("高く評価", "Like"); visible: menu.isSong && WaveApp.loggedIn; height: visible ? implicitHeight : 0; onTriggered: WaveApp.setSongLiked(JSON.stringify(menu.item), true) }
    WMenu {
        id: add
        title: Style.t("プレイリストに保存", "Save to playlist")
        enabled: menu.isSong && WaveApp.loggedIn
        MenuItem { text: Style.t("新しいプレイリスト", "New playlist"); onTriggered: Style.nav.createPlaylist(menu.item) }
        MenuSeparator {}
        Repeater {
            model: menu.playlists
            delegate: MenuItem { text: modelData.title; onTriggered: WaveApp.addSongToPlaylist(JSON.stringify(menu.item), modelData.id, modelData.title) }
        }
    }
    MenuItem {
        property int dl: menu.isSong ? WaveApp.downloadState(menu.item.id || "") : 0
        text: dl === 0 ? Style.t("ダウンロード", "Download") : Style.t("ダウンロードを削除", "Remove download")
        visible: menu.isSong && !menu.item.live
        height: visible ? implicitHeight : 0
        onTriggered: dl === 0 ? WaveApp.downloadSong(JSON.stringify(menu.item)) : WaveApp.removeDownload(menu.item.id)
    }
    MenuSeparator { visible: menu.isSong; height: visible ? implicitHeight : 0 }
    MenuItem { text: Style.t("アルバムへ移動", "Go to album"); visible: menu.isSong && (menu.item.albumId || "") !== ""; height: visible ? implicitHeight : 0; onTriggered: Style.nav.openAlbum(menu.item.albumId, menu.item.album) }
    MenuItem { text: Style.t("アーティストへ移動", "Go to artist"); visible: menu.isSong && (menu.item.artistId || "") !== ""; height: visible ? implicitHeight : 0; onTriggered: Style.nav.openArtist(menu.item.artistId, menu.item.artists) }
    MenuItem { text: menu.removal ? menu.removal.label : ""; visible: !!menu.removal; height: visible ? implicitHeight : 0; onTriggered: menu.removal.action() }
}
