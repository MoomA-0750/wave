import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
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
    // 窓の幅の段（GTK 版と同じ。0: 1400 以上、1: 1180〜、2: 1000〜、3: 880〜、4: それ未満）。
    // 右の欄は段 1 まで（Claude の欄は段 3 まで）。下の帯は、段 0 だけ端末名、段 1 まで音量のバー
    readonly property int level: width >= 1400 ? 0 : width >= 1180 ? 1 : width >= 1000 ? 2 : width >= 880 ? 3 : 4
    readonly property bool dockRight: rightOpen && (level <= 1 || (rightView === "ai" && level <= 3))
    // 狭くて横に並べられない窓では、右の欄を中身の上に重ねて出す（押した間だけ。外を押す・Esc で閉じる）
    property bool overlayRight: false
    readonly property bool showRight: dockRight || overlayRight
    readonly property bool narrowRight: !(level <= 1 || (rightView === "ai" && level <= 3))
    onNarrowRightChanged: if (!narrowRight) overlayRight = false
    // 映像だけを窓いっぱいに出している（右の欄・小窓の「拡大」から直に開いたなら、やめると元の画面に戻す）
    property bool videoOnly: false
    property bool videoOnlyFromExpand: false
    property bool pipOpen: false
    // 今の検索の語（上の帯の検索の欄に出す）
    property string searchQuery: ""
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
        case "podcast": case "newepisodes": openPodcast(item.id, item.title); break
        case "downloads": openDownloads(); break
        }
    }
    function openAlbum(id, title) { if (id) push(collectionPage, { kind: "album", itemId: id, pageTitle: title || "" }) }
    function openPlaylist(id, title) { if (id) push(collectionPage, { kind: "playlist", itemId: id, pageTitle: title || "" }) }
    function openArtist(id, name) { if (id) push(artistPage, { itemId: id, pageTitle: name || "" }) }
    function openSearch(query) {
        searchQuery = query
        if (visibleKind === "search") nav.currentItem.search(query)
        else push(searchPage, { query: query })
    }
    function openMood(title, params) { push(moodPage, { pageTitle: title, params: params }) }
    function openSettings() { push(settingsPage, {}) }
    function openPodcast(id, title) { if (id) push(podcastPage, { itemId: id, pageTitle: title || "" }) }
    function openFeedSearch() { push(feedSearchPage, {}) }
    function openDownloads() { if (visibleKind !== "downloads") push(downloadsPage, {}) }
    function openDevices() { if (visibleKind !== "devices") push(devicesPage, {}) }
    // Claude の欄（開く前の右の欄を覚え、閉じたら戻す）
    property var beforeAi: null
    function toggleAi() { if (showRight && rightView === "ai") closeAi(); else showAi() }
    function showAi() {
        if (level > 3) { overlayRight = true; rightView = "ai"; return }
        if (!(showRight && rightView === "ai")) beforeAi = { open: rightOpen, view: rightView }
        rightOpen = true
        rightView = "ai"
    }
    function closeAi() {
        if (overlayRight) { overlayRight = false; return }
        const before = beforeAi || { open: true, view: "now" }
        beforeAi = null
        setRight(before.open, before.view === "ai" ? "now" : before.view)
    }
    function toggleLyrics() {
        if (visibleKind === "lyrics") back()
        else push(lyricsPage, {})
    }
    function expandVideo() {
        WaveApp.setVideoOn(true)
        full = true
        videoOnly = true
        videoOnlyFromExpand = true
    }
    function toggleVideoOnly() {
        if (videoOnly) {
            if (videoOnlyFromExpand) { videoOnly = false; videoOnlyFromExpand = false; full = false }
            else videoOnly = false
        } else {
            videoOnly = true
            full = true
        }
    }
    function openPip() {
        WaveApp.setVideoOn(true)
        if (videoOnly) toggleVideoOnly()
        if (!pipWindow) pipWindow = pipComponent.createObject(shell, { shell: shell })
        pipOpen = true
        pipWindow.show()
    }
    function closePip() { if (pipWindow) pipWindow.close() }
    function togglePip() { if (pipOpen) closePip(); else openPip() }
    function pipClosed() { pipOpen = false; if (pipWindow) { pipWindow.destroy(); pipWindow = null } }
    function pipBackToApp() { closePip(); Window.window.raise(); Window.window.requestActivate(); expandVideo() }
    property var pipWindow: null
    Component { id: pipComponent; PipWindow {} }
    // 曲が変わって映像が無くなったら、映像だけの表示をやめる（小窓は開いたまま）
    Connections {
        target: WaveApp
        function onShowVideoChanged() { if (!WaveApp.showVideo && shell.videoOnly && !shell.pipOpen) shell.toggleVideoOnly() }
        // 確かめ用: 映像が見つかってから、映像だけ・小窓にする
        function onHasVideoChanged() {
            if (!WaveApp.hasVideo) return
            if (WaveApp.demo === "videoonly") shell.expandVideo()
            else if (WaveApp.demo === "pip") shell.openPip()
        }
    }

    function goHome() { full = false; forwardPages = []; records = []; nav.pop(null) }
    function back() {
        if (videoOnly) { toggleVideoOnly(); return }
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
    function toggleRight(view) {
        if (level > 1) { overlayRight = !(overlayRight && rightView === view); rightView = view; return }
        setRight(!(showRight && rightView === view), view)
    }
    function toggleQueue() { toggleRight("queue") }
    function togglePane() { toggleRight("now") }

    Component.onCompleted: {
        Style.nav = shell
        // 確かめ用: WAVE_DEMO（search:語・album:ID・playlist:ID・artist:ID・lyrics・queue・settings・full・mood:題:params・history・
        // savequeue（キューを開いて保存の名前の画面を出す）・newplaylist（新しいプレイリストの名前の画面を出す））
        const demo = WaveApp.demo
        if (demo === "") return
        const [kind, ...rest] = demo.split(":")
        const arg = rest.join(":")
        if (kind === "search") openSearch(arg)
        else if (kind === "album") openAlbum(arg, "")
        else if (kind === "playlist") openPlaylist(arg, "")
        else if (kind === "artist") openArtist(arg, "")
        else if (kind === "lyrics") push(lyricsPage, {})
        else if (kind === "queue") { setRight(true, "queue"); overlayRight = narrowRight }
        else if (kind === "savequeue") { setRight(true, "queue"); overlayRight = narrowRight; demoDialog.start() }
        else if (kind === "newplaylist") demoDialog.start()
        else if (kind === "settings") openSettings()
        else if (kind === "full") demoFull.start()
        else if (kind === "mood") openMood(rest[0], rest.slice(1).join(":"))
        else if (kind === "ai") showAi()
        else if (kind === "devices") openDevices()
        else if (kind === "downloads") openDownloads()
        else if (kind === "podcast") openPodcast(arg, Style.t("ポッドキャスト", "Podcast"))
        else if (kind === "newepisodes") openPodcast("VLRDPN", Style.t("新しいエピソード", "New episodes"))
        else if (kind === "feedsearch") openFeedSearch()
        else if (kind === "firstpodcast") demoToken = WaveApp.request("search.podcasts", arg, "")
        else if (kind === "firstplaylist") { demoPlaylist = true; demoToken = WaveApp.request("search.albums", arg, "") }
        else if (kind === "firstalbum" || kind === "firstartist") demoToken = WaveApp.request(kind === "firstalbum" ? "search.albums" : "search.artists", arg, "")
    }
    // 確かめ用: firstalbum:語・firstartist:語 は検索の1件目のページを開く
    property int demoToken: -1
    property bool demoPlaylist: false
    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== shell.demoToken) return
            const v = JSON.parse(json)
            // 確かめ用: firstplaylist:語 は検索の1件目のアルバムの曲のプレイリスト（OLAK…）を、プレイリストとして開く
            if (shell.demoPlaylist && v.items) { shell.demoToken = WaveApp.request("album", v.items[0].id, ""); return }
            if (shell.demoPlaylist && v.playlistId) { shell.demoPlaylist = false; shell.openPlaylist(v.playlistId, v.title); return }
            if (v.items && v.items.length > 0) shell.openItem(v.items[0])
        }
        function onToast(text) { toastStrip.show(text) }
        // 新作の通知を押した
        function onOpenRequested(kind, id, title) {
            if (kind === "album") shell.openAlbum(id, title)
            else shell.openArtist(id, title)
        }
    }
    Timer { id: demoFull; interval: 2500; onTriggered: shell.full = true }
    Timer { id: demoDialog; interval: 6000; onTriggered: WaveApp.demo === "savequeue" ? queuePane.askSave() : shell.createPlaylist(null) }

    Shortcut { sequence: "Space"; context: Qt.WindowShortcut; enabled: !(shell.Window.activeFocusItem instanceof TextInput); onActivated: WaveApp.togglePlay() }
    Shortcut { sequence: "Ctrl+Right"; onActivated: WaveApp.next() }
    Shortcut { sequence: "Ctrl+Left"; onActivated: WaveApp.previous() }
    Shortcut { sequence: "Alt+Left"; onActivated: shell.back() }
    Shortcut { sequence: "Alt+Right"; onActivated: shell.forward() }
    Shortcut { sequence: "F11"; onActivated: shell.full = !shell.full }
    Shortcut { sequence: "Escape"; enabled: shell.full; onActivated: shell.videoOnly ? shell.toggleVideoOnly() : (shell.full = false) }
    Shortcut { sequence: "Escape"; enabled: !shell.full && shell.overlayRight; onActivated: shell.overlayRight = false }
    Shortcut { sequences: ["Ctrl+F", "Ctrl+L"]; onActivated: top.focusSearch() }
    Shortcut { sequence: "Ctrl+J"; onActivated: shell.toggleAi() }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TopBar { id: top; Layout.fillWidth: true; shell: shell; visible: !shell.full }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: Style.space.s2
                anchors.rightMargin: Style.space.s2
                spacing: Style.space.s2
                visible: !shell.full
                // 左の欄は狭い窓では細くする（中身のページに幅を残す）
                Pane_ { Layout.preferredWidth: shell.level >= 4 ? 232 : shell.level >= 3 ? 264 : 300; Layout.fillHeight: true; LibraryPane { anchors.fill: parent } }
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
                // 右の欄の場所（中身は下の rightPane。並べるときだけ幅を取る）
                Item { visible: shell.dockRight && !shell.overlayRight; Layout.preferredWidth: 340; Layout.fillHeight: true }
            }
            // 重ねて出している間は、外を暗くして押すと閉じる
            Rectangle {
                anchors.fill: parent
                visible: shell.overlayRight && !shell.full
                color: Qt.rgba(0, 0, 0, 0.35)
                TapHandler { onTapped: shell.overlayRight = false }
            }
            Pane_ {
                id: rightPane
                visible: shell.showRight && !shell.full
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                anchors.right: parent.right
                anchors.rightMargin: Style.space.s2
                width: shell.overlayRight ? Math.min(360, parent.width - 48) : 340
                border.color: shell.overlayRight ? Style.border : "transparent"
                NowPlayingPane { anchors.fill: parent; visible: shell.rightView === "now"; shell: shell }
                QueuePane { id: queuePane; anchors.fill: parent; visible: shell.rightView === "queue"; shell: shell }
                AiPane { anchors.fill: parent; visible: shell.rightView === "ai"; shell: shell }
            }
            FullView { anchors.fill: parent; visible: shell.full; shell: shell }
        }

        // 短い知らせ。中身と下の帯の間に場所を取って出す（中身に重ねると、一覧の行や棚の文字を覆っていた）。3.5 秒で畳む
        Item {
            id: toastStrip
            property string text: ""
            property bool shown: false
            function show(t) { text = t; shown = true; toastHide.restart() }
            Layout.fillWidth: true
            Layout.preferredHeight: shown ? toastBox.height + 12 : 0
            Behavior on Layout.preferredHeight { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
            clip: true
            visible: Layout.preferredHeight > 0
            Rectangle {
                id: toastBox
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.top: parent.top
                anchors.topMargin: 6
                width: Math.min(toastLabel.implicitWidth + 40, toastStrip.width - 48)
                height: toastLabel.implicitHeight + 16
                radius: Style.radius === 0 ? 0 : height / 2
                color: Style.raised
                border.color: Style.border
                Label { id: toastLabel; anchors.centerIn: parent; width: Math.min(implicitWidth, toastStrip.width - 88); text: toastStrip.text; wrapMode: Text.Wrap; horizontalAlignment: Text.AlignHCenter; color: Style.foreground }
            }
            Timer { id: toastHide; interval: 3500; onTriggered: toastStrip.shown = false }
        }

        PlayerBar { Layout.fillWidth: true; shell: shell }
    }

    // 映像だけを窓いっぱいに出す層（下の帯も覆う）。右上に小窓・窓の全画面・やめる（Esc）。ダブルクリックで窓の全画面
    Rectangle {
        anchors.fill: parent
        visible: shell.videoOnly && shell.full
        color: "black"
        z: 50
        VideoView { anchors.fill: parent; place: "layer" }
        TapHandler { onDoubleTapped: shell.toggleWindowFullScreen() }
        // ボタンは映像の中（上下の黒い帯をまたがない位置）に置く
        Row {
            readonly property real videoHeight: Math.min(parent.height, parent.width * 9 / 16)
            anchors.right: parent.right
            anchors.rightMargin: 16 + Math.max(0, (parent.width - parent.height * 16 / 9) / 2)
            y: (parent.height - videoHeight) / 2 + 16
            spacing: Style.space.s2
            LayerButton { iconName: shell.pipOpen ? "view-conceal-symbolic" : "window-new-symbolic"; tip: shell.pipOpen ? Style.t("小窓を閉じる", "Close picture in picture") : Style.t("小窓で見る", "Picture in picture"); onClicked: shell.togglePip() }
            LayerButton {
                iconName: shell.Window.window && shell.Window.window.visibility === Window.FullScreen ? "view-restore-symbolic" : "view-fullscreen-symbolic"
                tip: shell.Window.window && shell.Window.window.visibility === Window.FullScreen ? Style.t("全画面を終える", "Exit full screen") : Style.t("全画面", "Full screen")
                onClicked: shell.toggleWindowFullScreen()
            }
            LayerButton { iconName: "window-close-symbolic"; tip: Style.t("映像だけをやめる (Esc)", "Exit video only (Esc)"); onClicked: shell.toggleVideoOnly() }
        }
    }
    function toggleWindowFullScreen() {
        const w = Window.window
        if (w) w.visibility = w.visibility === Window.FullScreen ? Window.Windowed : Window.FullScreen
    }
    component LayerButton: RoundButton {
        property string iconName
        property string tip
        implicitWidth: 40
        implicitHeight: 40
        icon.source: Style.icon(iconName)
        icon.color: "white"
        background: Rectangle { radius: width / 2; color: Qt.rgba(0, 0, 0, parent.hovered ? 0.75 : 0.55) }
        ToolTip.visible: hovered
        ToolTip.text: tip
    }

    // 左・中央・右の欄の地（一覧と横の欄の色、角は Hyprland の丸み）
    component Pane_: Rectangle {
        color: Style.view
        radius: Style.radius
        clip: true
    }

    // プレイリストを作る（song があれば作ったプレイリストに入れる）
    function createPlaylist(song) {
        newPlaylist.song = song
        newPlaylistTitle.text = ""
        newPlaylist.open()
        newPlaylistTitle.forceActiveFocus()
    }
    WDialog {
        id: newPlaylist
        property var song: null
        title: Style.t("新しいプレイリスト", "New playlist")
        ColumnLayout {
            // 欄はダイアログの幅いっぱい（キューの保存の名前の画面と同じ）
            width: parent ? parent.width : 400
            spacing: 6
            // 名前の見出しは欄の外に置く（Material の placeholderText は文字が入ると枠の上へ浮くが、背景を自前にしているので文字に重なる）
            Label { text: Style.t("プレイリスト名", "Playlist name"); color: Style.dim; font.pixelSize: 12 }
            TextField {
                id: newPlaylistTitle
                Layout.fillWidth: true
                background: Rectangle { radius: Style.small; color: Style.view; border.color: newPlaylistTitle.activeFocus ? Style.accent : Style.border }
                onAccepted: if (text.trim() !== "") newPlaylist.accept()
            }
        }
        footer: DialogButtonBox {
            background: null
            Btn { text: Style.t("キャンセル", "Cancel"); flat: true; DialogButtonBox.buttonRole: DialogButtonBox.RejectRole }
            Btn { text: Style.t("作成", "Create"); highlighted: true; enabled: newPlaylistTitle.text.trim() !== ""; DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole }
        }
        onAccepted: WaveApp.createPlaylist(newPlaylistTitle.text.trim(), newPlaylist.song ? JSON.stringify(newPlaylist.song) : "")
    }

    Component { id: homePage; HomePage {} }
    Component { id: collectionPage; CollectionPage {} }
    Component { id: artistPage; ArtistPage {} }
    Component { id: searchPage; SearchPage {} }
    Component { id: lyricsPage; LyricsPage {} }
    Component { id: settingsPage; SettingsPage {} }
    Component { id: moodPage; HomePage {} }
    Component { id: podcastPage; PodcastPage {} }
    Component { id: feedSearchPage; FeedSearchPage {} }
    Component { id: downloadsPage; DownloadsPage {} }
    Component { id: devicesPage; DevicesPage {} }
}
