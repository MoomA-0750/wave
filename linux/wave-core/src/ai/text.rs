//! Claude に送る歌詞と、Claude の答えの中の時刻（Windows・Android の AiText と同じ）。
//! 時刻付きの歌詞は行ごとに「[01:23] 本文」の形で送り、答えの中の [mm:ss] は押すとその位置へ飛べるようにする。

use std::sync::LazyLock;

use regex::Regex;

use crate::lyrics::SongLyrics;

/// 中継へ送る歌詞。時刻付きなら行ごとに [mm:ss] 本文（空の行は飛ばす）、時刻が無ければ本文のまま。
pub fn lyrics_text(lyrics: &SongLyrics) -> String {
    match lyrics {
        SongLyrics::Synced { lines, .. } => lines
            .iter()
            .filter(|l| !l.text.trim().is_empty())
            .map(|l| format!("[{}] {}", stamp(l.start_ms), l.text.trim()))
            .collect::<Vec<_>>()
            .join("\n"),
        SongLyrics::Plain { text, .. } => text.clone(),
    }
}

/// 曲の頭からの時刻を mm:ss にする（秒より下は切り捨て。1時間を超えても分で数える）。
pub fn stamp(position_ms: u64) -> String {
    let seconds = position_ms / 1000;
    format!("{:02}:{:02}", seconds / 60, seconds % 60)
}

/// 答えの中の時刻 [mm:ss] の1つ。start・end は括弧を含むバイト位置。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Timestamp {
    pub start: usize,
    pub end: usize,
    pub position_ms: u64,
}

static TIMESTAMP: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\[(\d{1,3}):([0-5]\d)\]").unwrap());

/// 答えの中の時刻を、前から順に返す。
pub fn timestamps(text: &str) -> Vec<Timestamp> {
    TIMESTAMP
        .captures_iter(text)
        .map(|c| {
            let whole = c.get(0).unwrap();
            let (m, s): (u64, u64) = (c[1].parse().unwrap_or(0), c[2].parse().unwrap_or(0));
            Timestamp { start: whole.start(), end: whole.end(), position_ms: (m * 60 + s) * 1000 }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lyrics::LyricLine;

    #[test]
    fn synced_lyrics_get_stamps_and_skip_empty_lines() {
        let lyrics = SongLyrics::Synced {
            lines: vec![LyricLine { start_ms: 83_900, text: " 歌 ".into() }, LyricLine { start_ms: 90_000, text: "".into() }, LyricLine { start_ms: 3_725_000, text: "長い".into() }],
            source: None,
        };
        assert_eq!(lyrics_text(&lyrics), "[01:23] 歌\n[62:05] 長い");
    }

    #[test]
    fn finds_timestamps_in_answers() {
        let found = timestamps("サビは [01:23]「…」と [2:05] です。[1:75] は違う");
        assert_eq!(found.iter().map(|t| t.position_ms).collect::<Vec<_>>(), [83_000, 125_000]);
    }
}
