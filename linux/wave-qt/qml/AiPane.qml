import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// 右の欄の Claude（GTK 版・Mac と同じ）: 上の段（これまでの会話・新しい会話・閉じる）・会話・頼みの例・入力の欄。
// つなぐのは設定の「Claude」。答えの中の [mm:ss] は押すとその位置へ（話している曲が鳴っているときだけ）。
Item {
    id: pane
    property var shell
    property var chat: JSON.parse(WaveApp.aiJson())
    readonly property var suggestions: [Style.t("この歌詞ってどういう意味だと思う？", "What do these lyrics mean?"), Style.t("これに似た曲ある？", "Any songs like this?"), Style.t("この曲から始まるプレイリストを作って", "Make a playlist from this song")]
    Connections {
        target: WaveApp
        function onAiVersionChanged() {
            pane.chat = JSON.parse(WaveApp.aiJson())
            Qt.callLater(() => list.positionViewAtEnd())
        }
    }
    function submit() {
        if (pane.chat.running) return
        const text = input.text.trim()
        if (text === "") return
        input.text = ""
        WaveApp.aiSend(text)
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: Style.space.s1
        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: Style.space.s3
            Layout.leftMargin: Style.space.s5
            Layout.rightMargin: Style.space.s5
            spacing: 6
            Label { text: Style.t("Claude に聞く", "Ask Claude"); font.weight: Font.Bold; Layout.fillWidth: true; color: Style.foreground }
            IconButton {
                iconName: "document-open-recent-symbolic"
                tip: Style.t("これまでの会話", "Past chats")
                enabled: pane.chat.history && pane.chat.history.length > 0
                onClicked: historyMenu.popup()
                WMenu {
                    id: historyMenu
                    Repeater {
                        model: pane.chat.history || []
                        delegate: MenuItem {
                            text: modelData.title
                            checkable: true
                            checked: modelData.current
                            onTriggered: WaveApp.aiOpen(modelData.id)
                        }
                    }
                    MenuSeparator {}
                    MenuItem { text: Style.t("会話を削除", "Delete chat"); onTriggered: { const c = (pane.chat.history || []).find(h => h.current); if (c) WaveApp.aiDelete(c.id) } }
                }
            }
            Btn { text: Style.t("新しい会話", "New chat"); leftPadding: Style.space.s3; rightPadding: Style.space.s3; implicitHeight: 36; onClicked: WaveApp.aiClear() }
            IconButton { iconName: "window-close-symbolic"; tip: Style.t("閉じる", "Close"); size: 16; onClicked: pane.shell.closeAi() }
        }
        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.leftMargin: Style.space.s4
            Layout.rightMargin: Style.space.s5
            clip: true
            spacing: 10
            model: pane.chat.messages || []
            ScrollBar.vertical: ScrollBar {}
            header: Label {
                width: ListView.view.width
                visible: (pane.chat.messages || []).length === 0
                topPadding: Style.space.s2
                bottomPadding: Style.space.s2
                wrapMode: Text.Wrap
                font.pixelSize: 12
                color: Style.dim
                text: Style.t("曲探し・プレイリスト作りを頼めます。再生中の曲について、歌詞の意味や似た曲も聞けます。", "Ask Claude to find music, make a playlist, or tell you about the song that's playing — what the lyrics mean, similar songs, and more.") + (pane.chat.configured ? "" : Style.t("\n設定の「Claude」でつなぐと使えます。", "\nConnect it in Settings → Claude to use it."))
            }
            delegate: Loader {
                width: ListView.view.width
                sourceComponent: modelData.kind === "user" ? userBubble : modelData.kind === "songs" ? songs : textBlock
                property var message: modelData
            }
            footer: BusyIndicator { width: ListView.view.width; visible: pane.chat.running; running: visible; height: visible ? 40 : 0 }
        }
        Column {
            Layout.fillWidth: true
            Layout.leftMargin: Style.space.s3
            Layout.rightMargin: Style.space.s4
            visible: (pane.chat.messages || []).length === 0
            Repeater {
                model: pane.suggestions
                delegate: Btn {
                    flat: true
                    text: modelData
                    width: parent.width
                    contentItem: Label { text: modelData; wrapMode: Text.Wrap; color: Style.accent; horizontalAlignment: Text.AlignLeft }
                    onClicked: WaveApp.aiSend(modelData)
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Style.space.s4
            Layout.rightMargin: Style.space.s5
            Layout.bottomMargin: Style.space.s3
            spacing: Style.space.s2
            TextArea {
                id: input
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(140, Math.max(44, implicitHeight))
                wrapMode: TextEdit.Wrap
                verticalAlignment: TextEdit.AlignVCenter
                leftPadding: Style.space.s3
                rightPadding: Style.space.s3
                topPadding: Style.space.s3
                bottomPadding: Style.space.s3
                background: Rectangle { radius: Style.radius; color: Style.raised; border.color: input.activeFocus ? Style.accent : Style.border }
                // 空のときの案内（Material の浮く案内は枠の外に出るので、自分で置く）
                Label { x: 12; anchors.verticalCenter: parent.verticalCenter; visible: input.text === "" && !input.inputMethodComposing; text: Style.t("Claude に聞く", "Ask Claude"); color: Style.dim }
                // Enter で送り、Shift+Enter で改行（変換中の Enter は確定）
                Keys.onReturnPressed: (event) => {
                    if (event.modifiers & Qt.ShiftModifier || input.inputMethodComposing) { event.accepted = false; return }
                    pane.submit()
                }
            }
            PlayButton {
                implicitWidth: 44
                implicitHeight: 44
                iconName: pane.chat.running ? "media-playback-stop-symbolic" : "mail-send-symbolic"
                tip: pane.chat.running ? Style.t("止める", "Stop") : Style.t("送る", "Send")
                onClicked: pane.chat.running ? WaveApp.aiStop() : pane.submit()
            }
        }
    }

    Component {
        id: userBubble
        Item {
            implicitHeight: bubble.height
            Rectangle {
                id: bubble
                anchors.right: parent.right
                width: Math.min(parent.width * 0.85, text.implicitWidth + 24)
                height: text.implicitHeight + 16
                radius: Style.radius
                color: Style.accent
                Label { id: text; x: 12; y: 8; width: parent.width - 24; text: message.text; wrapMode: Text.Wrap; color: Style.accentText }
            }
        }
    }
    Component {
        id: textBlock
        Label {
            text: message.kind === "assistant" ? message.html : message.text
            textFormat: message.kind === "assistant" ? Text.StyledText : Text.PlainText
            wrapMode: Text.Wrap
            linkColor: Style.accent
            font.pixelSize: message.kind === "action" ? 12 : 14
            color: message.kind === "failure" ? Style.red : message.kind === "action" ? Style.dim : Style.foreground
            onLinkActivated: (link) => WaveApp.aiSeek(message.videoId, parseInt(link))
            HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
        }
    }
    Component {
        id: songs
        Column {
            Repeater {
                model: message.songs
                delegate: TrackRow {
                    width: parent.width
                    song: modelData
                    showAlbum: false
                    onPlay: WaveApp.playSongs(JSON.stringify(message.songs), index, "", false)
                }
            }
        }
    }
}
