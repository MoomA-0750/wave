import Foundation

/// 画面の言語（日本語・英語）。既定は macOS の表示言語に合わせ、日本語なら日本語、それ以外は英語。設定で固定できる（次の起動から）。
/// 文言は使う場所に日本語と英語を並べて書く（`L.t("日本語", "English")`）。Windows 版の L と同じ。
enum L {
    nonisolated(unsafe) private(set) static var ja = true

    /// YouTube に送る表示の言語（届く文字を画面と同じ言語にする）。
    static var hl: String { ja ? "ja" : "en" }

    static func initialize(_ setting: String?) {
        switch setting {
        case "ja": ja = true
        case "en": ja = false
        default: ja = (Locale.preferredLanguages.first ?? "").hasPrefix("ja")
        }
    }

    static func t(_ ja: String, _ en: String) -> String { L.ja ? ja : en }

    /// 曲数（英語は単数・複数を分ける）。
    static func songs(_ count: Int) -> String { ja ? "\(count) 曲" : count == 1 ? "1 song" : "\(count) songs" }

    /// 合計の長さ（プレイリスト・アルバムの補足）。
    static func length(_ seconds: Int) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        if h >= 1 { return ja ? "\(h) 時間 \(m) 分" : "\(h) hr \(m) min" }
        return ja ? "\(m) 分 \(s) 秒" : "\(m) min \(s) sec"
    }

    /// 引用（「…」/ "…"）。
    static func q(_ text: String) -> String { ja ? "「\(text)」" : "“\(text)”" }
}
