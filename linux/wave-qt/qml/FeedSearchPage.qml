import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import wave

// RSS の番組を探して足すページ（GTK 版と同じ）: 番組名で探すか、RSS の URL を入れる（URL ならその番組を開く）。
Flickable {
    id: page
    property string pageKind: "feed-search"
    property string pageTitle: "RSS の番組を探す"
    property var results: []
    property string message: ""
    property bool busy: false
    property int token: -1
    contentHeight: column.implicitHeight + Style.pageBottom
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== page.token) return
            page.busy = false
            const v = JSON.parse(json)
            if (v.open) { Style.nav.openPodcast(v.open, "RSS の番組"); return }
            if (v.error) { page.message = "探せませんでした（通信を確かめてください）"; page.results = []; return }
            page.results = v.items
            page.message = v.items.length === 0 ? "見つかりませんでした" : ""
        }
    }
    ColumnLayout {
        id: column
        x: 24
        y: 24
        width: page.width - 48
        spacing: 12
        Label { text: "RSS の番組を探す"; font.pixelSize: 28; font.weight: Font.Bold; color: Style.foreground }
        Label { Layout.fillWidth: true; wrapMode: Text.Wrap; color: Style.dim; text: "番組名で探すか、RSS の URL（https://…）を入れてください。YouTube に無い番組も聴けます" }
        TextField {
            id: field
            Layout.fillWidth: true
            placeholderText: "番組名・RSS の URL"
            background: Rectangle { radius: Style.small; color: Style.raised; border.color: field.activeFocus ? Style.accent : Style.border }
            onAccepted: { if (text.trim() === "") return; page.busy = true; page.token = WaveApp.request("feed.search", text.trim(), "") }
        }
        BusyIndicator { visible: page.busy; running: visible }
        Label { visible: page.message !== ""; text: page.message; color: Style.dim }
        Flow {
            Layout.fillWidth: true
            spacing: 16
            Repeater { model: page.results; delegate: Card { item: modelData; size: 160 } }
        }
    }
}
