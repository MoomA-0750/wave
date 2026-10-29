//! 歌詞（YouTube Music の時刻付き・LRCLIB の時刻付き・YouTube Music の時刻なし）。Windows の Wave.Core.Lyrics と同じ。

pub mod lrc;
pub mod lrclib;
pub mod search;
pub mod service;
pub mod timed;

/// 歌詞の1行。start_ms は曲の頭からの時刻。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LyricLine {
    pub start_ms: u64,
    pub text: String,
}

/// 曲の歌詞。source は出典の表示（「LRCLIB」「Source: Musixmatch」など）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SongLyrics {
    /// 行ごとに時刻がある（再生位置に合わせて表示する）。
    Synced { lines: Vec<LyricLine>, source: Option<String> },
    /// 時刻の無い本文。
    Plain { text: String, source: Option<String> },
}
