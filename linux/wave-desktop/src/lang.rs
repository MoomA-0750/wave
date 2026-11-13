//! 画面の言語（日本語・英語。Windows の L・Mac と同じ決まり）。既定は端末の言語（LC_ALL・LC_MESSAGES・LANG の順に見て、日本語なら日本語、それ以外は英語）。
//! 設定の「画面の言語」（ui_language: ""・"ja"・"en"）で固定でき、次の起動から効く。YouTube に送る表示の言語（hl）も画面に合わせる。
//! 文言は使う場所に日本語と英語を並べて書く（`t("日本語", "English")`）。

use std::sync::OnceLock;

static JA: OnceLock<bool> = OnceLock::new();

/// 起動のはじめに1度だけ呼ぶ（設定の値。空なら端末の言語）。呼ぶ前に ja() を使うと端末の言語で決まる。
pub fn init(setting: &str) {
    let _ = JA.set(choose(setting, &system_locale()));
}

pub fn ja() -> bool {
    *JA.get_or_init(|| choose("", &system_locale()))
}

pub fn t<'a>(ja_text: &'a str, en_text: &'a str) -> &'a str {
    if ja() { ja_text } else { en_text }
}

/// YouTube に送る表示の言語（届く文字を画面と同じ言語にする）。
pub fn hl() -> &'static str {
    if ja() { "ja" } else { "en" }
}

/// 曲数（英語は単数・複数を分ける）。
pub fn songs(count: usize) -> String {
    if ja() { format!("{count} 曲") } else if count == 1 { "1 song".into() } else { format!("{count} songs") }
}

fn system_locale() -> String {
    ["LC_ALL", "LC_MESSAGES", "LANG"]
        .iter()
        .filter_map(|k| std::env::var(k).ok())
        .find(|v| !v.is_empty())
        .unwrap_or_default()
}

fn choose(setting: &str, locale: &str) -> bool {
    match setting {
        "ja" => true,
        "en" => false,
        _ => locale.starts_with("ja"),
    }
}

#[cfg(test)]
mod tests {
    use super::choose;

    #[test]
    fn the_setting_wins_over_the_locale() {
        assert!(choose("", "ja_JP.UTF-8"));
        assert!(!choose("", "en_US.UTF-8"));
        assert!(!choose("", ""));
        assert!(choose("ja", "en_US.UTF-8"));
        assert!(!choose("en", "ja_JP.UTF-8"));
    }
}
