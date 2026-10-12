#if os(iOS)
import SwiftUI
import WaveCore

/// Claude のタブ（Android の AiChatScreen・Mac の AiView と同じ）。今の曲と歌詞を添えて頼み、答えを流しながら出す。
/// Claude が並べた曲はカードで出し、押すと再生する。下に候補の質問と入力欄。中継につないでいなければ、設定でつなぐ案内を出す。
struct AiTabView: View {
    @State private var input = ""
    @FocusState private var inputFocus: Bool
    /// 中継につないでいるか（設定は Observable でないので、画面に戻るたびに読み直す）。
    @State private var configured = AppServices.ai.configured
    @State private var showHistory = false

    private var suggestions: [String] {
        [
            L.t("この歌詞ってどういう意味だと思う？", "What do these lyrics mean?"),
            L.t("これに似た曲ある？", "Any songs like this?"),
            L.t("この曲から始まるプレイリストを作って", "Make a playlist from this song"),
        ]
    }

    var body: some View {
        let chat = AppServices.ai!
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if !configured {
                        AiSetupNote()
                    }
                    if chat.messages.isEmpty {
                        Text(L.t("曲探し・プレイリスト作りを頼めます。再生中の曲について、歌詞の意味や似た曲も聞けます。",
                                 "Ask Claude to find music, make a playlist, or tell you about the song that's playing — what the lyrics mean, similar songs, and more."))
                            .foregroundStyle(.secondary)
                    }
                    ForEach(Array(chat.messages.enumerated()), id: \.offset) { _, message in
                        AiMessageRow(message: message, configured: configured)
                    }
                    if chat.running {
                        ProgressView().frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            // 会話を払う・入力欄の外を押すと、キーボードを閉じる（会話が短くてスクロールしないときも）
            .scrollDismissesKeyboard(.immediately)
            .contentShape(Rectangle())
            .onTapGesture { inputFocus = false }
            .simultaneousGesture(DragGesture(minimumDistance: 12).onChanged { _ in if inputFocus { inputFocus = false } })
            .onChange(of: chat.messages.count) { _, _ in withAnimation { proxy.scrollTo("end", anchor: .bottom) } }
            .onChange(of: lastLength(chat)) { _, _ in proxy.scrollTo("end", anchor: .bottom) }
        }
        .safeAreaInset(edge: .bottom) { inputBar(chat) }
        .onAppear { configured = chat.configured }
        .onChange(of: chat.messages.count) { _, _ in configured = chat.configured }
        .navigationTitle("Claude")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { showHistory = true } label: { Label(L.t("これまでの会話", "Past chats"), systemImage: "clock.arrow.circlepath") }
            }
            if !chat.messages.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { chat.clear() } label: { Label(L.t("新しい会話", "New chat"), systemImage: "square.and.pencil") }
                }
            }
        }
        .sheet(isPresented: $showHistory) { AiHistorySheet(chat: chat) }
    }

    private func inputBar(_ chat: AiChat) -> some View {
        VStack(spacing: 8) {
            // よく頼むこと
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(suggestions, id: \.self) { suggestion in
                        Button { send(suggestion) } label: {
                            Text(suggestion).font(.footnote).padding(.horizontal, 12).padding(.vertical, 8)
                                .glassCard(16, interactive: true)
                        }
                        .buttonStyle(.plain)
                        .disabled(chat.running)
                        .opacity(chat.running ? 0.5 : 1)
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            HStack(alignment: .bottom, spacing: 8) {
                TextField(L.t("Claude に聞く", "Ask Claude"), text: $input, axis: .vertical)
                    .lineLimit(1...4)
                    .focused($inputFocus)
                    .submitLabel(.send)
                    .onSubmit { if !chat.running { send(input) } }
                    .toolbar {
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button { inputFocus = false } label: { Image(systemName: "keyboard.chevron.compact.down") }
                                .accessibilityLabel(L.t("キーボードを閉じる", "Hide keyboard"))
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .glassCard(20)
                Button {
                    if chat.running { chat.stop() } else { send(input) }
                } label: {
                    Image(systemName: chat.running ? "stop.fill" : "arrow.up")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(Color.accentColor))
                }
                .buttonStyle(.plain)
                .disabled(!chat.running && input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .opacity(!chat.running && input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.5 : 1)
                .accessibilityLabel(chat.running ? L.t("止める", "Stop") : L.t("送る", "Send"))
            }
            .padding(.horizontal, 16)
        }
        .padding(.vertical, 8)
    }

    private func lastLength(_ chat: AiChat) -> Int {
        if case .assistant(let t, _)? = chat.messages.last { return t.count }
        return 0
    }

    private func send(_ text: String) {
        let request = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !request.isEmpty else { return }
        AppServices.ai.send(request)
        input = ""
    }
}

/// 中継につないでいないときの案内（設定の「Claude」へ）。
private struct AiSetupNote: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(L.t("Claude につないでいません", "Claude isn't connected"), systemImage: "sparkles").font(.headline)
            Text(L.t("家の PC の Claude Code で、歌詞の意味・似た曲・プレイリスト作りを頼めます。設定の「Claude」で中継の接続の文字列を貼ってつないでください。",
                     "Ask about lyrics, find similar songs and make playlists with Claude Code on your PC. Paste the bridge's pairing text in Settings → Claude."))
                .font(.footnote).foregroundStyle(.secondary)
            Button(L.t("設定を開く", "Open settings")) { AppServices.nav.settings() }
                .buttonStyle(.borderedProminent)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }
}

