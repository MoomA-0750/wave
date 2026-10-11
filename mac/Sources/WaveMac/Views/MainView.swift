import AppKit
import SwiftUI
import WaveCore

/// 主画面。並びは Spotify のデスクトップ版（と Windows 版）と同じ: 上に検索、左にマイライブラリ、中央にページ、右に再生中の曲、下に再生の操作。
struct MainView: View {
    @State private var model = MainWindowModel.shared
    @State private var dialogs = Dialogs.shared
    @FocusState private var searchFocus: Bool
    @State private var keyMonitor: Any?

    var body: some View {
        let player = AppServices.player!
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                if model.fullScreen {
                    FullView()
                } else {
                    HStack(spacing: 8) {
                        LibraryPane().frame(width: model.libraryWidth).pane()
                        ContentPane().pane()
                        if model.showRight { RightPane().frame(width: 340).pane() }
                    }
                    .padding(.horizontal, 8)
                    .padding(.top, 4)
                }
                SuggestionsOverlay(model: model)
            }
            PlayerBar()
        }
        .background(GeometryReader { geo in
            Color.clear.onChange(of: geo.size.width, initial: true) { _, w in model.windowWidth = w }
        })
        .toolbar { titleBar }
        .toolbarRole(.editor)
        .navigationTitle(player.current.map { "\($0.title) • \($0.artistsText)" } ?? "wave")
        .sheet(item: $dialogs.addToPlaylist) { song in AddToPlaylistSheet(song: song) }
        .sheet(isPresented: $dialogs.creating) { CreatePlaylistSheet(song: dialogs.createSong) }
        .onChange(of: player.message) { _, message in
            guard let message else { return }
            AppServices.toast.show(message)
            player.message = nil
        }
        .onChange(of: player.current?.videoId) { _, _ in
            // 曲が変わったら曲の表示に戻す（Android と同じ）
            model.showVideo = false
            model.prefetchMembership()
        }
        .onChange(of: searchFocus) { _, focused in model.searchFocusChanged(focused) }
        .onChange(of: model.searchFocused) { _, focused in if focused != searchFocus { searchFocus = focused } }
        .onAppear(perform: installKeys)
        .onDisappear {
            if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
            AppServices.player.saveQueue()
        }
    }

    @ToolbarContentBuilder
    private var titleBar: some ToolbarContent {
        let nav = AppServices.nav!
        // 戻る・進む（同じ大きさの2つのボタン）
        ToolbarItemGroup(placement: .navigation) {
            Button { nav.goBack() } label: { Image(systemName: "chevron.left") }
                .disabled(!nav.canGoBack).help(L.t("戻る", "Back"))
            Button { nav.goForward() } label: { Image(systemName: "chevron.right") }
                .disabled(!nav.canGoForward).help(L.t("進む", "Forward"))
        }
        ToolbarItem(placement: .principal) {
            HStack(spacing: 8) {
                Button { nav.home() } label: { Image(systemName: "house") }.help(L.t("ホーム", "Home"))
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField(L.t("何を聴きたいですか？", "What do you want to play?"), text: $model.searchText)
                        .textFieldStyle(.plain)
                        .focused($searchFocus)
                        .onChange(of: model.searchText) { _, text in
                            if searchFocus { model.searchTextChanged(text) }
                        }
                        .onSubmit { model.searchFor(model.searchText) }
                        .onKeyPress(.escape) {
                            model.suggestions = []
                            return .handled
                        }
                    if !model.searchText.isEmpty {
                        Button {
                            model.searchText = ""
                            model.searchTextChanged("")
                        } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 10)
                .frame(width: model.searchWidth, height: 28)
                .background(Capsule().fill(Color.primary.opacity(0.07)))
                .overlay(Capsule().strokeBorder(searchFocus ? Color.accentColor.opacity(0.6) : Color.primary.opacity(0.1)))
                // Claude に聞く（どの画面からでも。右の欄に開き、もう一度押すと元に戻す）
                Toggle(isOn: Binding(get: { model.aiToggleOn }, set: { _ in model.toggleAi() })) {
                    Image(systemName: "sparkles")
                }
                .toggleStyle(.button)
                .help(L.t("Claude に聞く (⌘J)", "Ask Claude (⌘J)"))
            }
        }
        ToolbarItem(placement: .primaryAction) {
            AccountMenu()
        }
    }

    // MARK: キー

    /// スペースで再生・一時停止（文字の入力中は除く）。⌘←/→ で前・次の曲。⌘K・⌘L で検索欄、⌘J で Claude、F11 で全画面。
    private func installKeys() {
        guard keyMonitor == nil else { return }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            let handled = MainActor.assumeIsolated { handleKey(event) }
            return handled ? nil : event
        }
    }

    @MainActor
    private func handleKey(_ event: NSEvent) -> Bool {
        let player = AppServices.player!
        let cmd = event.modifierFlags.contains(.command)
        let typing = (NSApp.keyWindow?.firstResponder is NSText)
        // シートなどほかの窓では扱わない
        guard NSApp.keyWindow?.isSheet == false, NSApp.keyWindow?.identifier?.rawValue.hasPrefix("login") != true else { return false }
        switch event.keyCode {
        case 49 where !typing && !cmd:  // space
            player.togglePlay()
            return true
        case 124 where cmd && !typing:  // →
            player.next()
            return true
        case 123 where cmd && !typing:  // ←
            player.previous()
            return true
        case 103:  // F11
            model.setFullScreen(!model.fullScreen)
            return true
        case 53 where model.fullScreen:  // esc
            model.setFullScreen(false)
            return true
        default:
            break
        }
        if cmd, let key = event.charactersIgnoringModifiers?.lowercased() {
            switch key {
            case "k", "l":
                searchFocus = true
                return true
            case "j":
                model.toggleAi()
                return true
            default:
                break
            }
        }
        return false
    }
}

