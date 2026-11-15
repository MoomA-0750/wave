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
    property string pageTitle: Style.t("ホーム", "Home")
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
    property bool expired: false
    property int token: -1

    contentHeight: column.implicitHeight + Style.pageBottom
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}

    function reload() {
        recent = []; releases = null; newReleases = null; shelves = []; continuation = ""; failed = false; expired = false; loading = true
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
            if (v.error) { if (page.shelves.length === 0) { page.failed = true; page.expired = v.expired === true }; return }
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

        ChipStrip {
            visible: page.params === ""
            Layout.fillWidth: true
            Layout.preferredHeight: 34
                Chip { text: Style.t("すべて", "All"); on: page.selected === ""; onClicked: { page.selected = ""; page.reload() } }
                Repeater {
                    model: page.chips
                    delegate: Chip {
                        text: modelData.title
                        on: page.selected === modelData.params
                        onClicked: { page.selected = page.selected === modelData.params ? "" : modelData.params; page.reload() }
                    }
                }
        }

        // 最近聴いたもの（4列の格子。押すと開く）
        // 列の数は幅で決める（1つ 200px 以上。狭いと名前が単語の途中で折れていた）。各列は同じ幅
        GridLayout {
            id: recentGrid
            visible: page.recent.length > 0
            Layout.fillWidth: true
            columns: Math.max(1, Math.min(4, Math.floor((width + 8) / 208)))
            columnSpacing: Style.space.s2
            rowSpacing: Style.space.s2
            Repeater {
                model: page.recent
                delegate: Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredWidth: 1
                    Layout.preferredHeight: 56
                    radius: Style.small
                    color: tileHover.hovered ? Qt.lighter(Style.raised, 1.15) : Style.raised
                    clip: true
                    RowLayout {
                        anchors.fill: parent
                        spacing: Style.space.s3
                        Art { Layout.preferredWidth: 56; Layout.preferredHeight: 56; source: modelData.thumb; round: !!modelData.round; radius: 0 }
                        Label { Layout.fillWidth: true; Layout.preferredWidth: 1; Layout.rightMargin: Style.space.s3; text: modelData.title; font.weight: Font.DemiBold; wrapMode: Text.WordWrap; maximumLineCount: 2; elide: Text.ElideRight; color: Style.foreground }
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
            spacing: Style.space.s2
            Label { text: page.expired ? Style.t("ログインが切れています", "Your sign-in has expired") : page.params !== "" ? Style.t("読み込めませんでした", "Couldn't load") : Style.t("ホームを読み込めませんでした", "Couldn't load Home"); font.pixelSize: 18; font.weight: Font.Bold; Layout.alignment: Qt.AlignHCenter; color: Style.foreground }
            Label { visible: page.expired; text: Style.t("YouTube Music が保存したログインを受け付けませんでした。\nログインし直してください。", "YouTube Music didn't accept your saved sign-in.\nPlease sign in again."); wrapMode: Text.Wrap; horizontalAlignment: Text.AlignHCenter; Layout.maximumWidth: 420; Layout.alignment: Qt.AlignHCenter; color: Style.dim }
            Btn { visible: page.expired; text: Style.t("ログインし直す", "Sign in again"); highlighted: true; Layout.alignment: Qt.AlignHCenter; onClicked: WaveApp.relogin() }
            Btn { text: Style.t("もう一度", "Retry"); Layout.alignment: Qt.AlignHCenter; onClicked: page.reload() }
        }
    }
}
