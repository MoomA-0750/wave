import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Controls.impl
import QtQuick.Layouts
import wave

// ログインしていないときの入り口（GTK 版と同じ）。「ログイン」はアプリの中の窓（GTK 版の WebKitGTK の窓を別に開く）、
// 「ブラウザのログインを使う」はふだんのブラウザに残っている music.youtube.com のログインを読む。
Item {
    id: page
    property int token: -1
    property bool busy: false

    Connections {
        target: WaveApp
        function onLoaded(t, json) {
            if (t !== page.token) return
            const v = JSON.parse(json)
            page.busy = false
            if (v.profiles !== undefined) {
                if (v.profiles.length === 0) notFound.open()
                else { chooser.profiles = v.profiles; chooser.open() }
            } else if (v.ok === false) {
                failed.title = v.title
                failedText.text = v.message
                failed.open()
            }
        }
    }

    ColumnLayout {
        anchors.centerIn: parent
        spacing: 12
        IconImage { Layout.alignment: Qt.AlignHCenter; source: Style.icon("audio-x-generic-symbolic"); sourceSize.width: 96; sourceSize.height: 96; color: Style.dim }
        Label { Layout.alignment: Qt.AlignHCenter; text: "wave"; font.pixelSize: 32; font.weight: Font.Bold; color: Style.foreground }
        Label { Layout.alignment: Qt.AlignHCenter; text: "YouTube Music のアカウントでログインしてください"; color: Style.dim }
        Button {
            Material.roundedScale: Style.radius
            Layout.alignment: Qt.AlignHCenter
            Layout.topMargin: 12
            Layout.preferredWidth: 260
            text: "ログイン"
            highlighted: true
            enabled: !page.busy
            onClicked: WaveApp.openLoginWindow()
        }
        Button {
            Material.roundedScale: Style.radius
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: 260
            text: "ブラウザのログインを使う"
            enabled: !page.busy
            onClicked: { page.busy = true; page.token = WaveApp.browserProfiles() }
        }
        BusyIndicator { Layout.alignment: Qt.AlignHCenter; visible: page.busy; running: visible }
    }

    Dialog {
        id: notFound
        anchors.centerIn: parent
        modal: true
        title: "ブラウザにログインが見つかりません"
        Label { width: 420; wrapMode: Text.Wrap; text: "ふだんのブラウザで music.youtube.com にログインしてから、もう一度「ブラウザのログインを使う」を押してください。\n\n読めるブラウザ: Firefox・Zen・LibreWolf・Floorp・Waterfox・Chrome・Chromium・Brave・Edge・Vivaldi・Opera" }
        footer: DialogButtonBox {
            Button { Material.roundedScale: Style.radius; text: "閉じる"; DialogButtonBox.buttonRole: DialogButtonBox.RejectRole }
            Button { Material.roundedScale: Style.radius; text: "ブラウザで開く"; highlighted: true; DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole }
        }
        onAccepted: Qt.openUrlExternally("https://music.youtube.com/")
    }
    Dialog {
        id: chooser
        property var profiles: []
        anchors.centerIn: parent
        modal: true
        title: "どのブラウザのログインを使いますか"
        ColumnLayout {
            width: 420
            Label { Layout.fillWidth: true; wrapMode: Text.Wrap; text: "選んだブラウザの YouTube の cookie を読んで、この PC のキーリングに保存します。ブラウザの側のログインはそのまま残ります。" }
            Repeater {
                model: chooser.profiles
                delegate: Button {
                    Material.roundedScale: Style.radius
                    Layout.fillWidth: true
                    text: modelData
                    highlighted: index === 0
                    onClicked: { chooser.close(); page.busy = true; page.token = WaveApp.useBrowserProfile(index) }
                }
            }
        }
        standardButtons: Dialog.Cancel
    }
    Dialog {
        id: failed
        anchors.centerIn: parent
        modal: true
        Label { id: failedText; width: 420; wrapMode: Text.Wrap }
        standardButtons: Dialog.Close
    }
}
