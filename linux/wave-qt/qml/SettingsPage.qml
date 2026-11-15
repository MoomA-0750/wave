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
    property string pageTitle: Style.t("設定", "Settings")
    property var s: JSON.parse(WaveApp.settingsJson())
    property var account: ({ loggedIn: WaveApp.loggedIn, premium: null })
    property int token: -1
    property int pairToken: -1
    property var ai: JSON.parse(WaveApp.aiJson())
    contentHeight: column.implicitHeight + Style.pageBottom
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
            if (t === page.pairToken && !JSON.parse(json).ok) WaveApp.toast(Style.t("wave の Claude の中継の接続の文字列ではありません", "That isn't a wave Claude bridge pairing text"))
        }
        function onAiVersionChanged() { page.ai = JSON.parse(WaveApp.aiJson()) }
    }

    ColumnLayout {
        id: column
        x: Math.max(24, (page.width - 720) / 2)
        y: 24
        width: Math.min(720, page.width - 48)
        spacing: Style.space.s6

        Label { text: Style.t("設定", "Settings"); font.pixelSize: 28; font.weight: Font.Bold; color: Style.foreground }

        Group {
            title: Style.t("アカウント", "Account")
            Row_ {
                title: WaveApp.loggedIn ? (WaveApp.accountName !== "" ? WaveApp.accountName : Style.t("ログイン中", "Signed in")) : Style.t("ログインしていません", "Not signed in")
                subtitle: !WaveApp.loggedIn ? Style.t("ログインすると、ライブラリやおすすめが使えます", "Sign in to use your library and recommendations")
                          : page.account.premium === true ? Style.t("Premium 音質: 使えます", "Premium audio: Available")
                          : page.account.premium === false ? Style.t("Premium 音質: 使えません", "Premium audio: Unavailable") : Style.t("アカウントを確かめています…", "Checking account…")
                Btn { text: WaveApp.loggedIn ? Style.t("ログアウト", "Sign out") : Style.t("ログイン", "Sign in"); onClicked: WaveApp.logOut() }
            }
        }
        Group {
            title: Style.t("再生", "Play")
            SwitchRow { title: Style.t("再生した曲を YouTube Music の再生履歴に残す", "Save played songs to your YouTube Music history"); subtitle: Style.t("オフにすると、wave で聴いた曲は履歴やおすすめに使われません", "When off, songs you play in wave won't be used for history or recommendations"); checked: page.s.send_history; onToggled: page.set("send_history", checked) }
            SwitchRow { title: Style.t("新作の通知", "New release notifications"); subtitle: Style.t("登録したアーティストのアルバム・シングル・動画が出たら知らせる（wave が動いている間、6時間おきに確かめる）", "Notify when artists you subscribe to release albums, singles or videos (checked every 6 hours while wave is running)"); checked: page.s.release_notices; onToggled: page.set("release_notices", checked) }
        }
        Group {
            title: Style.t("ダウンロード", "Downloads")
            SwitchRow { title: Style.t("スマートダウンロード", "Smart downloads"); subtitle: Style.t("いつも聴いている曲・好きそうな曲・おすすめから、聴きそうな曲を毎日選び直して取っておく", "Automatically download songs you're likely to play from songs you play often, songs you might like and recommendations, refreshed daily"); checked: page.s.smart_downloads; onToggled: page.set("smart_downloads", checked) }
            Row_ {
                title: Style.t("ダウンロードに使う通信", "Download over")
                ComboBox {
                    id: networkBox
                    // 長いほうの選び肢が切れない幅（文字の幅＋左右の余白と ▾ の分）
                    implicitWidth: Math.max(220, networkText.advanceWidth + leftPadding + rightPadding + (indicator ? indicator.width : 0) + 16)
                    Material.roundedScale: Style.radius
                    TextMetrics { id: networkText; font: networkBox.font; text: Style.t("Wi-Fi など従量制でない通信だけ", "Wi-Fi and other unmetered networks only") }
                    model: [networkText.text, Style.t("どの通信でも", "Any network")]
                    currentIndex: page.s.download_network === "Any" ? 1 : 0
                    onActivated: (i) => page.set("download_network", i === 1 ? "Any" : "Unmetered")
                }
            }
        }
        Group {
            title: "Discord"
            SwitchRow { title: Style.t("Discord に再生中の曲を出す", "Show what you're playing on Discord"); subtitle: page.s.discord_status; checked: page.s.discord_presence; onToggled: page.set("discord_presence", checked) }
            Row_ {
                title: "Application ID"
                TextField {
                    implicitWidth: 240
                    text: page.s.discord_client_id || ""
                    placeholderText: Style.t("数字", "Number")
                    validator: RegularExpressionValidator { regularExpression: /[0-9]*/ }
                    onEditingFinished: page.set("discord_client_id", text === "" ? null : text)
                }
            }
        }
        Group {
            title: "Claude"
            Row_ {
                title: page.ai.configured ? (Style.ja ? page.ai.configured + " の中継につないでいます" : "Connected to " + page.ai.configured) : Style.t("家の PC の Claude Code で、歌詞の意味・似た曲・プレイリスト作りを頼めます", "Ask about lyrics, find similar songs and make playlists with Claude Code on your PC")
                subtitle: Style.t("家の PC で ai-bridge の ai_bridge.py --name wave --pair を動かし、出てきた waveai://pair?… の文字列を貼ってください", "Run ai_bridge.py --name wave --pair from ai-bridge on your PC and paste the waveai://pair?… text it prints")
                Btn { visible: !!page.ai.configured; text: Style.t("接続を外す", "Disconnect"); onClicked: WaveApp.aiUnpair() }
            }
            Row_ {
                visible: !page.ai.configured
                title: Style.t("接続の文字列", "Pairing text")
                TextField {
                    id: pairText
                    implicitWidth: 300
                    placeholderText: "waveai://pair?…"
                    background: Rectangle { radius: Style.small; color: Style.view; border.color: pairText.activeFocus ? Style.accent : Style.border }
                }
                Btn { text: Style.t("接続", "Connect"); highlighted: true; enabled: pairText.text.trim() !== ""; onClicked: page.pairToken = WaveApp.aiPair(pairText.text.trim()) }
            }
        }
        Group {
            title: Style.t("言語", "Language")
            // 画面の言語（""＝端末に合わせる・"ja"・"en"）。起動のはじめに決まるので、次の起動から効く
            Row_ {
                title: Style.t("画面の言語", "App language")
                ComboBox {
                    readonly property var values: ["", "ja", "en"]
                    implicitWidth: 240
                    Material.roundedScale: Style.radius
                    model: [Style.t("端末に合わせる", "Use device language"), "日本語", "English"] // 言語の名前はその言語のまま出す
                    currentIndex: Math.max(0, values.indexOf(page.s.ui_language || ""))
                    onActivated: (i) => {
                        if (values[i] === (page.s.ui_language || "")) return
                        page.set("ui_language", values[i])
                        WaveApp.toast(Style.t("次の起動から反映されます", "Takes effect the next time you open wave"))
                    }
                }
            }
            SwitchRow { title: Style.t("曲名を元の表記で", "Show titles as released"); subtitle: Style.t("曲名・アーティスト名・アルバム名を、曲の言語のまま出します（洋楽をカタカナに、邦楽をローマ字にしません）。YouTube に2回問い合わせるので、ページの表示が少し遅くなります", "Keep song, artist and album names in their own language (no katakana for Western songs, no romaji for Japanese songs). wave asks YouTube twice, so pages load a little slower"); checked: page.s.original_titles; onToggled: page.set("original_titles", checked) }
        }
        Group {
            title: Style.t("このアプリについて", "About")
            Row_ { title: "wave for Linux " + page.s.version + Style.t("（QML 版）", " (QML)"); subtitle: Style.t("ログイン情報と Claude の接続は GNOME のキーリング（Secret Service）に保存し、ファイルには書きません", "Your sign-in and Claude connection are stored in the GNOME keyring (Secret Service), never in files") }
            Row_ { title: Style.t("データの場所", "Data folder"); subtitle: page.s.data_dir }
            Row_ { title: Style.t("画面の見た目", "Appearance"); subtitle: Style.omarchy ? (Style.ja ? "Omarchy のテーマの色と、Hyprland の角の丸み（" + Style.radius + "px）に合わせています" : "Matches your Omarchy theme colors and Hyprland corner rounding (" + Style.radius + "px)") : Style.t("Omarchy のテーマが見つからないので、kasane の既定の色です", "No Omarchy theme found, so kasane's default colors are used") }
        }
    }

    // 設定の1まとまり（見出しとカード）
    component Group: ColumnLayout {
        default property alias rows: card.data
        property string title
        Layout.fillWidth: true
        spacing: Style.space.s2
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
        spacing: Style.space.s3
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
