import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import QtQuick.Controls.impl
import wave

// 上の帯（Mac・GTK 版と同じ）: 左に戻る・進む、窓の左右の中央にホームと検索の欄、右端にアカウント。
// 戻る・進むは押せるときだけ出す（進めるときは2つとも。戻れなければ戻るは薄く押せなくする）。
Rectangle {
    id: bar
    property var shell
    implicitHeight: 52
    color: Style.background

    function focusSearch() { field.forceActiveFocus(); field.selectAll() }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 8
        anchors.rightMargin: 12
        spacing: 4
        IconButton {
            iconName: "go-previous-symbolic"; tip: "戻る"
            visible: bar.shell.canBack || bar.shell.canForward
            enabled: bar.shell.canBack
            onClicked: bar.shell.back()
        }
        IconButton {
            iconName: "go-next-symbolic"; tip: "進む"
            visible: bar.shell.canForward
            onClicked: bar.shell.forward()
        }
        Item { Layout.fillWidth: true }
    }

    // ホームと検索の欄（窓の左右の中央）
    RowLayout {
        anchors.centerIn: parent
        width: Math.min(560, bar.width - 280)
        spacing: 8
        RoundButton {
            Layout.preferredWidth: 40
            Layout.preferredHeight: 40
            flat: bar.shell.visibleKind !== "home"
            icon.source: Style.icon("go-home-symbolic")
            icon.color: Style.foreground
            Material.roundedScale: Style.radius === 0 ? Material.NotRounded : Material.FullScale
            Material.background: Style.raised
            Material.elevation: 0
            ToolTip.visible: hovered
            ToolTip.text: "ホーム"
            onClicked: bar.shell.goHome()
        }
        TextField {
            id: field
            Layout.fillWidth: true
            Layout.preferredHeight: 40
            placeholderText: "何を聴きたいですか？"
            leftPadding: 40
            topPadding: 0
            bottomPadding: 0
            verticalAlignment: TextInput.AlignVCenter
            Material.containerStyle: Material.Filled
            background: Rectangle { radius: Style.radius === 0 ? 0 : height / 2; color: Style.raised; border.color: field.activeFocus ? Style.accent : "transparent"; border.width: 2 }
            IconImage {
                anchors.left: parent.left
                anchors.leftMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                source: Style.icon("system-search-symbolic")
                sourceSize.width: 16
                sourceSize.height: 16
                color: Style.dim
            }
            onTextEdited: suggest.restart()
            onAccepted: {
                popup.close()
                if (text.trim() === "") return
                WaveApp.rememberSearch(JSON.stringify({ kind: "query", title: text.trim() }))
                bar.shell.openSearch(text.trim())
            }
            Keys.onDownPressed: if (popup.opened) list.forceActiveFocus()
            Keys.onEscapePressed: popup.close()
        }
    }

    // アカウント（写真）。押すと設定・ログアウト
    RoundButton {
        id: account
        anchors.right: parent.right
        anchors.rightMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        width: 40
        height: 40
        flat: true
        padding: 0
        contentItem: Art {
            source: WaveApp.accountPhoto
            round: true
            placeholder: "avatar-default-symbolic"
        }
        ToolTip.visible: hovered
        ToolTip.text: WaveApp.accountName !== "" ? WaveApp.accountName : "アカウント"
        onClicked: accountMenu.popup(account, 0, account.height)
        Menu {
            id: accountMenu
            MenuItem { text: WaveApp.accountName !== "" ? WaveApp.accountName : "ログインしていません"; enabled: false }
            MenuSeparator {}
            MenuItem { text: "設定"; onTriggered: bar.shell.openSettings() }
            MenuItem { text: "再生履歴"; enabled: WaveApp.loggedIn; onTriggered: bar.shell.push(historyPage, {}) }
            MenuItem { text: WaveApp.loggedIn ? "ログアウト" : "ログイン"; onTriggered: WaveApp.loggedIn ? confirm.open() : WaveApp.logOut() }
        }
    }

    Dialog {
        id: confirm
        parent: Overlay.overlay
        anchors.centerIn: parent
        modal: true
        title: "ログアウトしますか？"
        Label { width: 360; wrapMode: Text.Wrap; text: "この PC のキーリングからログインの情報を消し、取った曲・キュー・検索の履歴・Claude との会話も消します。" }
        standardButtons: Dialog.Cancel | Dialog.Ok
        onAccepted: WaveApp.logOut()
    }

    Component {
        id: historyPage
        Page_ {}
    }
    component Page_: CollectionPage { kind: "history"; pageTitle: "再生履歴" }

    // 打ち込み中の候補（語と、曲・アーティスト・アルバムの行）
    property int suggestToken: -1
    property var suggestions: ({ queries: [], items: [] })
    Timer {
        id: suggest
        interval: 220
        onTriggered: {
            const q = field.text.trim()
            if (q === "") { popup.close(); return }
            bar.suggestToken = WaveApp.request("suggestions", q, "")
        }
    }
    Connections {
        target: WaveApp
        function onLoaded(token, json) {
            if (token !== bar.suggestToken) return
            const value = JSON.parse(json)
            if (value.error) return
            bar.suggestions = value
            if (field.activeFocus && (value.queries.length + value.items.length) > 0) popup.open()
        }
    }
    Popup {
        id: popup
        y: bar.height - 4
        x: (bar.width - width) / 2 + 24
        width: Math.min(560, bar.width - 280) - 48
        padding: 6
        background: Rectangle { color: Style.raised; radius: Style.radius; border.color: Style.border }
        contentItem: ListView {
            id: list
            implicitHeight: Math.min(contentHeight, 420)
            clip: true
            model: bar.suggestions.queries.map(q => ({ kind: "query", title: q })).concat(bar.suggestions.items)
            delegate: ItemDelegate {
                width: ListView.view.width
                height: 48
                contentItem: RowLayout {
                    spacing: 12
                    Art {
                        Layout.preferredWidth: 32
                        Layout.preferredHeight: 32
                        source: modelData.thumb || ""
                        round: !!modelData.round
                        placeholder: modelData.kind === "query" ? "system-search-symbolic" : "folder-music-symbolic"
                    }
                    ColumnLayout {
                        spacing: 0
                        Layout.fillWidth: true
                        Label { text: modelData.title; elide: Text.ElideRight; Layout.fillWidth: true; color: Style.foreground }
                        Label { text: modelData.subtitle || ""; visible: text !== ""; elide: Text.ElideRight; Layout.fillWidth: true; font.pixelSize: 12; color: Style.dim }
                    }
                }
                onClicked: {
                    popup.close()
                    if (modelData.kind === "query") {
                        field.text = modelData.title
                        WaveApp.rememberSearch(JSON.stringify(modelData))
                        bar.shell.openSearch(modelData.title)
                    } else {
                        WaveApp.rememberSearch(JSON.stringify(modelData))
                        bar.shell.openItem(modelData)
                    }
                }
            }
        }
    }
}
