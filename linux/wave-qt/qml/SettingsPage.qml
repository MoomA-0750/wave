import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import wave

// 設定のページ（GTK 版と同じ並び）: アカウント・再生・ダウンロード・Discord・言語・このアプリについて。
// 値は ~/.config/wave/settings.json（GTK 版と同じファイル・同じ形）。
Flickable {
    id: page
    property string pageKind: "settings"
    property string pageTitle: "設定"
    property var s: JSON.parse(WaveApp.settingsJson())
    property var account: ({ loggedIn: WaveApp.loggedIn, premium: null })
    property int token: -1
    property int pairToken: -1
    property var ai: JSON.parse(WaveApp.aiJson())
    contentHeight: column.implicitHeight + 64
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar {}

    function set(key, value) { WaveApp.setSetting(key, JSON.stringify(value)) }
    Component.onCompleted: token = WaveApp.request("account", "", "")
    Connections {
        target: WaveApp
        function onSettingsVersionChanged() { page.s = JSON.parse(WaveApp.settingsJson()) }
        function onLoaded(t, json) {
            if (t === page.token) page.account = JSON.parse(json)
            if (t === page.pairToken && !JSON.parse(json).ok) WaveApp.toast("接続の文字列の形が違います")
        }
        function onAiVersionChanged() { page.ai = JSON.parse(WaveApp.aiJson()) }
    }

    ColumnLayout {
        id: column
        x: Math.max(24, (page.width - 720) / 2)
        y: 24
        width: Math.min(720, page.width - 48)
        spacing: 24

        Label { text: "設定"; font.pixelSize: 28; font.weight: Font.Bold; color: Style.foreground }

        Group {
            title: "アカウント"
            Row_ {
                title: WaveApp.loggedIn ? (WaveApp.accountName !== "" ? WaveApp.accountName : "ログイン中") : "ログインしていません"
                subtitle: !WaveApp.loggedIn ? "ログインすると、ライブラリと評価を使えます"
                          : page.account.premium === true ? "Premium の音質を使えます"
                          : page.account.premium === false ? "Premium の音質を使えません（アプリはそのまま使えます）" : "Premium かどうかを確かめています…"
                Btn { text: WaveApp.loggedIn ? "ログアウト" : "ログイン"; onClicked: WaveApp.logOut() }
            }
        }
        Group {
            title: "再生"
            SwitchRow { title: "再生した曲を YouTube Music の再生履歴に残す"; subtitle: "オフにすると、wave で聴いた曲は履歴やおすすめに使われません"; checked: page.s.send_history; onToggled: page.set("send_history", checked) }
            SwitchRow { title: "新作の通知"; subtitle: "登録したアーティストのアルバム・シングル・動画が出たら知らせる（wave が動いている間、6時間おきに確かめる）"; checked: page.s.release_notices; onToggled: page.set("release_notices", checked) }
        }
        Group {
            title: "ダウンロード"
            SwitchRow { title: "スマートダウンロード"; subtitle: "よく聴く曲を自動で取っておき、通信が無くても聴けるようにする"; checked: page.s.smart_downloads; onToggled: page.set("smart_downloads", checked) }
            Row_ {
                title: "ダウンロードに使う通信"
                ComboBox {
                    implicitWidth: 300
                    Material.roundedScale: Style.radius
                    model: ["従量制でない通信だけ", "どの通信でも"]
                    currentIndex: page.s.download_network === "Any" ? 1 : 0
                    onActivated: (i) => page.set("download_network", i === 1 ? "Any" : "Unmetered")
                }
            }
        }
        Group {
            title: "Discord"
            SwitchRow { title: "Discord に再生中の曲を出す"; subtitle: page.s.discord_status; checked: page.s.discord_presence; onToggled: page.set("discord_presence", checked) }
            Row_ {
                title: "Application ID"
                TextField {
                    implicitWidth: 240
                    text: page.s.discord_client_id || ""
                    placeholderText: "数字"
                    validator: RegularExpressionValidator { regularExpression: /[0-9]*/ }
                    onEditingFinished: page.set("discord_client_id", text === "" ? null : text)
                }
            }
        }
        Group {
            title: "Claude"
            Row_ {
                title: page.ai.configured ? "つないでいます: " + page.ai.configured : "つないでいません"
                subtitle: "Claude の中継（ai-bridge）の「接続の文字列」（waveai://pair?…）を貼り付けてつなぎます。つなぎ先はキーリングに保存します。"
                Btn { visible: !!page.ai.configured; text: "切る"; onClicked: WaveApp.aiUnpair() }
            }
            Row_ {
                visible: !page.ai.configured
                title: "接続の文字列"
                TextField {
                    id: pairText
                    implicitWidth: 300
                    placeholderText: "waveai://pair?…"
                    background: Rectangle { radius: Style.small; color: Style.view; border.color: pairText.activeFocus ? Style.accent : Style.border }
                }
                Btn { text: "つなぐ"; highlighted: true; enabled: pairText.text.trim() !== ""; onClicked: page.pairToken = WaveApp.aiPair(pairText.text.trim()) }
            }
        }
        Group {
            title: "言語"
            SwitchRow { title: "曲名を元の表記で"; subtitle: "曲名・アーティスト名・アルバム名を、曲の言語のまま出します（洋楽をカタカナに、邦楽をローマ字にしません）。YouTube に2回問い合わせるので、ページの表示が少し遅くなります"; checked: page.s.original_titles; onToggled: page.set("original_titles", checked) }
        }
        Group {
            title: "このアプリについて"
            Row_ { title: "wave for Linux " + page.s.version + "（QML 版）"; subtitle: "ログイン情報と Claude の接続は GNOME のキーリング（Secret Service）に保存し、ファイルには書きません。" }
            Row_ { title: "データの場所"; subtitle: page.s.data_dir }
            Row_ { title: "画面の見た目"; subtitle: Style.omarchy ? "Omarchy のテーマの色と、Hyprland の角の丸み（" + Style.radius + "px）に合わせています" : "Omarchy のテーマが見つからないので、Material の既定の色です" }
        }
    }

    // 設定の1まとまり（見出しとカード）
    component Group: ColumnLayout {
        default property alias rows: card.data
        property string title
        Layout.fillWidth: true
        spacing: 8
        Label { text: parent.title; font.weight: Font.Bold; color: Style.foreground }
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: card.implicitHeight
            radius: Style.radius
            color: Style.raised
            border.color: Style.border
            ColumnLayout { id: card; width: parent.width; spacing: 0 }
        }
    }
    component Row_: RowLayout {
        default property alias trailing: end.data
        property string title
        property string subtitle
        Layout.fillWidth: true
        Layout.margins: 14
        spacing: 12
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            Label { Layout.fillWidth: true; text: parent.parent.title; wrapMode: Text.Wrap; color: Style.foreground }
            Label { Layout.fillWidth: true; text: parent.parent.subtitle; visible: text !== ""; wrapMode: Text.Wrap; font.pixelSize: 12; color: Style.dim }
        }
        RowLayout { id: end }
    }
    component SwitchRow: Row_ {
        id: row
        property alias checked: toggle.checked
        signal toggled()
        Switch { id: toggle; onToggled: row.toggled() }
    }
}
