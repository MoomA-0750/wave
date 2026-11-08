import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// 右の欄の再生中（GTK 版・Mac と同じ）: 見出しと閉じる、大きなジャケット、曲名（アルバムへ）とアーティスト、評価、次の曲と「キューを開く」。
Flickable {
    id: pane
    property var shell
    readonly property var next: WaveApp.nextJson !== "" ? JSON.parse(WaveApp.nextJson) : null
    contentHeight: column.implicitHeight + Style.pageBottom
    clip: true
    boundsBehavior: Flickable.StopAtBounds

    ColumnLayout {
        id: column
        x: 20
        y: 12
        width: pane.width - 40
        spacing: 14
        RowLayout {
            Layout.fillWidth: true
            Label { text: WaveApp.album !== "" ? WaveApp.album : "再生中"; font.weight: Font.Bold; elide: Text.ElideRight; Layout.fillWidth: true; color: Style.foreground }
            IconButton { iconName: "window-close-symbolic"; tip: "閉じる"; size: 16; onClicked: pane.shell.setRight(false, "") }
        }
        // 曲｜動画（今の曲に MV があるときだけ）
        Row {
            visible: WaveApp.hasVideo
            Layout.alignment: Qt.AlignHCenter
            spacing: 6
            Chip { text: "曲"; on: !WaveApp.showVideo; onClicked: WaveApp.setVideoOn(false) }
            Chip { text: "動画"; on: WaveApp.showVideo; onClicked: WaveApp.setVideoOn(true) }
        }
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: WaveApp.showVideo ? width * 9 / 16 : width
            Art { anchors.fill: parent; visible: !WaveApp.showVideo; source: WaveApp.art; radius: Style.radius }
            VideoView { anchors.fill: parent; visible: WaveApp.showVideo; place: "side" }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Label {
                    Layout.fillWidth: true
                    text: WaveApp.hasSong ? WaveApp.title : "何も再生していません"
                    font.pixelSize: 22
                    font.weight: Font.Bold
                    elide: Text.ElideRight
                    color: Style.foreground
                    font.underline: titleHover.hovered && WaveApp.albumId !== ""
                    HoverHandler { id: titleHover; cursorShape: WaveApp.albumId !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                    TapHandler { enabled: WaveApp.albumId !== ""; onTapped: Style.nav.openAlbum(WaveApp.albumId, WaveApp.album) }
                }
                Label {
                    Layout.fillWidth: true
                    text: WaveApp.artists
                    visible: text !== ""
                    elide: Text.ElideRight
                    color: Style.dim
                    font.underline: artistHover.hovered && WaveApp.artistId !== ""
                    HoverHandler { id: artistHover; cursorShape: WaveApp.artistId !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                    TapHandler { enabled: WaveApp.artistId !== ""; onTapped: Style.nav.openArtist(WaveApp.artistId, WaveApp.artists) }
                }
            }
            IconButton {
                visible: WaveApp.hasSong && WaveApp.loggedIn
                iconName: WaveApp.liked === 1 ? "object-select-symbolic" : "list-add-symbolic"
                tip: WaveApp.liked === 1 ? "高く評価した曲から削除" : "高く評価した曲に追加"
                checked: WaveApp.liked === 1
                checkable: false
                onClicked: WaveApp.toggleLike()
            }
        }
        // 流れる歌詞の箱（歌詞がある曲だけ。押すと歌詞のページ）
        Rectangle {
            visible: WaveApp.hasSong && lyricsBox.found
            Layout.fillWidth: true
            Layout.preferredHeight: 240
            radius: Style.radius
            color: Style.raised
            clip: true
            Label { id: lyricsTitle; x: 12; y: 12; text: "歌詞"; font.weight: Font.Bold; color: Style.foreground }
            LyricsFollower { id: lyricsBox; x: 12; y: lyricsTitle.y + lyricsTitle.height + 8; width: parent.width - 24; height: parent.height - y - 12 }
            // 下の端は薄く消す（行が途中で切れて見えないように）
            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: 48
                radius: Style.radius
                gradient: Gradient {
                    GradientStop { position: 0; color: Qt.rgba(Style.raised.r, Style.raised.g, Style.raised.b, 0) }
                    GradientStop { position: 1; color: Style.raised }
                }
            }
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: if (pane.shell.visibleKind !== "lyrics") pane.shell.toggleLyrics() }
        }
        // 次の曲
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: nextColumn.implicitHeight + 24
            radius: Style.radius
            color: Style.raised
            ColumnLayout {
                id: nextColumn
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: 12
                spacing: 8
                RowLayout {
                    Layout.fillWidth: true
                    Label { text: "次の曲"; font.weight: Font.Bold; Layout.fillWidth: true; color: Style.foreground }
                    Btn { text: "キューを開く"; flat: true; link: true; font.weight: Font.Bold; onClicked: pane.shell.setRight(true, "queue") }
                }
                TrackRow { visible: !!pane.next; Layout.fillWidth: true; song: pane.next || ({}); showAlbum: false; onPlay: WaveApp.next() }
            }
        }
    }
}
