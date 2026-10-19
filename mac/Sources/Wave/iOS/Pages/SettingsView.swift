#if os(iOS)
import SwiftUI
import WaveCore

/// 設定（Android の SettingsScreen・Mac の SettingsPage と同じ並び）。アカウント・再生履歴の送信・Claude・新作の通知・言語・
/// 端末の連携・ログアウト・このアプリについて。
struct SettingsView: View {
    @State private var sendHistory = AppServices.settings.sendHistory
    @State private var releaseNotices = AppServices.settings.releaseNotices
    @State private var musicPip = AppServices.settings.musicPip
    @State private var language = AppServices.settings.language
    @State private var pairText = ""
    @State private var aiConfigured = AppServices.ai.configured
    @State private var confirmLogout = false
    /// Claude の中継の接続の QR をカメラで読んでいる（Android と同じ。貼り付けも残す）。
    @State private var scanningAi = false
    @State private var account = AppServices.account!

    var body: some View {
        Form {
            accountSection
            Section {
                Toggle(isOn: $sendHistory) {
                    SettingsLabel(title: L.t("再生した曲を YouTube Music の再生履歴に残す", "Save played songs to your YouTube Music history"),
                                  detail: L.t("オフにすると、wave で聴いた曲は履歴やおすすめに使われません", "When off, songs you play in wave won’t be used for history or recommendations"))
                }
                .onChange(of: sendHistory) { _, v in
                    AppServices.settings.sendHistory = v
                    AppServices.settings.save()
                }
                Toggle(isOn: $musicPip) {
                    SettingsLabel(title: L.t("音楽の再生中も小窓を出す", "Picture in picture for music"),
                                  detail: L.t("ホームへ戻ると、曲名とジャケットを小窓に出す。小窓のボタンで再生・一時停止と、次の曲・前の曲へ",
                                              "When you leave wave, show the song and artwork in a small window. Its buttons play, pause and skip songs"))
                }
                .onChange(of: musicPip) { _, v in
                    AppServices.settings.musicPip = v
                    AppServices.settings.save()
                    MusicPip.shared.settingChanged()
                }
            } header: {
                Text(L.t("再生", "Play"))
            }
            claudeSection
            Section {
                Toggle(isOn: $releaseNotices) {
                    SettingsLabel(title: L.t("新作の通知", "New release notifications"),
                                  detail: L.t("登録したアーティストのアルバム・シングル・動画が出たら知らせる（6時間ほどおきに確かめる。閉じている間は iPhone が決めた時に）",
                                              "Notify when artists you subscribe to release albums, singles or videos (checked about every 6 hours; while wave is closed, when iPhone allows)"))
                }
                .onChange(of: releaseNotices) { _, v in
                    AppServices.settings.releaseNotices = v
                    AppServices.settings.save()
                    ReleaseRefresh.schedule()
                }
            } header: {
                Text(L.t("通知", "Notifications"))
            }
            Section {
                Picker(L.t("画面の言語", "App language"), selection: $language) {
                    Text(L.t("iPhone に合わせる", "Use iPhone language")).tag("system")
                    Text("日本語").tag("ja")
                    Text("English").tag("en")
                }
                .onChange(of: language) { _, v in
                    guard v != AppServices.settings.language else { return }
                    AppServices.settings.language = v
                    AppServices.settings.save()
                    AppServices.toast.show(L.t("次の起動から反映されます", "Takes effect the next time you open wave"))
                }
            } header: {
                Text(L.t("言語", "Language"))
            } footer: {
                Text(L.t("YouTube から届く文字（棚の見出しなど）もこの言語になります。次の起動から反映されます",
                         "Text from YouTube (such as shelf titles) also uses this language. Takes effect the next time you open wave"))
            }
            if AppServices.connect.configured {
                Section {
                    Button { AppServices.nav.devices() } label: {
                        HStack {
                            Label {
                                SettingsLabel(title: L.t("端末の連携", "Devices"),
                                              detail: L.t("ほかの端末と再生を引き継ぐ・端末を加える", "Move playback between devices and add devices"))
                            } icon: {
                                Image(systemName: "hifispeaker.2")
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            if account.isLoggedIn {
                Section {
                    // 押し間違いでログインが消えないよう、確かめてからログアウトする（Android と同じ）
                    Button(L.t("ログアウト", "Log out"), role: .destructive) { confirmLogout = true }
                }
            }
            Section {
                LabeledContent("wave for iPhone", value: version)
                // 動作の記録（件数・状態だけで、認証情報・URL は書いていない）を送れるように
                ShareLink(item: AppPaths.file("wave.log")) { Label(L.t("動作の記録を共有", "Share the activity log"), systemImage: "doc.text") }
                Text(L.t("ログイン情報は iOS のキーチェーンに、この iPhone だけが読める形で保存します。",
                         "Your login is stored in the iOS keychain, readable only on this iPhone."))
                    .font(.footnote).foregroundStyle(.secondary)
            } header: {
                Text(L.t("このアプリについて", "About"))
            }
        }
        .navigationTitle(L.t("設定", "Settings"))
        .onAppear { aiConfigured = AppServices.ai.configured }
        .sheet(isPresented: $scanningAi) {
            QrScanSheet(title: L.t("Claude の接続の QR を読む", "Scan Claude pairing QR code")) { text in pairAi(text) }
        }
        .confirmationDialog(L.t("ログアウトしますか？", "Log out?"), isPresented: $confirmLogout, titleVisibility: .visible) {
            Button(L.t("ログアウト", "Log out"), role: .destructive) { logOut() }
            Button(L.t("キャンセル", "Cancel"), role: .cancel) {}
        } message: {
            Text(L.t("この iPhone に保存したログイン情報を消します。", "This removes your login saved on this iPhone."))
        }
    }

    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0" }

    // MARK: アカウント

    private var accountSection: some View {
        Section {
            HStack(spacing: 14) {
                SettingsAvatar(name: account.name, photoUrl: account.photoUrl, size: 52)
                VStack(alignment: .leading, spacing: 2) {
                    Text(account.titleText)
                        .font(.headline).lineLimit(1)
                    Text(account.stateText).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)
            if !account.isLoggedIn {
                Button(L.t("ログイン", "Log in")) { LoginGate.shared.skipped = false }
            }
            // ログインが切れたと確かめられた・疑いがある（ADR-0004）: Android と同じく、もう一度ログインを勧める。
            // 通信の失敗だけでは出さない。押してもログアウトはせず、ログインの画面を開くだけ（ログアウトは下の確認付きのボタン）
            if account.isLoggedIn, account.session == .expired {
                VStack(alignment: .leading, spacing: 2) {
                    Label(L.t("再ログインが必要です", "You need to log in again"), systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text(L.t("ログインが切れました。もう一度ログインしてください。", "Your login has expired. Please log in again."))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Button(L.t("もう一度ログイン", "Log in again")) { LoginGate.shared.reLogin = true }
            } else if account.isLoggedIn, case .suspect = account.session {
                VStack(alignment: .leading, spacing: 2) {
                    Label(L.t("ログイン状態を確認できていません", "Couldn’t verify your login"), systemImage: "exclamationmark.circle")
                        .foregroundStyle(.secondary)
                    Text(L.t("少し待っても直らなければ、もう一度ログインしてください。", "If this doesn’t clear up soon, log in again."))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Button(L.t("もう一度ログイン", "Log in again")) { LoginGate.shared.reLogin = true }
            }
            if account.savedLoginUnreadable && !account.isLoggedIn {
                VStack(alignment: .leading, spacing: 2) {
                    Label(L.t("保存したログイン情報を読み込めませんでした", "Couldn’t read your saved login"), systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text(L.t("ログインし直すと上書きされます。", "Logging in again will replace it.")).font(.footnote).foregroundStyle(.secondary)
                }
            }
        } header: {
            Text(L.t("アカウント", "Account"))
        }
    }

    /// ログアウトして、ログインの画面を出す。
    private func logOut() {
        account.logOut()
        LoginGate.shared.skipped = false
    }

    // MARK: Claude

    /// 接続の文字列（貼り付けたもの・QR から読んだもの）で Claude の中継につなぐ。
    private func pairAi(_ text: String) {
        if AiChat.pair(text) {
            pairText = ""
            aiConfigured = AppServices.ai.configured
            AppServices.toast.show(L.t("Claude の中継につなぎました", "Connected to the Claude bridge"))
        } else {
            AppServices.toast.show(L.t("wave の Claude の中継の接続の文字列ではありません", "That isn't a wave Claude bridge pairing text"))
        }
    }

    private var claudeSection: some View {
        Section {
            Text(aiConfigured ? L.t("\(AppServices.settings.aiName ?? AppServices.settings.aiUrl ?? "") の中継につないでいます", "Connected to \(AppServices.settings.aiName ?? AppServices.settings.aiUrl ?? "")")
                : L.t("家の PC の Claude Code で、歌詞の意味・似た曲・プレイリスト作りを頼めます", "Ask about lyrics, find similar songs and make playlists with Claude Code on your PC"))
            if aiConfigured {
                Button(L.t("接続を外す", "Disconnect"), role: .destructive) {
                    AiChat.unpair()
                    aiConfigured = AppServices.ai.configured
                }
            } else {
                Button(L.t("QR を読んでつなぐ", "Scan QR code to connect"), systemImage: "qrcode.viewfinder") { scanningAi = true }
                HStack {
                    SecureField("waveai://pair?u=…&t=…", text: $pairText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    PasteButton(payloadType: String.self) { strings in
                        if let first = strings.first { pairText = first }
                    }
                    .labelStyle(.iconOnly)
                    .buttonBorderShape(.capsule)
                }
                Button(L.t("接続", "Connect")) { pairAi(pairText) }
                    .disabled(pairText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        } header: {
            Text("Claude")
        } footer: {
            if !aiConfigured {
                Text(L.t("家の PC で tools/ai-bridge の wave_ai_bridge.py --pair を動かし、出てきた waveai://pair?… の文字列を貼ってください。",
                         "Run wave_ai_bridge.py --pair from tools/ai-bridge on your PC and paste the waveai://pair?… text it prints."))
            }
        }
    }
}

/// 見出しと小さな説明の2段。
private struct SettingsLabel: View {
    var title: String
    var detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(detail).font(.footnote).foregroundStyle(.secondary)
        }
    }
}

/// アカウントの絵（無ければ名前の頭文字の丸。Android と同じ）。
private struct SettingsAvatar: View {
    var name: String?
    var photoUrl: String?
    var size: CGFloat

    var body: some View {
        if let url = Ui.small(photoUrl) {
            Cover(url: url, size: size, round: true)
        } else {
            ZStack {
                Circle().fill(Color.accentColor.opacity(0.25))
                if let initial = name?.trimmingCharacters(in: .whitespaces).first {
                    Text(String(initial).uppercased()).font(.title2.weight(.semibold)).foregroundStyle(Color.accentColor)
                } else {
                    Image(systemName: "person.fill").font(.title2).foregroundStyle(Color.accentColor)
                }
            }
            .frame(width: size, height: size)
        }
    }
}
#endif