/// 右上のアカウントのメニュー。
struct AccountMenu: View {
    var body: some View {
        let account = AppServices.account!
        Menu {
            if account.isLoggedIn {
                Text(account.name ?? L.t("ログイン中", "Logged in"))
                Divider()
                Button(L.t("最近再生した曲", "Recently played"), systemImage: "clock.arrow.circlepath") { AppServices.nav.history() }
                if AppServices.connect.configured {
                    Button(L.t("端末の連携", "Devices"), systemImage: "hifispeaker.2") { AppServices.nav.devices() }
                }
            } else {
                Button(L.t("ログイン", "Log in"), systemImage: "person.crop.circle") { LoginWindowController.show() }
            }
            Button(L.t("設定", "Settings"), systemImage: "gearshape") { AppServices.nav.settings() }
        } label: {
            if let url = Ui.small(account.photoUrl) {
                Cover(url: url, size: 26, round: true)
            } else {
                Image(systemName: "person.crop.circle").font(.system(size: 20))
            }
        }
        .menuIndicator(.hidden)
        .help(L.t("アカウント", "Account"))
    }
}

/// 検索欄の候補（上の検索欄の下に出す）。
struct SuggestionsOverlay: View {
    var model: MainWindowModel

    var body: some View {
        if model.searchFocused && !model.suggestions.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(model.suggestions) { s in
                    SuggestionRow(suggestion: s) { model.choose(s) }
                }
            }
            .padding(6)
            .frame(width: model.searchWidth + 40)
            .background(RoundedRectangle(cornerRadius: 10).fill(.regularMaterial))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Color.primary.opacity(0.1)))
            .shadow(color: .black.opacity(0.25), radius: 16, y: 6)
            .padding(.top, 2)
        }
    }
}

private struct SuggestionRow: View {
    var suggestion: Suggestion
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    if suggestion.isQuery {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    } else {
                        Cover(url: Ui.small(suggestion.thumbnailUrl), size: 36, round: suggestion.round, radius: 4)
                    }
                }
                .frame(width: 36, height: 36)
                .background(suggestion.isQuery ? AnyView(RoundedRectangle(cornerRadius: 4).fill(Color.primary.opacity(0.08))) : AnyView(EmptyView()))
                VStack(alignment: .leading, spacing: 1) {
                    Text(suggestion.title).lineLimit(1)
                    if let subtitle = suggestion.subtitle, !subtitle.isEmpty { Text(subtitle).subtle() }
                }
                Spacer()
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(RoundedRectangle(cornerRadius: 6).fill(hover ? Color.primary.opacity(0.08) : .clear))
        .onHover { hover = $0 }
    }
}

/// 知らせ（Android のスナックバーと同じく、下の細いバーが消えるまでの残り時間を表す）。中央の枠の下に出す。
struct ToastView: View {
    var toast: ToastCenter

    var body: some View {
        if let text = toast.text {
            HStack(spacing: 12) {
                Image(systemName: "info.circle.fill").foregroundStyle(Color.accentColor)
                Text(text).lineLimit(3)
                Spacer(minLength: 8)
                Button { toast.close() } label: { Image(systemName: "xmark") }.buttonStyle(IconButtonStyle(size: 24))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(minWidth: 320, maxWidth: 560)
            .background(.regularMaterial)
            .overlay(alignment: .bottom) {
                TimelineView(.animation) { context in
                    let left = max(0, 1 - context.date.timeIntervalSince(toast.shownAt) / ToastCenter.duration)
                    GeometryReader { geo in
                        Rectangle().fill(Color.accentColor).frame(width: geo.size.width * left, height: 3)
                    }
                    .frame(height: 3)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.primary.opacity(0.1)))
            .shadow(color: .black.opacity(0.2), radius: 10, y: 4)
            .padding(16)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .id(toast.shownAt)
        }
    }
}
