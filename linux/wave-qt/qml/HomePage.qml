import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// ホーム（GTK 版・Mac と同じ）: 上にチップ（すべて＋気分）、最近聴いたものの格子、登録したアーティストの新作、新作、
// その下にホームの棚。下の端に近づくと続きを読む。params があれば気分・ジャンルのページ（棚だけ）。
Flickable {
    id: page
    property string pageKind: params === "" ? "home" : "mood"
    property string pageTitle: "ホーム"
    property string params: ""
    property var chips: []
    property string selected: ""
    property var recent: []
    property var releases: null
    property var newReleases: null
    property var shelves: []
    property string continuation: ""
    property bool loading: false
    property bool failed: false
    property int token: -1

    contentHeight: column.implicitHeight + 48
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}

    function reload() {
        recent = []; releases = null; newReleases = null; shelves = []; continuation = ""; failed = false; loading = true
        token = params !== "" ? WaveApp.request("mood", params, "") : WaveApp.request("home", selected, "")
    }
    function loadMore() {
        if (loading || continuation === "" || params !== "") return
        loading = true
        token = WaveApp.request("home", "", continuation)
    }
    Component.onCompleted: reload()
    onContentYChanged: if (contentY + height > contentHeight - 600) loadMore()

    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== page.token) return
            page.loading = false
            const v = JSON.parse(json)
            if (v.error) { if (page.shelves.length === 0) page.failed = true; return }
            if (v.chips && v.chips.length > 0 && page.selected === "") page.chips = v.chips
            if (v.recent) page.recent = v.recent
            if (v.releases !== undefined) page.releases = v.releases
            if (v.newReleases !== undefined) page.newReleases = v.newReleases
            page.shelves = page.shelves.concat(v.shelves || [])
            page.continuation = v.continuation || ""
        }
    }

    ColumnLayout {
        id: column
        width: page.width - 32
        x: 16
        y: 16
        spacing: 28

        Section { visible: page.params !== ""; text: page.pageTitle; font.pixelSize: 28 }

        Flickable {
            visible: page.params === ""
            Layout.fillWidth: true
            Layout.preferredHeight: 34
            contentWidth: chipRow.implicitWidth
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Row {
                id: chipRow
                spacing: 8
                Chip { text: "すべて"; on: page.selected === ""; onClicked: { page.selected = ""; page.reload() } }
                Repeater {
                    model: page.chips
                    delegate: Chip {
                        text: modelData.title
                        on: page.selected === modelData.params
                        onClicked: { page.selected = page.selected === modelData.params ? "" : modelData.params; page.reload() }
                    }
                }
            }
        }

        // 最近聴いたもの（4列の格子。押すと開く）
        GridLayout {
            visible: page.recent.length > 0
            Layout.fillWidth: true
            columns: 4
            columnSpacing: 8
            rowSpacing: 8
            Repeater {
                model: page.recent
                delegate: Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 56
                    radius: Style.small
                    color: tileHover.hovered ? Qt.lighter(Style.raised, 1.15) : Style.raised
                    clip: true
                    RowLayout {
                        anchors.fill: parent
                        spacing: 12
                        Art { Layout.preferredWidth: 56; Layout.preferredHeight: 56; source: modelData.thumb; round: !!modelData.round; radius: 0 }
                        Label { Layout.fillWidth: true; Layout.rightMargin: 12; text: modelData.title; font.weight: Font.DemiBold; wrapMode: Text.Wrap; maximumLineCount: 2; elide: Text.ElideRight; color: Style.foreground }
                    }
                    HoverHandler { id: tileHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: Style.nav.openItem(modelData) }
                }
            }
        }

        Shelf { visible: !!page.releases; Layout.fillWidth: true; title: page.releases ? page.releases.title : ""; items: page.releases ? page.releases.items : [] }
        Shelf { visible: !!page.newReleases; Layout.fillWidth: true; title: page.newReleases ? page.newReleases.title : ""; items: page.newReleases ? page.newReleases.items : [] }
        Repeater {
            model: page.shelves
            delegate: Shelf { Layout.fillWidth: true; title: modelData.title; strapline: modelData.strapline; items: modelData.items }
        }

        BusyIndicator { visible: page.loading; running: visible; Layout.alignment: Qt.AlignHCenter }
        ColumnLayout {
            visible: page.failed && !page.loading
            Layout.alignment: Qt.AlignHCenter
            spacing: 8
            Label { text: page.params !== "" ? "読み込めませんでした" : "ホームを読み込めませんでした"; font.pixelSize: 18; font.weight: Font.Bold; Layout.alignment: Qt.AlignHCenter; color: Style.foreground }
            Button { Material.roundedScale: Style.radius; text: "もう一度読み込む"; Layout.alignment: Qt.AlignHCenter; onClicked: page.reload() }
        }
    }
}
