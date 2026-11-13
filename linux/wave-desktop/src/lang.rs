//! 画面の言語（日本語・英語。Windows の L・Mac と同じ決まり）。既定は端末の言語（LC_ALL・LC_MESSAGES・LANG の順に見て、日本語なら日本語、それ以外は英語）。
//! 設定の「画面の言語」（ui_language: ""・"ja"・"en"）で固定でき、次の起動から効く。YouTube に送る表示の言語（hl）も画面に合わせる。
//! 文言は使う場所に日本語と英語を並べて書く（`t("日本語", "English")`）。

use std::sync::OnceLock;

use wave_core::music::collection::SortKey;
use wave_core::music::models::Song;

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

/// 合計の長さ（一覧の補足）。1 時間以上は「1 時間 5 分」/ `1 hr 5 min`、未満は「13 分 5 秒」/ `13 min 5 sec`（Windows・Mac と同じ）。
pub fn length(seconds: u32) -> String {
    length_in(ja(), seconds)
}

/// 曲数と合計の長さ（「3 曲、13 分 5 秒」/ `3 songs, 13 min 5 sec`）。長さの分からない曲は数えない。
pub fn count_and_length(list: &[Song]) -> String {
    let total: u32 = list.iter().filter_map(|s| s.duration_seconds).sum();
    format!("{}{}{}", songs(list.len()), t("、", ", "), length(total))
}

fn length_in(ja: bool, seconds: u32) -> String {
    let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60);
    match (ja, h > 0) {
        (true, true) => format!("{h} 時間 {m} 分"),
        (false, true) => format!("{h} hr {m} min"),
        (true, false) => format!("{m} 分 {s} 秒"),
        (false, false) => format!("{m} min {s} sec"),
    }
}

/// 並べ方の名前（画面の言語で。日本語は wave-core の名前のまま。GTK 版・QML 版で同じ）。
pub fn sort_label(key: SortKey) -> &'static str {
    let en = match key {
        SortKey::Custom => "Custom order",
        SortKey::Added => "Date added",
        SortKey::Published => "Release date",
        SortKey::Title => "Title",
        SortKey::Artist => "Artist",
        SortKey::Album => "Album",
        SortKey::Duration => "Duration",
    };
    t(key.label(), en)
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
    use super::{choose, length_in};

    #[test]
    fn lengths_read_like_windows_and_the_mac() {
        assert_eq!(length_in(true, 785), "13 分 5 秒");
        assert_eq!(length_in(false, 785), "13 min 5 sec");
        assert_eq!(length_in(true, 3900), "1 時間 5 分");
        assert_eq!(length_in(false, 3900), "1 hr 5 min");
        assert_eq!(length_in(false, 0), "0 min 0 sec");
    }

    #[test]
    fn the_setting_wins_over_the_locale() {
        assert!(choose("", "ja_JP.UTF-8"));
        assert!(!choose("", "en_US.UTF-8"));
        assert!(!choose("", ""));
        assert!(choose("ja", "en_US.UTF-8"));
        assert!(!choose("en", "ja_JP.UTF-8"));
    }
}
