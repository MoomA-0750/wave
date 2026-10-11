import SwiftUI
import WaveCore

/// Claude に聞く（Android と同じ。今の曲と歌詞を添えて頼む）。
struct AiView: View {
    @State private var model = MainWindowModel.shared
    @State private var input = ""
    @FocusState private var inputFocus: Bool

    private let suggestions = [
        L.t("この歌詞ってどういう意味だと思う？", "What do these lyrics mean?"),
        L.t("これに似た曲ある？", "Any songs like this?"),
        L.t("この曲から始まるプレイリストを作って", "Make a playlist from this song"),
    ]

    var body: some View {
        let chat = AppServices.ai!
        VStack(spacing: 0) {
            HStack {
                Text(L.t("Claude に聞く", "Ask Claude")).font(.body.weight(.semibold))
                Spacer()
                Button(L.t("新しい会話", "New chat")) { chat.clear() }.buttonStyle(.textLink)
                Button { model.setRightPane(open: true, queue: false) } label: { Image(systemName: "xmark").font(.system(size: 11)) }
                    .buttonStyle(IconButtonStyle()).help(L.t("閉じる", "Close"))
            }
            .padding(EdgeInsets(top: 12, leading: 16, bottom: 4, trailing: 8))
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        if chat.messages.isEmpty {
                            Text(L.t("曲探し・プレイリスト作りを頼めます。再生中の曲について、歌詞の意味や似た曲も聞けます。",
                                     "Ask Claude to find music, make a playlist, or tell you about the song that's playing — what the lyrics mean, similar songs, and more."))
                                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                        ForEach(Array(chat.messages.enumerated()), id: \.offset) { _, message in
                            AiMessageView(message: message)
                        }
                        if chat.running { ProgressView().progressViewStyle(.linear) }
                        Color.clear.frame(height: 1).id("end")
                    }
                    .padding(EdgeInsets(top: 8, leading: 16, bottom: 16, trailing: 16))
                }
                .onChange(of: chat.messages.count) { _, _ in proxy.scrollTo("end", anchor: .bottom) }
                .onChange(of: lastText(chat)) { _, _ in proxy.scrollTo("end", anchor: .bottom) }
            }
            if !chat.running {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(suggestions, id: \.self) { s in
                        Button(s) { chat.send(s) }.buttonStyle(.textLink).font(.callout)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
            HStack(alignment: .bottom, spacing: 8) {
                // Enter で送る（Shift+Enter は改行）
                TextField(L.t("Claude に聞く", "Ask Claude"), text: $input, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...5)
                    .focused($inputFocus)
                    .onKeyPress(.return, phases: .down) { press in
                        if press.modifiers.contains(.shift) { return .ignored }
                        submit()
                        return .handled
                    }
                Button(action: submit) { Image(systemName: chat.running ? "stop.fill" : "paperplane.fill") }
                    .buttonStyle(IconButtonStyle())
            }
            .padding(EdgeInsets(top: 4, leading: 16, bottom: 12, trailing: 16))
        }
        .onAppear { inputFocus = true }
    }

    private func lastText(_ chat: AiChat) -> Int {
        if case .assistant(let t)? = chat.messages.last { return t.count }
        return 0
    }

    private func submit() {
        let chat = AppServices.ai!
        if chat.running {
            chat.stop()
            return
        }
        chat.send(input)
        input = ""
    }
}

private struct AiMessageView: View {
    var message: AiMessage

    var body: some View {
        switch message {
        case .user(let text):
            HStack {
                Spacer(minLength: 40)
                Text(text).foregroundStyle(.white)
                    .padding(EdgeInsets(top: 6, leading: 12, bottom: 8, trailing: 12))
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color.accentColor))
                    .frame(maxWidth: 260, alignment: .trailing)
            }
        case .assistant(let text):
            Text(AiView.markdown(text)).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
        case .action(let text):
            Text("・" + text).font(.caption).foregroundStyle(.secondary)
        case .failure(let text):
            Text(text).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
        case .songs(let songs):
            // 押すと再生、右クリックで曲のメニュー
            VStack(spacing: 2) {
                ForEach(songs, id: \.videoId) { song in
                    Button { AppServices.player.playSong(song) } label: {
                        HStack(spacing: 10) {
                            Cover(url: Ui.small(song.thumbnailUrl), size: 40, radius: 4)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(song.title).lineLimit(1)
                                Text(song.artistsText).subtle()
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .contextMenu { SongMenuItems(song: song) }
                }
            }
        }
    }
}

extension AiView {
    /// Claude の答えの Markdown のうち、太字（**…**）と見出し（# …）だけを見た目にする（Android と同じ）。
    static func markdown(_ text: String) -> AttributedString {
        var out = AttributedString()
        let lines = text.components(separatedBy: "\n")
        let heading = try? NSRegularExpression(pattern: #"^#{1,6}\s+"#)
        for (i, raw) in lines.enumerated() {
            if i > 0 { out += AttributedString("\n") }
            var line = raw
            var isHeading = false
            if let m = heading?.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)), let r = Range(m.range, in: line) {
                line.removeSubrange(r)
                isHeading = true
            }
            for (k, part) in line.components(separatedBy: "**").enumerated() {
                var run = AttributedString(part)
                if isHeading || k % 2 == 1 { run.font = .body.bold() }
                out += run
            }
        }
        return out
    }
}
