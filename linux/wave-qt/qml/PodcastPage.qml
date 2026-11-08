import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// ポッドキャストの番組のページ（YouTube の MPSP… と RSS の rss:…）と「新しいエピソード」（GTK 版・Mac の PodcastPage と同じ）:
// 上に画像と名前・作り手・説明（3行で切り、押すと全部）・再生・保存（YouTube）か登録（RSS）、下にエピソード（続きは下の端で読む）。
ListView {
    id: page
    property string itemId: ""
    property string pageTitle: ""
    property string pageKind: "podcast"
    property var info: null
    property var episodes: []
    property string continuation: ""
    property bool loading: true
    property bool failed: false
    property bool expanded: false
    property var saved: null
    property int token: -1
    property int saveToken: -1

    clip: true
    boundsBehavior: Flickable.StopAtBounds
    // 見出しが後から伸びても、頭から見せる（今の行に寄せない）
    currentIndex: -1
    ScrollBar.vertical: ScrollBar {}
    model: episodes
    bottomMargin: Style.pageBottom
    Component.onCompleted: token = WaveApp.request("podcast", itemId, pageTitle)
    onAtYEndChanged: if (atYEnd && continuation !== "" && !loading) { loading = true; token = WaveApp.request("podcast.more", itemId, pageTitle + "\n" + continuation) }

    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            const v = JSON.parse(json)
            if (t === page.saveToken) { if (v.ok) page.saved = !page.saved; return }
            if (t !== page.token) return
            page.loading = false
            if (v.error) { if (!page.info) page.failed = true; return }
            const first = !page.info
            if (first) { page.info = v; page.saved = v.saved }
            page.episodes = page.episodes.concat(v.episodes)
            page.continuation = v.continuation || ""
            if (first) Qt.callLater(() => page.positionViewAtBeginning())
        }
    }

    header: ColumnLayout {
        width: page.width
        spacing: 16
        BusyIndicator { visible: !page.info && !page.failed; running: visible; Layout.alignment: Qt.AlignHCenter; Layout.topMargin: 24 }
        Label { visible: page.failed; text: "ポッドキャストを読み込めませんでした"; color: Style.dim; Layout.alignment: Qt.AlignHCenter; Layout.topMargin: 24 }
        RowLayout {
            visible: !!page.info
            Layout.fillWidth: true
            Layout.margins: 24
            spacing: 24
            Art { Layout.preferredWidth: 200; Layout.preferredHeight: 200; Layout.alignment: Qt.AlignTop; source: page.info ? page.info.art : ""; radius: Style.radius; placeholder: "audio-input-microphone-symbolic" }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 6
                Label { text: page.info && page.info.newEpisodes ? "プレイリスト • 自動" : "ポッドキャスト"; font.pixelSize: 12; font.weight: Font.DemiBold; color: Style.dim }
                Label { Layout.fillWidth: true; text: page.info ? page.info.title : page.pageTitle; font.pixelSize: 32; font.weight: Font.Bold; wrapMode: Text.Wrap; maximumLineCount: 2; elide: Text.ElideRight; color: Style.foreground }
                Label { text: page.info ? page.info.author : ""; visible: text !== ""; font.weight: Font.DemiBold; color: Style.foreground }
                Label {
                    Layout.fillWidth: true
                    visible: text !== ""
                    text: page.info ? page.info.description : ""
                    wrapMode: Text.Wrap
                    maximumLineCount: page.expanded ? 1000 : 3
                    elide: Text.ElideRight
                    color: Style.dim
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: page.expanded = !page.expanded }
                    ToolTip.visible: false
                }
                RowLayout {
                    Layout.topMargin: 8
                    spacing: 8
                    PlayButton { visible: page.episodes.length > 0; onClicked: WaveApp.playSongs(JSON.stringify(page.episodes), 0, page.itemId, false) }
                    Btn {
                        visible: !!page.info && page.info.canSave
                        text: page.saved ? (page.info.isFeed ? "登録済み" : "保存済み") : (page.info && page.info.isFeed ? "登録する" : "ライブラリに保存")
                        onClicked: page.saveToken = WaveApp.setPodcastSaved(JSON.stringify(page.info.show), !page.saved)
                    }
                    Btn { visible: !!page.info && page.info.newEpisodes; text: "RSS の番組を探して足す"; onClicked: Style.nav.openFeedSearch() }
                }
            }
        }
        Label { visible: !!page.info && page.episodes.length === 0 && !page.loading; text: "エピソードはありません"; color: Style.dim; Layout.leftMargin: 24 }
    }
    delegate: TrackRow {
        width: page.width - 32
        x: 16
        implicitHeight: 64
        song: modelData
        showAlbum: false
        onPlay: WaveApp.playSongs(JSON.stringify(page.episodes), index, page.itemId, false)
    }
    footer: BusyIndicator { width: page.width; visible: page.loading && page.episodes.length > 0; running: visible }
}
