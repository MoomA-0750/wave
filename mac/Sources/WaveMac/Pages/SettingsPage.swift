import SwiftUI
import WaveCore

/// 設定（Windows の SettingsPage と同じ並び）。
struct SettingsPageView: View {
    @State private var sendHistory = AppServices.settings.sendHistory
    @State private var releaseNotices = AppServices.settings.releaseNotices
    @State private var language = AppServices.settings.language
    @State private var pairText = ""
    @State private var aiConfigured = AppServices.ai.configured
    @State private var confirmLogout = false

    var body: some View {
        let account = AppServices.account!
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(L.t("設定", "Settings")).font(.system(size: 48, weight: .bold)).padding(.bottom, 16)

                section(L.t("アカウント", "Account"))
                card {
                    HStack(spacing: 16) {
                        if let url = Ui.small(account.photoUrl) {
                            Cover(url: url, size: 56, round: true)
                        } else {
                            Image(systemName: "person.crop.circle.fill").font(.system(size: 50)).foregroundStyle(.secondary)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(account.isLoggedIn ? account.name ?? L.t("ログイン中", "Logged in") : L.t("ログインしていません", "Not logged in")).subtitleStyle()
                            Text(stateText(account)).subtle()
                        }
                        Spacer()
                        Button(account.isLoggedIn ? L.t("ログアウト", "Log out") : L.t("ログイン", "Log in")) {
                            if account.isLoggedIn { confirmLogout = true } else { LoginWindowController.show() }
                        }
                    }
                }
                // ログインを確かめられなかった（期限切れなど）: Android と同じく、もう一度ログインを勧める
                if account.isLoggedIn && account.accountCheckFailed {
                    warning(L.t("ログインを確かめられませんでした", "Couldn’t verify your login"),
                            L.t("ログインの期限が切れたかもしれません。もう一度ログインしてください。", "Your login may have expired. Please log in again.")) {
                        Button(L.t("もう一度ログイン", "Log in again")) { LoginWindowController.show() }
                    }
                }
                if account.savedLoginUnreadable && !account.isLoggedIn {
                    warning(L.t("保存したログイン情報を読み込めませんでした", "Couldn’t read your saved login"), L.t("ログインし直すと上書きされます。", "Logging in again will replace it.")) {
                        EmptyView()
                    }
                }

                section(L.t("再生", "Play"))
                card {
                    toggleRow(L.t("再生した曲を YouTube Music の再生履歴に残す", "Save played songs to your YouTube Music history"),
                              L.t("オフにすると、wave で聴いた曲は履歴やおすすめに使われません", "When off, songs you play in wave won’t be used for history or recommendations"),
                              isOn: $sendHistory)
                        .onChange(of: sendHistory) { _, v in
                            AppServices.settings.sendHistory = v
                            AppServices.settings.save()
                        }
                }
                card {
                    toggleRow(L.t("新作の通知", "New release notifications"),
                              L.t("登録したアーティストのアルバム・シングル・動画が出たら知らせる（wave が動いている間、6時間おきに確かめる）",
                                  "Notify when artists you subscribe to release albums, singles or videos (checked every 6 hours while wave is running)"),
                              isOn: $releaseNotices)
                        .onChange(of: releaseNotices) { _, v in
                            AppServices.settings.releaseNotices = v
                            AppServices.settings.save()
                        }
                }

                section("Claude")
                card {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(aiConfigured ? L.t("\(AppServices.settings.aiName ?? AppServices.settings.aiUrl ?? "") の中継につないでいます", "Connected to \(AppServices.settings.aiName ?? AppServices.settings.aiUrl ?? "")")
                            : L.t("家の PC の Claude Code で、歌詞の意味・似た曲・プレイリスト作りを頼めます", "Ask about lyrics, find similar songs and make playlists with Claude Code on your PC"))
                        Text(L.t("家の PC で tools/ai-bridge の wave_ai_bridge.py --pair を動かし、出てきた waveai://pair?… の文字列を貼ってください。",
                                 "Run wave_ai_bridge.py --pair from tools/ai-bridge on your PC and paste the waveai://pair?… text it prints."))
                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        HStack(spacing: 8) {
                            SecureField("waveai://pair?u=…&t=…", text: $pairText).textFieldStyle(.roundedBorder)
                            Button(L.t("接続", "Connect")) {
                                if AiChat.pair(pairText) {
                                    pairText = ""
                                    aiConfigured = AppServices.ai.configured
                                } else {
                                    AppServices.player.message = L.t("wave の Claude の中継の接続の文字列ではありません", "That isn't a wave Claude bridge pairing text")
                                }
                            }
                            if aiConfigured {
                                Button(L.t("接続を外す", "Disconnect")) {
                                    AiChat.unpair()
                                    aiConfigured = AppServices.ai.configured
                                }
                            }
                        }
                    }
                }

