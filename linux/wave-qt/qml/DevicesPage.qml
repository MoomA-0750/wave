import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Controls.impl
import QtQuick.Layouts
import wave

// 端末の連携のページ（GTK 版・Mac の DevicesPageView と同じ並び）: 知らせの帯・ほかの端末で鳴っている曲・はじめる（家に加わる）・
// 加入の手順（QR と6桁）・連携している端末・中継。
Flickable {
    id: page
    property string pageKind: "devices"
    property string pageTitle: Style.t("端末の連携", "Devices")
    property var d: JSON.parse(WaveApp.devicesJson())
    contentHeight: column.implicitHeight + Style.pageBottom
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}
    Connections {
        target: WaveApp
        function onDevicesVersionChanged() { page.d = JSON.parse(WaveApp.devicesJson()) }
    }
    // ページを離れたら、途中の加入の手順はやめる（相手に6桁を見せている間は続ける）
    Component.onDestruction: {
        const kind = page.d.pairing ? page.d.pairing.kind : "idle"
        if (!["show", "idle", "done", "failed"].includes(kind)) WaveApp.connectAction("cancel_pairing", "")
    }

    ColumnLayout {
        id: column
        x: Math.max(24, (page.width - 760) / 2)
        y: 24
        width: Math.min(760, page.width - 48)
        spacing: 16

        Label { text: Style.t("端末の連携", "Devices"); font.pixelSize: 28; font.weight: Font.Bold; color: Style.foreground }
        Label { Layout.fillWidth: true; wrapMode: Text.Wrap; color: Style.dim; text: Style.t("同じアカウントのスマホと PC のあいだで、再生を引き継げます。端末どうしの通信は暗号化され、中継のサーバーは中身を読めません。", "Move playback between your phone and PC on the same account. Traffic between devices is encrypted, so the relay server can't read it.") }

        // 知らせの帯
        Rectangle {
            visible: !!page.d.banner
            Layout.fillWidth: true
            Layout.preferredHeight: bannerRow.implicitHeight + 24
            radius: Style.radius
            readonly property color tone: !page.d.banner ? Style.accent : page.d.banner.severity === "error" ? Style.red : page.d.banner.severity === "warning" ? Style.yellow : Style.accent
            color: Qt.rgba(tone.r, tone.g, tone.b, 0.14)
            RowLayout {
                id: bannerRow
                anchors.fill: parent
                anchors.margins: 12
                spacing: 12
                IconImage {
                    source: Style.icon(page.d.banner && page.d.banner.severity !== "info" ? "dialog-warning-symbolic" : "dialog-information-symbolic")
                    sourceSize.width: 20
                    sourceSize.height: 20
                    color: parent.parent.tone
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Label { Layout.fillWidth: true; text: page.d.banner ? page.d.banner.title : ""; font.weight: Font.Bold; wrapMode: Text.Wrap; color: Style.foreground }
                    Label { Layout.fillWidth: true; text: page.d.banner ? page.d.banner.message : ""; visible: text !== ""; wrapMode: Text.Wrap; font.pixelSize: 12; color: Style.dim }
                }
                Btn { visible: !!page.d.banner && page.d.banner.action !== ""; text: page.d.banner ? page.d.banner.actionLabel : ""; onClicked: WaveApp.connectAction(page.d.banner.action, "") }
            }
        }

        // ほかの端末で鳴っている曲
        Card_ {
            visible: !!page.d.mirror
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Art { Layout.preferredWidth: 56; Layout.preferredHeight: 56; source: page.d.mirror ? page.d.mirror.song.thumb : "" }
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2
                    Label { text: page.d.mirror ? page.d.mirror.status : ""; color: Style.accent; font.pixelSize: 12; font.weight: Font.Bold }
                    Label { Layout.fillWidth: true; text: page.d.mirror ? (page.d.mirror.song.title || Style.t("曲", "Song")) : ""; font.weight: Font.DemiBold; elide: Text.ElideRight; color: Style.foreground }
                    Label { Layout.fillWidth: true; text: page.d.mirror ? page.d.mirror.song.artists : ""; elide: Text.ElideRight; font.pixelSize: 12; color: Style.dim }
                }
                Btn { text: Style.t("この PC で再生", "Play on this PC"); highlighted: true; onClicked: WaveApp.connectAction("transfer_here", "") }
            }
        }

        // はじめる（家に加わる・新しく始める）
        Card_ {
            visible: !!page.d.showJoin
            Label { text: Style.t("はじめる", "Get started"); font.weight: Font.Bold; color: Style.foreground }
            Label { Layout.fillWidth: true; wrapMode: Text.Wrap; color: Style.foreground; text: Style.t("連携済みのスマホの wave で「端末の連携」→「端末を追加」を押し、「文字で共有」で送った文字列をここに貼り付けてください。", "In wave on a phone that's already set up, go to Devices → Add device, choose “Share as text”, and paste the text here.") }
            TextField { id: offer; Layout.fillWidth: true; placeholderText: "wave-pair:…"; background: Rectangle { radius: Style.small; color: Style.view; border.color: offer.activeFocus ? Style.accent : Style.border } }
            RowLayout {
                spacing: 8
                Btn { text: Style.t("加わる", "Join"); highlighted: true; enabled: offer.text.trim() !== ""; onClicked: WaveApp.connectAction("accept", offer.text) }
                Btn { text: Style.t("この PC で新しく始める", "Start a new group on this PC"); onClicked: WaveApp.connectAction("create_household", "") }
            }
        }

        // 加入の手順
        Card_ {
            visible: !!page.d.pairing && page.d.pairing.kind !== "idle"
            Label { text: Style.t("端末を追加", "Add device"); font.weight: Font.Bold; color: Style.foreground }
            Label { Layout.fillWidth: true; wrapMode: Text.Wrap; text: page.d.pairing ? page.d.pairing.text : ""; color: page.d.pairing && page.d.pairing.kind === "failed" ? Style.red : Style.foreground }
            QrCode { visible: !!page.d.pairing && page.d.pairing.kind === "offer"; rows: page.d.pairing ? page.d.pairing.qr : []; Layout.preferredWidth: 220; Layout.preferredHeight: 220 }
            RowLayout {
                visible: !!page.d.pairing && page.d.pairing.kind === "offer"
                Layout.fillWidth: true
                Label { Layout.fillWidth: true; text: page.d.pairing ? page.d.pairing.offer : ""; elide: Text.ElideMiddle; font.family: "monospace"; font.pixelSize: 12; color: Style.dim }
                Btn { text: Style.t("文字列をコピー", "Copy text"); onClicked: { copyHelper.text = page.d.pairing.offer; copyHelper.selectAll(); copyHelper.copy(); WaveApp.toast(Style.t("コピーしました", "Copied")) } }
                TextEdit { id: copyHelper; visible: false }
            }
            Label {
                visible: !!page.d.pairing && page.d.pairing.kind === "show"
                text: page.d.pairing ? page.d.pairing.code : ""
                font.pixelSize: 44
                font.weight: Font.DemiBold
                font.letterSpacing: 8
                color: Style.foreground
            }
            RowLayout {
                visible: !!page.d.pairing && page.d.pairing.kind === "code"
                spacing: 8
                TextField {
                    id: code
                    implicitWidth: 180
                    placeholderText: Style.t("6桁の数字", "6-digit code")
                    inputMethodHints: Qt.ImhDigitsOnly
                    validator: RegularExpressionValidator { regularExpression: /[0-9]{0,6}/ }
                    background: Rectangle { radius: Style.small; color: Style.view; border.color: code.activeFocus ? Style.accent : Style.border }
                }
                Btn { text: Style.t("確認", "Confirm"); highlighted: true; enabled: code.text.length === 6; onClicked: if (WaveApp.connectEnterCode(code.text)) code.text = "" }
            }
            BusyIndicator { visible: !!page.d.pairing && page.d.pairing.kind === "working"; running: visible }
            RowLayout {
                spacing: 8
                Btn { visible: !!page.d.pairing && page.d.pairing.kind === "show"; text: Style.t("相手に入力したら、続ける", "Continue after entering it"); highlighted: true; onClicked: WaveApp.connectAction("confirm_on_b", "") }
                Btn { visible: !!page.d.pairing && page.d.pairing.grantId !== "" && !!page.d.pairing.grantLabel; text: Style.t("ログインを渡す", "Give login"); highlighted: true; onClicked: grant.ask(page.d.pairing.grantId, page.d.pairing.grantLabel) }
                Btn { text: page.d.pairing && (page.d.pairing.kind === "done" || page.d.pairing.kind === "failed") ? Style.t("閉じる", "Close") : Style.t("キャンセル", "Cancel"); onClicked: WaveApp.connectAction("cancel_pairing", "") }
            }
        }

        // 連携している端末
        Card_ {
            visible: !!page.d.inHousehold
            RowLayout {
                Layout.fillWidth: true
                Label { text: Style.t("連携している端末", "Your devices"); font.weight: Font.Bold; Layout.fillWidth: true; color: Style.foreground }
                Btn { text: Style.t("端末を追加", "Add device"); enabled: !page.d.pairing || page.d.pairing.kind === "idle"; onClicked: WaveApp.connectAction("start_offer", "") }
            }
            Repeater {
                model: page.d.members || []
                delegate: RowLayout {
                    Layout.fillWidth: true
                    spacing: 12
                    IconImage { source: Style.icon(modelData.icon); sourceSize.width: 20; sourceSize.height: 20; color: Style.foreground; Layout.preferredWidth: 28 }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 0
                        Label { Layout.fillWidth: true; text: modelData.label; elide: Text.ElideRight; color: Style.foreground }
                        Label { text: modelData.status; font.pixelSize: 12; color: modelData.status === Style.t("再生中の端末", "Active device") ? Style.accent : Style.dim }
                    }
                    Btn { visible: modelData.canGrant; text: Style.t("ログインを渡す", "Give login"); onClicked: grant.ask(modelData.id, modelData.name) }
                    Btn { visible: !modelData.self; text: Style.t("外す", "Remove"); onClicked: remove.ask(modelData.id, modelData.name) }
                }
            }
        }

        Label { text: page.d.relay || ""; color: Style.dim; Layout.topMargin: 8 }
        // 中継の場所
        Card_ {
            Label { text: Style.t("中継の場所（wss://ホスト:ポート）", "Relay address (wss://host:port)"); font.weight: Font.Bold; color: Style.foreground }
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                TextField {
                    id: relay
                    Layout.fillWidth: true
                    text: page.d.relayUrl || ""
                    placeholderText: "wss://…"
                    background: Rectangle { radius: Style.small; color: Style.view; border.color: relay.activeFocus ? Style.accent : Style.border }
                    onAccepted: WaveApp.connectAction("set_relay", text)
                }
                Btn { text: Style.t("保存", "Save"); onClicked: WaveApp.connectAction("set_relay", relay.text) }
            }
        }
    }

    WDialog {
        id: remove
        property string deviceId: ""
        property string label: ""
        function ask(id, name) { deviceId = id; label = name; open() }
        title: Style.t("端末を外しますか？", "Remove this device?")
        Label { width: 440; wrapMode: Text.Wrap; text: Style.ja ? "「" + remove.label + "」は、もう再生を引き継げなくなります。もう一度加えるには、QR と6桁の手順が要ります。" : "“" + remove.label + "” won't be able to take over playback anymore. To add it again, you'll need the QR code and 6-digit steps." }
        footer: DialogButtonBox {
            background: null
            Btn { text: Style.t("キャンセル", "Cancel"); flat: true; DialogButtonBox.buttonRole: DialogButtonBox.RejectRole }
            Btn { text: Style.t("外す", "Remove"); highlighted: true; DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole }
        }
        onAccepted: WaveApp.connectRemove(deviceId, label)
    }
    WDialog {
        id: grant
        property string deviceId: ""
        property string label: ""
        function ask(id, name) { deviceId = id; label = name; open() }
        title: Style.t("ログインを渡しますか？", "Give your login?")
        Label { width: 440; wrapMode: Text.Wrap; text: Style.ja ? "「" + grant.label + "」に、この端末の YouTube のログインを渡します。ほかのアプリ（home-dashboard など）から、あなたのプレイリスト・高く評価した曲・おすすめを使えるようになります。wave API を動かしている機械に入れる人は、それらを読めます。" : "This gives this device's YouTube login to “" + grant.label + "”. Other apps (such as home-dashboard) can then use your playlists, liked songs and recommendations. Anyone who can get into the machine running wave API can read them." }
        footer: DialogButtonBox {
            background: null
            Btn { text: Style.t("キャンセル", "Cancel"); flat: true; DialogButtonBox.buttonRole: DialogButtonBox.RejectRole }
            Btn { text: Style.t("渡す", "Give"); highlighted: true; DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole }
        }
        onAccepted: WaveApp.connectGrant(deviceId, label)
    }

    // カード（見出しの帯・カードの色、角は Hyprland の丸み）
    component Card_: Rectangle {
        default property alias content: inner.data
        Layout.fillWidth: true
        Layout.preferredHeight: inner.implicitHeight + 32
        radius: Style.radius
        color: Style.raised
        border.color: Style.border
        ColumnLayout { id: inner; x: 16; y: 16; width: parent.width - 32; spacing: 12 }
    }
}