/// 会話の1つ分（自分・Claude・動作の行・曲の一覧・失敗）。
private struct AiMessageRow: View {
    var message: AiMessage
    var configured: Bool

    var body: some View {
        switch message {
        case .user(let text):
            HStack {
                Spacer(minLength: 48)
                Text(text).foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 18).fill(Color.accentColor))
                    .textSelection(.enabled)
            }
        case .assistant(let text, let videoId):
            AiAnswerText(text: text, videoId: videoId).frame(maxWidth: .infinity, alignment: .leading)
        case .action(let text):
            Text("・" + text).font(.footnote).foregroundStyle(.secondary)
        case .failure(let text):
            VStack(alignment: .leading, spacing: 6) {
                Text(text).font(.footnote).foregroundStyle(.red)
                if !configured {
                    Button(L.t("設定を開く", "Open settings")) { AppServices.nav.settings() }.font(.footnote)
                }
            }
        case .songs(let songs):
            // 横に並べたカード。押すと再生、長押しで曲のメニュー（Android と同じ）
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(songs, id: \.videoId) { song in
                        Button { AppServices.player.playSong(song) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Cover(url: Ui.img(song.thumbnailUrl), size: 120, radius: 8)
                                Text(song.title).font(.subheadline).lineLimit(2).multilineTextAlignment(.leading)
                                Text(song.artistsText).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                            .frame(width: 120, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .contextMenu { SongMenuItems(song: song) }
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
    }
}

/// これまでの会話（この iPhone の中だけ。Android と同じ）。押すと開いて続け、左へ払って消す。
private struct AiHistorySheet: View {
    var chat: AiChat
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if chat.history.conversations.isEmpty {
                    Text(L.t("まだ会話はありません。会話はこの iPhone の中だけに残ります。", "No past chats yet. Chats are kept on this iPhone only."))
                        .foregroundStyle(.secondary)
                }
                ForEach(chat.history.conversations) { conversation in
                    Button {
                        chat.open(conversation.id)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(conversation.title.isEmpty ? L.t("（質問なし）", "(no question)") : conversation.title)
                                .lineLimit(2).foregroundStyle(.primary)
                            Text(Date(timeIntervalSince1970: Double(conversation.updatedAtMs) / 1000).formatted(date: .abbreviated, time: .shortened))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .listRowBackground(conversation.id == chat.conversationId ? Color.accentColor.opacity(0.12) : nil)
                    .swipeActions {
                        Button(role: .destructive) { chat.delete(conversation.id) } label: { Label(L.t("会話を消す", "Delete chat"), systemImage: "trash") }
                    }
                }
            }
            .navigationTitle(L.t("これまでの会話", "Past chats"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L.t("閉じる", "Close")) { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
#endif
