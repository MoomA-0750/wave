import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import wave

// 窓の並び（GTK 版・Mac と同じ）: 上の帯に戻る・進む、中央にホーム・検索の欄、右端にアカウント。左にマイライブラリ、
// 中央にページ、右に再生中・キュー（窓が狭いときは出さない）、下に再生の操作の帯。全画面の再生中の画面は上の2段を覆う。
Item {
    id: shell
    // 右の欄: 開いているか・中身（"now"・"queue"）
    property bool rightOpen: JSON.parse(WaveApp.settingsJson()).right_pane_open
    property string rightView: "now"
    property bool full: false
    // 戻ったページ（「進む」で開き直す。新しいページを開いたら消す）
    property var forwardPages: []
    readonly property bool showRight: rightOpen && width >= 1000
    readonly property string visibleKind: nav.currentItem ? (nav.currentItem.pageKind || "") : ""
    readonly property bool canBack: nav.depth > 1 || full
    readonly property bool canForward: forwardPages.length > 0

    // 積んだページ（「戻る」で外したものを「進む」で開き直すため、作り方を覚える）
    property var records: []
    function push(component, props) {
        forwardPages = []
        full = false
        records = records.concat([{ component: component, props: props }])
        nav.push(component, props)
    }
    function openItem(item) {
        switch (item.kind) {
        case "song": WaveApp.playSongs(JSON.stringify([item]), 0, "", false); break
        case "album": openAlbum(item.id, item.title); break
        case "playlist": openPlaylist(item.id, item.title); break
        case "liked": openPlaylist(item.id, item.title); break
        case "artist": openArtist(item.id, item.title); break
        default: WaveApp.toast("この画面ではまだ開けません（GTK 版で開けます）")
        }
    }
    function openAlbum(id, title) { if (id) push(collectionPage, { kind: "album", itemId: id, pageTitle: title || "" }) }
    function openPlaylist(id, title) { if (id) push(collectionPage, { kind: "playlist", itemId: id, pageTitle: title || "" }) }
    function openArtist(id, name) { if (id) push(artistPage, { itemId: id, pageTitle: name || "" }) }
    function openSearch(query) {
        if (visibleKind === "search") nav.currentItem.search(query)
        else push(searchPage, { query: query })
    }
    function openMood(title, params) { push(moodPage, { pageTitle: title, params: params }) }
    function openSettings() { push(settingsPage, {}) }
    function toggleLyrics() {
        if (visibleKind === "lyrics") back()
        else push(lyricsPage, {})
    }
    function goHome() { full = false; forwardPages = []; records = []; nav.pop(null) }
    function back() {
        if (full) { full = false; return }
        if (nav.depth <= 1) return
        forwardPages = forwardPages.concat([records[records.length - 1]])
        records = records.slice(0, records.length - 1)
        nav.pop()
    }
    function forward() {
        if (forwardPages.length === 0) return
        const last = forwardPages[forwardPages.length - 1]
        forwardPages = forwardPages.slice(0, forwardPages.length - 1)
        records = records.concat([last])
        nav.push(last.component, last.props)
    }
    function setRight(open, view) {
        rightOpen = open
        if (view) rightView = view
        WaveApp.setSetting("right_pane_open", JSON.stringify(open))
    }
    function toggleQueue() { setRight(!(showRight && rightView === "queue"), "queue") }
    function togglePane() { setRight(!(showRight && rightView === "now"), "now") }

    Component.onCompleted: {
        Style.nav = shell
        // 確かめ用: WAVE_DEMO（search:語・album:ID・playlist:ID・artist:ID・lyrics・queue・settings・full・mood:題:params・history）
        const demo = WaveApp.demo
        if (demo === "") return
        const [kind, ...rest] = demo.split(":")
        const arg = rest.join(":")
        if (kind === "search") openSearch(arg)
        else if (kind === "album") openAlbum(arg, "")
        else if (kind === "playlist") openPlaylist(arg, "")
        else if (kind === "artist") openArtist(arg, "")
        else if (kind === "lyrics") push(lyricsPage, {})
        else if (kind === "queue") setRight(true, "queue")
        else if (kind === "settings") openSettings()
        else if (kind === "full") demoFull.start()
        else if (kind === "mood") openMood(rest[0], rest.slice(1).join(":"))
        else if (kind === "firstalbum" || kind === "firstartist") demoToken = WaveApp.request(kind === "firstalbum" ? "search.albums" : "search.artists", arg, "")
    }
    // 確かめ用: firstalbum:語・firstartist:語 は検索の1件目のページを開く
    property int demoToken: -1
    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== shell.demoToken) return
            const v = JSON.parse(json)
            if (v.items && v.items.length > 0) shell.openItem(v.items[0])
        }
    }
    Timer { id: demoFull; interval: 2500; onTriggered: shell.full = true }

    Shortcut { sequence: "Space"; context: Qt.WindowShortcut; enabled: !(shell.Window.activeFocusItem instanceof TextInput); onActivated: WaveApp.togglePlay() }
    Shortcut { sequence: "Ctrl+Right"; onActivated: WaveApp.next() }
    Shortcut { sequence: "Ctrl+Left"; onActivated: WaveApp.previous() }
    Shortcut { sequence: "Alt+Left"; onActivated: shell.back() }
    Shortcut { sequence: "Alt+Right"; onActivated: shell.forward() }
    Shortcut { sequence: "F11"; onActivated: shell.full = !shell.full }
    Shortcut { sequence: "Escape"; enabled: shell.full; onActivated: shell.full = false }
    Shortcut { sequences: ["Ctrl+F", "Ctrl+L"]; onActivated: top.focusSearch() }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TopBar { id: top; Layout.fillWidth: true; shell: shell; visible: !shell.full }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 8
                anchors.rightMargin: 8
                spacing: 8
                visible: !shell.full
                Pane_ { Layout.preferredWidth: 300; Layout.fillHeight: true; LibraryPane { anchors.fill: parent } }
                Pane_ {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    StackView {
                        id: nav
                        anchors.fill: parent
                        clip: true
                        initialItem: homePage
                        pushEnter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 140 } }
                        pushExit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 140 } }
                        popEnter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 140 } }
                        popExit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 140 } }
                    }
                }
                Pane_ {
                    visible: shell.showRight
                    Layout.preferredWidth: 360
                    Layout.fillHeight: true
                    NowPlayingPane { anchors.fill: parent; visible: shell.rightView === "now"; shell: shell }
                    QueuePane { anchors.fill: parent; visible: shell.rightView === "queue"; shell: shell }
                }
            }
            FullView { anchors.fill: parent; visible: shell.full; shell: shell }
        }

        PlayerBar { Layout.fillWidth: true; shell: shell }
    }

    // 左・中央・右の欄の地（一覧と横の欄の色、角は Hyprland の丸み）
    component Pane_: Rectangle {
        color: Style.view
        radius: Style.radius
        clip: true
    }

    Component { id: homePage; HomePage {} }
    Component { id: collectionPage; CollectionPage {} }
    Component { id: artistPage; ArtistPage {} }
    Component { id: searchPage; SearchPage {} }
    Component { id: lyricsPage; LyricsPage {} }
    Component { id: settingsPage; SettingsPage {} }
    Component { id: moodPage; HomePage {} }
}
