import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Controls.impl
import QtQuick.Layouts
import wave

// 左の欄のマイライブラリ（GTK 版・Mac の LibraryPane と同じ）。見出しと「＋」、種類のチップ（もう一度押すと外す）、
// 検索の欄と並べ方・リストとグリッドの切り替え。先頭は高く評価した曲 → ダウンロード。
Item {
    id: pane
    property string kind: ""
    property string query: ""
    property var settings: JSON.parse(WaveApp.settingsJson())
    property var entries: []
    readonly property var sorts: [["recent", "最近"], ["added", "最近追加した順"], ["alpha", "アルファベット順"]]

    function refresh() { entries = JSON.parse(WaveApp.libraryEntries(kind, query)) }
    Component.onCompleted: refresh()
    onKindChanged: refresh()
    onQueryChanged: refresh()
    Connections {
        target: WaveApp
        function onLibraryVersionChanged() { pane.refresh() }
        function onSettingsVersionChanged() { pane.settings = JSON.parse(WaveApp.settingsJson()); pane.refresh() }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 4

        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 10
            Layout.leftMargin: 16
            Layout.rightMargin: 8
            IconImage { source: Style.icon("folder-music-symbolic"); sourceSize.width: 16; sourceSize.height: 16; color: Style.foreground }
            Label { text: "マイライブラリ"; font.weight: Font.Bold; Layout.fillWidth: true; color: Style.foreground }
            IconButton { iconName: "list-add-symbolic"; tip: "プレイリストを作成"; visible: WaveApp.loggedIn; onClicked: Style.nav.createPlaylist(null) }
        }
        Flickable {
            Layout.fillWidth: true
            Layout.leftMargin: 12
            Layout.rightMargin: 12
            Layout.preferredHeight: 36
            contentWidth: chips.implicitWidth
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Row {
                id: chips
                spacing: 6
                Repeater {
                    model: [["playlist", "プレイリスト"], ["artist", "アーティスト"], ["album", "アルバム"], ["podcast", "ポッドキャスト"]]
                    delegate: Chip {
                        text: modelData[1]
                        on: pane.kind === modelData[0]
                        onClicked: pane.kind = pane.kind === modelData[0] ? "" : modelData[0]
                    }
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 12
            Layout.rightMargin: 8
            spacing: 4
            TextField {
                id: search
                Layout.fillWidth: true
                Layout.preferredHeight: 36
                placeholderText: "マイライブラリを検索"
                topPadding: 0
                bottomPadding: 0
                leftPadding: 32
                verticalAlignment: TextInput.AlignVCenter
                background: Rectangle { radius: Style.small; color: Style.raised }
                onTextChanged: pane.query = text
                IconImage { anchors.left: parent.left; anchors.leftMargin: 10; anchors.verticalCenter: parent.verticalCenter; source: Style.icon("system-search-symbolic"); sourceSize.width: 14; sourceSize.height: 14; color: Style.dim }
            }
            Btn {
                id: sortButton
                flat: true
                font.pixelSize: 12
                text: (pane.sorts.find(s => s[0] === pane.settings.library_sort) || pane.sorts[0])[1]
                Material.roundedScale: Style.small
                onClicked: sortMenu.popup(sortButton, 0, sortButton.height)
                WMenu {
                    id: sortMenu
                    Repeater {
                        model: pane.sorts
                        delegate: MenuItem { text: modelData[1]; checkable: true; checked: pane.settings.library_sort === modelData[0]; onTriggered: WaveApp.setSetting("library_sort", JSON.stringify(modelData[0])) }
                    }
                }
            }
            IconButton {
                iconName: pane.settings.library_grid ? "view-list-symbolic" : "view-grid-symbolic"
                tip: pane.settings.library_grid ? "リストで表示" : "グリッドで表示"
                onClicked: WaveApp.setSetting("library_grid", JSON.stringify(!pane.settings.library_grid))
            }
        }

        Label {
            visible: !WaveApp.loggedIn
            Layout.fillWidth: true
            Layout.margins: 16
            wrapMode: Text.Wrap
            text: "ログインすると、YouTube Music のプレイリスト・アーティスト・アルバムがここに並びます。"
            color: Style.dim
        }
        Item { visible: !WaveApp.loggedIn; Layout.fillHeight: true }

        // リスト
        ListView {
            visible: WaveApp.loggedIn && !pane.settings.library_grid
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: pane.entries
            ScrollBar.vertical: ScrollBar {}
            delegate: ItemDelegate {
                width: ListView.view.width
                height: 58
                leftPadding: 12
                contentItem: RowLayout {
                    spacing: 12
                    LibraryArt { entry: modelData; Layout.preferredWidth: 46; Layout.preferredHeight: 46 }
                    ColumnLayout {
                        spacing: 1
                        Layout.fillWidth: true
                        Label { text: modelData.title; elide: Text.ElideRight; Layout.fillWidth: true; color: Style.foreground }
                        Label { text: modelData.subtitle; elide: Text.ElideRight; Layout.fillWidth: true; font.pixelSize: 12; color: Style.dim }
                    }
                }
                onClicked: pane.open(modelData)
                TapHandler { acceptedButtons: Qt.RightButton; onTapped: pane.menu(modelData) }
            }
        }
        // グリッド（名前だけ）
        GridView {
            id: grid
            visible: WaveApp.loggedIn && pane.settings.library_grid
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.leftMargin: 12
            clip: true
            cellWidth: Math.floor((width - 4) / 2)
            cellHeight: cellWidth + 30
            model: pane.entries
            delegate: Item {
                width: grid.cellWidth
                height: grid.cellHeight
                Column {
                    anchors.fill: parent
                    anchors.margins: 4
                    spacing: 4
                    LibraryArt { entry: modelData; width: parent.width; height: parent.width }
                    Label { text: modelData.title; width: parent.width; elide: Text.ElideRight; font.pixelSize: 12; color: Style.foreground }
                }
                TapHandler { onTapped: pane.open(modelData) }
                TapHandler { acceptedButtons: Qt.RightButton; onTapped: pane.menu(modelData) }
            }
        }
    }

    function open(entry) {
        Style.nav.openItem(entry)
    }
    function menu(entry) {
        if (entry.kind === "downloads" || entry.kind === "podcast" || entry.kind === "newepisodes") return
        entryMenu.entry = entry.kind === "liked" ? Object.assign({}, entry, { kind: "playlist" }) : entry
        entryMenu.popup()
    }
    WMenu {
        id: entryMenu
        property var entry: ({})
        MenuItem { text: "再生"; onTriggered: WaveApp.playItem(JSON.stringify(entryMenu.entry), false) }
    }

    // 先頭の固定の行は絵の代わりに記号（高く評価した曲・ダウンロード）
    component LibraryArt: Item {
        property var entry: ({})
        Rectangle {
            anchors.fill: parent
            visible: entry.kind === "liked" || entry.kind === "downloads" || entry.kind === "newepisodes"
            radius: Style.small
            gradient: Gradient {
                GradientStop { position: 0; color: entry.kind === "liked" ? "#4b2bd6" : entry.kind === "newepisodes" ? "#b34700" : "#0f6b5c" }
                GradientStop { position: 1; color: entry.kind === "liked" ? "#c14de0" : entry.kind === "newepisodes" ? "#e8912d" : "#1aa48c" }
            }
            IconImage {
                anchors.centerIn: parent
                color: "white"
                source: Style.icon(entry.kind === "liked" ? "starred-symbolic" : entry.kind === "newepisodes" ? "audio-input-microphone-symbolic" : "folder-download-symbolic")
                sourceSize.width: parent.width * 0.42
                sourceSize.height: parent.width * 0.42
            }
        }
        Art {
            anchors.fill: parent
            visible: !(entry.kind === "liked" || entry.kind === "downloads" || entry.kind === "newepisodes")
            source: entry.thumb || ""
            round: !!entry.round
        }
    }
}