                section(L.t("言語", "Language"))
                card {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L.t("画面の言語", "App language"))
                            Text(L.t("YouTube から届く文字（棚の見出しなど）もこの言語になります。次の起動から反映されます",
                                     "Text from YouTube (such as shelf titles) also uses this language. Takes effect the next time you open wave"))
                                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 40)
                        Picker("", selection: $language) {
                            Text(L.t("macOS に合わせる", "Use macOS language")).tag("system")
                            Text("日本語").tag("ja")
                            Text("English").tag("en")
                        }
                        .labelsHidden()
                        .frame(width: 200)
                        .onChange(of: language) { _, v in
                            guard v != AppServices.settings.language else { return }
                            AppServices.settings.language = v
                            AppServices.settings.save()
                            AppServices.toast.show(L.t("次の起動から反映されます", "Takes effect the next time you open wave"))
                        }
                    }
                }

                section(L.t("このアプリについて", "About"))
                card {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("wave for Mac \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0")")
                        Text(L.t("ログイン情報は macOS のキーチェーンに、このユーザーとこの Mac だけが読める形で保存します。",
                                 "Your login is stored in the macOS keychain, readable only by this user on this Mac."))
                            .font(.caption).foregroundStyle(.secondary)
                        Text(L.t("データの場所: ", "Data folder: ") + AppPaths.data.path).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                    }
                }
            }
            .padding(EdgeInsets(top: 32, leading: 40, bottom: 32, trailing: 40))
            .frame(maxWidth: 880, alignment: .leading)
        }
        .confirmationDialog(L.t("ログアウトしますか？", "Log out?"), isPresented: $confirmLogout) {
            Button(L.t("ログアウト", "Log out"), role: .destructive) { AppServices.account.logOut() }
            Button(L.t("キャンセル", "Cancel"), role: .cancel) {}
        } message: {
            Text(L.t("この Mac に保存したログイン情報を消します。", "This removes your login saved on this Mac."))
        }
    }

    private func stateText(_ account: AccountState) -> String {
        if !account.isLoggedIn { return L.t("ログインすると、ライブラリやおすすめが使えます", "Log in to use your library and recommendations") }
        if !account.accountChecked { return L.t("アカウントを確かめています…", "Checking account…") }
        let premium: String = switch account.premiumAudio {
        case true?: L.t("使えます", "Available")
        case false?: L.t("使えません", "Unavailable")
        case nil: account.accountCheckFailed ? L.t("分かりません", "Unknown") : L.t("確かめています…", "Checking…")
        }
        return L.t("Premium 音質: ", "Premium audio: ") + premium
    }

    private func section(_ title: String) -> some View {
        Text(title).font(.body.weight(.semibold)).padding(.top, 16).padding(.bottom, 4)
    }

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        content()
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.08)))
    }

    private func toggleRow(_ title: String, _ detail: String, isOn: Binding<Bool>) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 40)
            Toggle("", isOn: isOn).toggleStyle(.switch).labelsHidden()
        }
    }

    private func warning<A: View>(_ title: String, _ message: String, @ViewBuilder action: () -> A) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.semibold))
                Text(message).font(.callout)
            }
            Spacer()
            action()
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.yellow.opacity(0.12)))
    }
}
