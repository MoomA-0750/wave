import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Controls.impl
import QtQuick.Layouts
import wave

// 検索のページ（GTK 版・Mac と同じ）。語の前は「最近の検索」と「すべてをブラウズ」（気分・ジャンルのタイル）、
// 語の後はタブ（すべて／曲／アルバム／アーティスト／ライブ／ポッドキャスト／エピソード）。続きは下の端で読む。
Item {
    id: page
    property string query: ""
    property string pageKind: "search"
    property string pageTitle: query === "" ? "検索" : "「" + query + "」の検索"
    property var settings: JSON.parse(WaveApp.settingsJson())
    property var moods: []
    property int moodToken: -1
    readonly property var tabs: [["all", "すべて"], ["songs", "曲"], ["albums", "アルバム"], ["artists", "アーティスト"], ["live", "ライブ"], ["podcasts", "ポッドキャスト"], ["episodes", "エピソード"], ["lyrics", "歌詞"]]
    property string tab: "all"
    // タブごとの結果 { items, continuation, loading, error, token }
    property var results: ({})
    property var all: null
    property int allToken: -1

    function search(q) {
        query = (q || "").trim()
        if (Style.nav) Style.nav.searchQuery = query
        results = ({})
        all = null
        tab = "all"
        if (query !== "") { allToken = WaveApp.request("search.all", query, ""); }
    }
    function loadTab(name, more) {
        if (name === "all" || query === "") return
        const old = results[name] || { items: [], continuation: "", loading: false, error: false }
        if (old.loading || (more && old.continuation === "") || (!more && old.items.length > 0)) return
        const r = Object.assign({}, old)
        const kind = name === "live" ? "search.live" : "search." + name
        r.loading = true
        r.token = WaveApp.request(kind, query, more ? r.continuation : "")
        const next = Object.assign({}, results); next[name] = r; results = next
    }
    Component.onCompleted: {
        search(query)
        // 確かめ用: WAVE_DEMO_SEARCH_TAB にある種類（lyrics など）を開く（GTK 版と同じ）
        const demoTab = WaveApp.env("WAVE_DEMO_SEARCH_TAB")
        if (query !== "" && demoTab !== "") tab = demoTab
        moodToken = WaveApp.request("moods", "", "")
    }
    onTabChanged: loadTab(tab, false)
    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            const v = JSON.parse(json)
            if (t === page.moodToken) { if (!v.error) page.moods = v.tiles; return }
            if (t === page.allToken) { page.all = v; return }
            for (const name in page.results) {
                const old = page.results[name]
                if (old.token !== t) continue
                // 新しい入れ物にする（同じ物を書き換えるだけだと、見ている側が変わったことに気づかない）
                const r = Object.assign({}, old, { loading: false })
                if (v.error) r.error = true
                else { r.items = old.items.concat(v.items); r.continuation = v.continuation || "" }
                const next = Object.assign({}, page.results); next[name] = r; page.results = next
            }
        }
        function onSettingsVersionChanged() { page.settings = JSON.parse(WaveApp.settingsJson()) }
    }

    // 語の前: 最近の検索とすべてをブラウズ
    Flickable {
        anchors.fill: parent
        visible: page.query === ""
        contentHeight: browse.implicitHeight + Style.pageBottom
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar {}
        ColumnLayout {
            id: browse
            x: 24
            y: 16
            width: parent.width - 48
            spacing: 16
            RowLayout {
                visible: page.settings.search_history.length > 0
                Layout.fillWidth: true
                Section { text: "最近の検索"; Layout.fillWidth: true }
                Btn { text: "すべて消去"; flat: true; onClicked: WaveApp.clearSearchHistory() }
            }
            GridLayout {
                visible: page.settings.search_history.length > 0
                Layout.fillWidth: true
                columns: Math.max(1, Math.floor(width / 270))
                columnSpacing: 8
                rowSpacing: 8
                Repeater {
                    model: page.settings.search_history.slice(0, 12)
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 60
                        radius: Style.small
                        color: historyHover.hovered ? Style.hover : "transparent"
                        RowLayout {
                            anchors.fill: parent
                            anchors.margins: 6
                            spacing: 12
                            Art {
                                Layout.preferredWidth: 48
                                Layout.preferredHeight: 48
                                source: modelData.kind === "query" ? "" : (modelData.thumbnail_url || (modelData.song ? modelData.song.ThumbnailUrl : "") || "")
                                round: modelData.kind === "artist"
                                placeholder: modelData.kind === "query" ? "system-search-symbolic" : "folder-music-symbolic"
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 0
                                Label { Layout.fillWidth: true; text: modelData.title; elide: Text.ElideRight; color: Style.foreground }
                                Label { Layout.fillWidth: true; text: modelData.subtitle || ""; visible: text !== ""; elide: Text.ElideRight; font.pixelSize: 12; color: Style.dim }
                            }
                            IconButton {
                                iconName: "window-close-symbolic"
                                tip: "履歴から削除"
                                size: 14
                                onClicked: WaveApp.forgetSearch(modelData.kind + ":" + (modelData.id || (modelData.song ? modelData.song.VideoId : modelData.title)))
                            }
                        }
                        HoverHandler { id: historyHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: {
                                const e = modelData
                                if (e.kind === "query") page.search(e.title)
                                else if (e.kind === "song" && e.song) WaveApp.playSongs(JSON.stringify([{ song: e.song }]), 0, "", false)
                                else Style.nav.openItem({ kind: e.kind, id: e.id, title: e.title })
                            }
                        }
                    }
                }
            }
            Section { text: "すべてをブラウズ"; Layout.topMargin: 8 }
            BusyIndicator { visible: page.moods.length === 0; running: visible }
            GridLayout {
                Layout.fillWidth: true
                columns: Math.max(2, Math.min(8, Math.floor(width / 180)))
                columnSpacing: 12
                rowSpacing: 12
                Repeater {
                    model: page.moods
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 110
                        radius: Style.radius
                        color: moodHover.hovered ? Qt.lighter(modelData.color, 1.15) : modelData.color
                        Label {
                            anchors.fill: parent
                            anchors.margins: 14
                            text: modelData.title
                            color: "white"
                            font.pixelSize: 17
                            font.weight: Font.Bold
                            wrapMode: Text.Wrap
                            maximumLineCount: 2
                            elide: Text.ElideRight
                        }
                        HoverHandler { id: moodHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: Style.nav.openMood(modelData.title, modelData.params) }
                    }
                }
            }
        }
    }

    // 語の後: タブと結果
    ColumnLayout {
        anchors.fill: parent
        visible: page.query !== ""
        spacing: 8
        Flickable {
            Layout.fillWidth: true
            Layout.topMargin: 12
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.preferredHeight: 34
            contentWidth: tabRow.implicitWidth
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Row {
                id: tabRow
                spacing: 8
                Repeater {
                    model: page.tabs
                    delegate: Chip { text: modelData[1]; on: page.tab === modelData[0]; onClicked: page.tab = modelData[0] }
                }
            }
        }
        // すべて: トップの検索結果・曲4件・アーティストの棚・アルバムの棚
        Flickable {
            visible: page.tab === "all"
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentHeight: allColumn.implicitHeight + Style.pageBottom
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar {}
            ColumnLayout {
                id: allColumn
                x: 16
                y: 8
                width: parent.width - 32
                spacing: 28
                BusyIndicator { visible: !page.all; running: visible; Layout.alignment: Qt.AlignHCenter }
                GridLayout {
                    visible: !!page.all
                    Layout.fillWidth: true
                    columns: allColumn.width >= 760 ? 2 : 1
                    columnSpacing: 24
                    rowSpacing: 24
                    ColumnLayout {
                        visible: !!page.all && !!page.all.top
                        Layout.preferredWidth: 360
                        Layout.maximumWidth: 400
                        Layout.fillWidth: allColumn.width < 760
                        Layout.alignment: Qt.AlignTop
                        spacing: 10
                        Section { text: "トップの検索結果" }
                        Rectangle {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 220
                            radius: Style.radius
                            color: topHover.hovered ? Qt.lighter(Style.raised, 1.12) : Style.raised
                            ColumnLayout {
                                anchors.fill: parent
                                anchors.margins: 20
                                spacing: 10
                                Art { Layout.preferredWidth: 96; Layout.preferredHeight: 96; source: page.all && page.all.top ? page.all.top.art : ""; round: page.all && page.all.top ? !!page.all.top.round : false }
                                Label { Layout.fillWidth: true; text: page.all && page.all.top ? page.all.top.title : ""; font.pixelSize: 26; font.weight: Font.Bold; elide: Text.ElideRight; color: Style.foreground }
                                Label { Layout.fillWidth: true; text: page.all && page.all.top ? page.all.top.subtitle : ""; elide: Text.ElideRight; color: Style.dim }
                            }
                            PlayButton {
                                anchors.right: parent.right
                                anchors.bottom: parent.bottom
                                anchors.margins: 16
                                visible: topHover.hovered
                                onClicked: WaveApp.playItem(JSON.stringify(page.all.top), false)
                            }
                            HoverHandler { id: topHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: Style.nav.openItem(page.all.top) }
                        }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        Layout.alignment: Qt.AlignTop
                        spacing: 4
                        visible: !!page.all && page.all.songs.length > 0
                        Section { text: "曲" }
                        Repeater {
                            model: page.all ? page.all.songs : []
                            delegate: TrackRow {
                                Layout.fillWidth: true
                                song: modelData
                                showAlbum: false
                                onPlay: { WaveApp.rememberSearch(JSON.stringify(modelData)); WaveApp.playSongs(JSON.stringify([modelData]), 0, "", false) }
                            }
                        }
                    }
                }
                Shelf { visible: !!page.all && page.all.artists.length > 0; Layout.fillWidth: true; title: "アーティスト"; items: page.all ? page.all.artists : [] }
                Shelf { visible: !!page.all && page.all.albums.length > 0; Layout.fillWidth: true; title: "アルバム"; items: page.all ? page.all.albums : [] }
            }
        }
        // 曲・ライブ・エピソード（行）とアルバム・アーティスト・ポッドキャスト（格子）
        Repeater {
            model: page.tabs.slice(1)
            delegate: Item {
                id: tabView
                readonly property string name: modelData[0]
                readonly property var result: page.results[name] || { items: [], continuation: "", loading: true, error: false }
                readonly property bool rows: name === "songs" || name === "live" || name === "episodes" || name === "lyrics"
                visible: page.tab === name
                Layout.fillWidth: true
                Layout.fillHeight: true
                ListView {
                    anchors.fill: parent
                    visible: tabView.rows
                    clip: true
                    model: tabView.rows ? tabView.result.items : []
                    ScrollBar.vertical: ScrollBar {}
                    onAtYEndChanged: if (atYEnd && visible) page.loadTab(tabView.name, true)
                    delegate: Column {
                        width: ListView.view.width - 32
                        x: 16
                        TrackRow {
                            width: parent.width
                            song: modelData
                            onPlay: { WaveApp.rememberSearch(JSON.stringify(modelData)); WaveApp.playSongs(JSON.stringify([modelData]), 0, "", false) }
                        }
                        // 歌詞から探したときは、合った行を曲名の下に（GTK 版と同じ）
                        Label {
                            visible: (modelData.quote || "") !== ""
                            x: 60
                            width: parent.width - 60
                            bottomPadding: 8
                            text: modelData.quote || ""
                            wrapMode: Text.Wrap
                            maximumLineCount: 2
                            elide: Text.ElideRight
                            font.pixelSize: 13
                            color: Style.accent
                        }
                    }
                    footer: BusyIndicator { width: parent ? parent.width : 0; visible: tabView.result.loading; running: visible }
                }
                GridView {
                    id: cards
                    anchors.fill: parent
                    anchors.leftMargin: 16
                    visible: !tabView.rows
                    clip: true
                    cellWidth: 190
                    cellHeight: 240
                    model: tabView.rows ? [] : tabView.result.items
                    ScrollBar.vertical: ScrollBar {}
                    onAtYEndChanged: if (atYEnd && visible) page.loadTab(tabView.name, true)
                    delegate: Card { item: modelData; size: 168 }
                    footer: BusyIndicator { width: cards.width; visible: tabView.result.loading; running: visible }
                }
                Label {
                    anchors.centerIn: parent
                    visible: !tabView.result.loading && tabView.result.items.length === 0
                    text: tabView.result.error ? "検索できませんでした" : "見つかりませんでした"
                    color: Style.dim
                }
            }
        }
    }
}
