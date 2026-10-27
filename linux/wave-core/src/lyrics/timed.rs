//! YouTube Music の時刻付きの歌詞（Windows・Android の YouTubeTimedLyricsClient と同じ）。歌詞のタブ（next で見つけた MPLY…）を
//! ANDROID_MUSIC として読むと、行ごとの時刻が付いて返る（WEB_REMIX では時刻なしだけ）。ログインは要らないので、
//! 認証情報を持たないクライアントで匿名で送る（ADR-0003。cookie を WEB_REMIX 以外の名乗りで送らない）。送るのは歌詞のタブの ID だけ。

use std::time::Duration;

use serde_json::{Value, json};

use super::{LyricLine, SongLyrics};
use crate::json::{JsonPath, find_all};

/// 6.x では時刻付きの形にならない（2026-10 に確かめた）。7.27.52・8.40.54 は返る。
pub const CLIENT_VERSION: &str = "7.27.52";
const USER_AGENT: &str = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TimedResult {
    Found(SongLyrics),
    /// 時刻付きが無い（MV など時刻の無い歌詞・歌詞の無い曲）。
    NotFound,
    Failed,
}

#[derive(Clone)]
pub struct TimedLyricsClient {
    http: reqwest::Client,
    hl: String,
    gl: String,
}

impl TimedLyricsClient {
    pub fn new(hl: &str, gl: &str) -> Self {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(15)).build().expect("the TLS backend is built in");
        Self { http, hl: hl.into(), gl: gl.into() }
    }

    pub async fn timed(&self, browse_id: &str) -> TimedResult {
        let body = json!({
            "context": { "client": { "clientName": "ANDROID_MUSIC", "clientVersion": CLIENT_VERSION, "hl": self.hl, "gl": self.gl } },
            "browseId": browse_id,
        });
        let Ok(response) = self
            .http
            .post("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("user-agent", USER_AGENT)
            .json(&body)
            .send()
            .await
        else {
            return TimedResult::Failed;
        };
        if !response.status().is_success() {
            return TimedResult::Failed;
        }
        match response.json::<Value>().await {
            Ok(root) => parse(&root).map_or(TimedResult::NotFound, TimedResult::Found),
            Err(_) => TimedResult::Failed,
        }
    }
}

/// 応答の …timedLyricsModel.lyricsData の行と出典。時刻（cueRange）の無い行ばかり・行が無いなら None。
/// 間奏の「♪」の行は空の行にする（画面では行の区切りとして出す）。
pub fn parse(root: &Value) -> Option<SongLyrics> {
    let data = find_all(root, "timedLyricsModel").into_iter().next()?.path(&["lyricsData"])?;
    let mut lines: Vec<LyricLine> = data
        .path(&["timedLyricsData"])
        .items()
        .into_iter()
        .filter_map(|line| {
            let start = line.path(&["cueRange", "startTimeMilliseconds"])?;
            let start_ms = start.as_u64().or_else(|| start.as_str()?.parse().ok())?;
            let text = line.path(&["lyricLine"]).str_().unwrap_or("").trim();
            Some(LyricLine { start_ms, text: if text == "♪" { String::new() } else { text.to_string() } })
        })
        .collect();
    if lines.is_empty() {
        return None;
    }
    lines.sort_by_key(|l| l.start_ms);
    Some(SongLyrics::Synced { lines, source: data.path(&["sourceMessage"]).str_().map(String::from) })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> String {
        std::fs::read_to_string(format!("{}/../../core/src/desktopTest/resources/fixtures/{name}.json", env!("CARGO_MANIFEST_DIR"))).unwrap()
    }

    #[test]
    fn timed_youtube_lyrics_are_read_and_untimed_lines_are_not() {
        let synced = parse(&serde_json::from_str(&fixture("browse_lyrics_timed")).unwrap()).unwrap();
        let line = |start_ms, text: &str| LyricLine { start_ms, text: text.into() };
        assert_eq!(
            synced,
            SongLyrics::Synced { lines: vec![line(1530, "一行目"), line(9040, "二行目"), line(17000, "")], source: Some("提供元: Musixmatch".into()) }
        );
        // MV の歌詞（時刻の無い行だけ）と、古い形（時刻なしの棚）は時刻付きにしない
        assert!(parse(&serde_json::from_str(&fixture("browse_lyrics_timed").replace("\"cueRange\"", "\"noCueRange\"")).unwrap()).is_none());
        assert!(parse(&serde_json::from_str(&fixture("browse_lyrics")).unwrap()).is_none());
    }
}
