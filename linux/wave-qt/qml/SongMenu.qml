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

    MenuItem { text: "再生"; visible: !menu.isSong; height: visible ? implicitHeight : 0; onTriggered: WaveApp.playItem(JSON.stringify(menu.item), false) }
    MenuItem { text: "次に再生"; visible: menu.isSong; height: visible ? implicitHeight : 0; onTriggered: WaveApp.playNextSong(JSON.stringify(menu.item)) }
    MenuItem { text: "キューに追加"; visible: menu.isSong; height: visible ? implicitHeight : 0; onTriggered: WaveApp.addToQueue(JSON.stringify(menu.item)) }
    MenuItem { text: "ラジオを再生"; visible: menu.isSong; height: visible ? implicitHeight : 0; onTriggered: WaveApp.playItem(JSON.stringify(menu.item), false) }
    MenuSeparator { visible: menu.isSong && WaveApp.loggedIn; height: visible ? implicitHeight : 0 }
    MenuItem { text: "高く評価"; visible: menu.isSong && WaveApp.loggedIn; height: visible ? implicitHeight : 0; onTriggered: WaveApp.setSongLiked(JSON.stringify(menu.item), true) }
    WMenu {
        id: add
        title: "プレイリストに追加"
        enabled: menu.isSong && WaveApp.loggedIn
        MenuItem { text: "新しいプレイリスト…"; onTriggered: Style.nav.createPlaylist(menu.item) }
        MenuSeparator {}
        Repeater {
            model: menu.playlists
            delegate: MenuItem { text: modelData.title; onTriggered: WaveApp.addSongToPlaylist(JSON.stringify(menu.item), modelData.id, modelData.title) }
        }
    }
    MenuItem {
        property int dl: menu.isSong ? WaveApp.downloadState(menu.item.id || "") : 0
        text: dl === 0 ? "ダウンロード" : "ダウンロードを削除"
        visible: menu.isSong && !menu.item.live
        height: visible ? implicitHeight : 0
        onTriggered: dl === 0 ? WaveApp.downloadSong(JSON.stringify(menu.item)) : WaveApp.removeDownload(menu.item.id)
    }
    MenuSeparator { visible: menu.isSong; height: visible ? implicitHeight : 0 }
    MenuItem { text: "アルバムへ移動"; visible: menu.isSong && (menu.item.albumId || "") !== ""; height: visible ? implicitHeight : 0; onTriggered: Style.nav.openAlbum(menu.item.albumId, menu.item.album) }
    MenuItem { text: "アーティストへ移動"; visible: menu.isSong && (menu.item.artistId || "") !== ""; height: visible ? implicitHeight : 0; onTriggered: Style.nav.openArtist(menu.item.artistId, menu.item.artists) }
    MenuItem { text: menu.removal ? menu.removal.label : ""; visible: !!menu.removal; height: visible ? implicitHeight : 0; onTriggered: menu.removal.action() }
}
