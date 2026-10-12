#if os(macOS)
import AppKit
import SwiftUI

/// 日本語の入力（変換中の文字）の最中かどうか。キーを先に横取りする処理（.onKeyPress・キーの見張り）は、
/// 変換中なら手を出さず、Return・Esc・Space を変換の確定・取り消しに使わせる。
@MainActor
enum TextInput {
    static var composing: Bool {
        (NSApp.keyWindow?.firstResponder as? NSTextView)?.hasMarkedText() == true
    }
}

/// Claude に聞く欄。複数行で、Return で送り Shift+Return で改行する。
/// SwiftUI の TextField(axis: .vertical) に .onKeyPress(.return) を付けると、変換を確定する Return まで先に取られて
/// 打ちかけのまま送られ、変換中の文字（下線）も出ないので、NSTextView で組む。
/// Return は IME が使わなかったときだけ doCommandBy に届くので、変換中の Return は確定に使われる。
/// また変換中は欄の文字を外から書き換えない（書き換えると変換中の文字が確定されて、ライブ変換が切れる）。
struct ChatInputField: NSViewRepresentable {
    @Binding var text: String
    var placeholder: String
    var maxLines = 5
    var autoFocus = false
    var onSubmit: () -> Void

    private static let font = NSFont.systemFont(ofSize: NSFont.systemFontSize)
    private static let inset = NSSize(width: 4, height: 4)

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSTextView.scrollableTextView()
        scroll.drawsBackground = false
        scroll.borderType = .noBorder
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        let view = scroll.documentView as! NSTextView
        view.delegate = context.coordinator
        view.font = Self.font
        view.textColor = .labelColor
        view.drawsBackground = false
        view.isRichText = false
        view.importsGraphics = false
        view.allowsUndo = true
        view.isAutomaticQuoteSubstitutionEnabled = false
        view.isAutomaticDashSubstitutionEnabled = false
        view.isAutomaticTextReplacementEnabled = false
        view.textContainerInset = Self.inset
        view.string = text
        // 空のときの薄い案内。NSTextView 自身に描かせると、変換中の文字があるあいだも正しく消える
        view.setValue(NSAttributedString(string: placeholder, attributes: [.font: Self.font, .foregroundColor: NSColor.placeholderTextColor]),
                      forKey: "placeholderAttributedString")
        view.setAccessibilityPlaceholderValue(placeholder)
        if autoFocus {
            DispatchQueue.main.async { view.window?.makeFirstResponder(view) }
        }
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        context.coordinator.parent = self
        guard let view = scroll.documentView as? NSTextView else { return }
        // 変換中は触らない（送ったあとに空にするなど、外から変えたときだけ入れ直す）
        if !view.hasMarkedText(), view.string != text {
            view.string = text
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView scroll: NSScrollView, context: Context) -> CGSize? {
        let view = scroll.documentView as? NSTextView
        let width = proposal.width ?? scroll.bounds.width
        let padding = (view?.textContainer?.lineFragmentPadding ?? 5) * 2 + Self.inset.width * 2
        let string = view?.string ?? text
        let line = NSLayoutManager().defaultLineHeight(for: Self.font)
        var height = (string as NSString).boundingRect(
            with: NSSize(width: max(width - padding, 10), height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: Self.font]).height
        if string.hasSuffix("\n") { height += line }  // 末尾の改行のあとの空の行も数える
        height = min(max(height, line), line * CGFloat(maxLines))
        return CGSize(width: width, height: ceil(height) + Self.inset.height * 2)
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: ChatInputField

        init(_ parent: ChatInputField) { self.parent = parent }

        func textDidChange(_ notification: Notification) {
            guard let view = notification.object as? NSTextView else { return }
            if parent.text != view.string { parent.text = view.string }
        }

        func textView(_ textView: NSTextView, doCommandBy selector: Selector) -> Bool {
            switch selector {
            case #selector(NSResponder.insertNewline(_:)):
                if NSApp.currentEvent?.modifierFlags.contains(.shift) == true {
                    textView.insertNewlineIgnoringFieldEditor(nil)
                } else {
                    parent.onSubmit()
                }
                return true
            case #selector(NSResponder.insertTab(_:)):
                textView.window?.selectNextKeyView(nil)
                return true
            case #selector(NSResponder.insertBacktab(_:)):
                textView.window?.selectPreviousKeyView(nil)
                return true
            default:
                return false
            }
        }
    }
}
#endif
