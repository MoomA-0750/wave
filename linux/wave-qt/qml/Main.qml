import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import wave

// 主の窓。読み込み中・ログインの入り口・主の画面を切り替え、知らせ（トースト）を下に出す。
ApplicationWindow {
    id: window
    width: 1280
    height: 800
    minimumWidth: 720
    minimumHeight: 480
    visible: true
    title: "wave"
    color: Style.background

    Material.theme: Style.dark ? Material.Dark : Material.Light
    Material.accent: Style.accent
    Material.primary: Style.accent
    Material.background: Style.background
    Material.foreground: Style.foreground
    Material.roundedScale: Style.radius

    Component.onCompleted: WaveApp.start()

    Connections {
        target: WaveApp
        function onToast(text) { toast.show(text) }
        function onRaiseRequested() { window.show(); window.raise(); window.requestActivate() }
    }

    Loader {
        anchors.fill: parent
        sourceComponent: WaveApp.phase === "main" ? shell : WaveApp.phase === "login" ? login : loading
    }

    Component { id: loading; Item { BusyIndicator { anchors.centerIn: parent; running: true } } }
    Component { id: login; LoginPage {} }
    Component { id: shell; Shell {} }

    // 画面の下に短い知らせ
    Rectangle {
        id: toast
        property string text: ""
        function show(t) { text = t; opacity = 1; hide.restart() }
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 112
        width: Math.min(label.implicitWidth + 40, parent.width - 48)
        height: label.implicitHeight + 20
        radius: Style.radius
        color: Style.raised
        border.color: Style.border
        opacity: 0
        visible: opacity > 0
        z: 100
        Behavior on opacity { NumberAnimation { duration: 180 } }
        Label { id: label; anchors.centerIn: parent; width: parent.width - 40; text: toast.text; wrapMode: Text.Wrap; horizontalAlignment: Text.AlignHCenter; color: Style.foreground }
        Timer { id: hide; interval: 3500; onTriggered: toast.opacity = 0 }
    }
}
