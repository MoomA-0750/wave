//! LRCLIB（lrclib.net）から時刻付きの歌詞を探す。送るのは曲名・アーティスト名・アルバム名・長さだけ（YouTube の cookie は持たない）。

use std::sync::LazyLock;
use std::time::Duration;

use regex::Regex;
use serde::Deserialize;

use super::{LyricLine, lrc};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LrclibResult {
    Found(Vec<LyricLine>),
    NotFound,
    Failed,
}

#[derive(Deserialize)]
struct Track {
    #[serde(rename = "duration")]
    duration: Option<f64>,
    #[serde(default)]
    instrumental: bool,
    #[serde(rename = "syncedLyrics")]
    synced_lyrics: Option<String>,
}

const DURATION_TOLERANCES: [f64; 2] = [2.0, 5.0];
const USER_AGENT: &str = concat!("wave-linux/", env!("CARGO_PKG_VERSION"));

#[derive(Clone)]
pub struct LrclibClient {
    http: reqwest::Client,
    base_url: String,
}

impl Default for LrclibClient {
    fn default() -> Self {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(15)).build().expect("the TLS backend is built in");
        Self { http, base_url: "https://lrclib.net".into() }
    }
}

impl LrclibClient {
    pub async fn synced(&self, title: &str, artist: &str, album: Option<&str>, duration_seconds: Option<u32>) -> LrclibResult {
        match self.try_synced(title, artist, album, duration_seconds).await {
            Ok(Some(lines)) => LrclibResult::Found(lines),
            Ok(None) => LrclibResult::NotFound,
            Err(()) => LrclibResult::Failed,
        }
    }

    async fn try_synced(&self, title: &str, artist: &str, album: Option<&str>, duration_seconds: Option<u32>) -> Result<Option<Vec<LyricLine>>, ()> {
        let mut query: Vec<(&str, String)> = vec![("track_name", title.into()), ("artist_name", artist.into())];
        if let Some(album) = album {
            query.push(("album_name", album.into()));
        }
        if let Some(d) = duration_seconds {
            query.push(("duration", d.to_string()));
        }
        let url = url::Url::parse_with_params(&format!("{}/api/get", self.base_url), &query).map_err(|_| ())?;
        let response = self.http.get(url).header("user-agent", USER_AGENT).send().await.map_err(|_| ())?;
        match response.status().as_u16() {
            404 => {}
            200 => {
                if let Some(lines) = response.json::<Track>().await.ok().and_then(|t| lines_of(&t)) {
                    return Ok(Some(lines));
                }
            }
            _ => return Err(()),
        }
        for t in search_titles(title) {
            let url = url::Url::parse_with_params(&format!("{}/api/search", self.base_url), &[("track_name", t.as_str()), ("artist_name", artist)]).map_err(|_| ())?;
            let response = self
                .http
                .get(url)
                .header("user-agent", USER_AGENT)
                .send()
                .await
                .map_err(|_| ())?;
            if response.status().as_u16() != 200 {
                return Err(());
            }
            let tracks: Vec<Track> = response.json().await.map_err(|_| ())?;
            if let Some(lines) = pick(&tracks, duration_seconds).and_then(lines_of) {
                return Ok(Some(lines));
            }
        }
        Ok(None)
    }
}

fn lines_of(track: &Track) -> Option<Vec<LyricLine>> {
    if track.instrumental {
        return None;
    }
    let lines = lrc::parse(track.synced_lyrics.as_deref().filter(|s| !s.is_empty())?);
    lines.iter().any(|l| !l.text.trim().is_empty()).then_some(lines)
}

/// 検索の結果から、時刻付きで長さの近いものを選ぶ。長さが分からなければ最初の時刻付き。
fn pick(tracks: &[Track], duration_seconds: Option<u32>) -> Option<&Track> {
    let synced: Vec<&Track> = tracks.iter().filter(|t| !t.instrumental && t.synced_lyrics.as_deref().is_some_and(|s| !s.trim().is_empty())).collect();
    let Some(duration) = duration_seconds.map(f64::from) else { return synced.first().copied() };
    DURATION_TOLERANCES.iter().find_map(|tolerance| {
        synced
            .iter()
            .filter(|t| t.duration.is_some_and(|d| (d - duration).abs() <= *tolerance))
            .min_by(|a, b| (a.duration.unwrap() - duration).abs().total_cmp(&(b.duration.unwrap() - duration).abs()))
            .copied()
    })
}

static BRACKETS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s*[(\[（【][^)\]）】]*[)\]）】]").unwrap());

/// 検索に使う曲名。「(From "THE FIRST TAKE")」「[Remastered]」「 - Orion」のような添え書きを外したものも試す。
pub fn search_titles(title: &str) -> Vec<String> {
    let without_brackets = BRACKETS.replace_all(title, "").trim().to_string();
    let before_dash = without_brackets.split(" - ").next().unwrap_or("").trim().to_string();
    let mut out: Vec<String> = Vec::new();
    for t in [title.to_string(), without_brackets, before_dash] {
        if !t.trim().is_empty() && !out.contains(&t) {
            out.push(t);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_titles_drop_decorations() {
        assert_eq!(search_titles("夜に駆ける (From \"THE FIRST TAKE\")"), ["夜に駆ける (From \"THE FIRST TAKE\")", "夜に駆ける"]);
        assert_eq!(search_titles("Song - Orion"), ["Song - Orion", "Song"]);
    }

    #[test]
    fn picks_the_closest_synced_track() {
        let track = |d: f64, synced: bool| Track { duration: Some(d), instrumental: false, synced_lyrics: synced.then(|| "[00:01.00]a".into()) };
        let tracks = [track(200.0, true), track(181.0, false), track(184.0, true)];
        assert_eq!(pick(&tracks, Some(182)).unwrap().duration, Some(184.0));
        assert!(pick(&tracks, Some(100)).is_none());
    }
}
