//! 曲の歌詞を選ぶ（Windows の LyricsService・Android の LyricsRepository と同じ）。YouTube Music の時刻付き、無ければ LRCLIB の時刻付き、
//! それも無ければ YouTube Music の時刻なし。見つかった・無かった結果は曲ごとに覚える。失敗は覚えない。
//! 時刻付きを探すのに失敗して時刻なしを出したときも覚えない（一時の失敗で、時刻なしが残り続けないように）。
//! LRCLIB の候補にアーティスト名・曲名の一致するものが無ければ、曲のもう1つの言語での名前（別名）を YouTube Music から取って足す。
//! 別名は曲ごとにアプリを閉じるまで覚え、同じ曲で何度も頼まない。

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

use super::lrclib::{LrclibClient, LrclibResult, OtherNames};
use super::timed::{TimedLyricsClient, TimedResult};
use super::SongLyrics;
use crate::music::models::Song;
use crate::music::repository::MusicRepository;

pub const LRCLIB_SOURCE: &str = "LRCLIB";
const CACHE_SIZE: usize = 50;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LyricsResult {
    Found(SongLyrics),
    None,
    Failed,
}

#[derive(Clone)]
pub struct LyricsService {
    music: MusicRepository,
    lrclib: LrclibClient,
    timed: TimedLyricsClient,
    cache: Arc<Mutex<VecDeque<(String, LyricsResult)>>>,
    /// 曲（videoId）ごとの、もう1つの言語での名前（取れなかった曲は None）。通信の失敗は覚えない。
    other_names: Arc<Mutex<HashMap<String, Option<OtherNames>>>>,
}

impl LyricsService {
    pub fn new(music: MusicRepository, lrclib: LrclibClient, timed: TimedLyricsClient) -> Self {
        Self { music, lrclib, timed, cache: Arc::default(), other_names: Arc::default() }
    }

    pub async fn lyrics(&self, song: &Song) -> LyricsResult {
        if let Some(hit) = self.cache.lock().unwrap().iter().find(|(id, _)| *id == song.video_id).map(|(_, r)| r.clone()) {
            return hit;
        }
        let tab = self.music.lyrics_browse_id(&song.video_id).await;
        let browse_id = tab.as_ref().ok().cloned().flatten();
        // 時刻付きと時刻なしを同時に尋ねる（どちらも歌詞のタブの ID だけを送る）
        let (timed, plain) = match &browse_id {
            Some(id) => {
                let (t, p) = tokio::join!(self.timed.timed(id), self.music.plain_lyrics_of_tab(id));
                (Some(t), p)
            }
            None => (None, tab.map(|_| None)),
        };
        let mut synced = LrclibResult::NotFound;
        let timed_failed = matches!(timed, Some(TimedResult::Failed));
        let result = match timed {
            // YouTube Music で取れたら LRCLIB には聞かない
            Some(TimedResult::Found(lyrics)) => LyricsResult::Found(lyrics),
            _ => {
                if let Some(artist) = song.artists.first() {
                    let album = song.album.as_ref().map(|a| a.title.as_str());
                    synced = self.lrclib.synced_with_other_names(&song.title, &artist.name, album, song.duration_seconds, || self.other_names_of(&song.video_id)).await;
                }
                choose(&synced, plain)
            }
        };
        let provisional = matches!(result, LyricsResult::Found(SongLyrics::Plain { .. })) && (timed_failed || synced == LrclibResult::Failed);
        if result != LyricsResult::Failed && !provisional {
            let mut cache = self.cache.lock().unwrap();
            cache.push_back((song.video_id.clone(), result.clone()));
            while cache.len() > CACHE_SIZE {
                cache.pop_front();
            }
        }
        result
    }
}

impl LyricsService {
    /// 曲のもう1つの言語での名前。覚えていればそれを使い、無ければ YouTube Music に1回頼む。
    async fn other_names_of(&self, video_id: &str) -> Option<OtherNames> {
        if let Some(known) = self.other_names.lock().unwrap().get(video_id) {
            return known.clone();
        }
        let names = self.music.names_in_other_language(video_id).await.ok()?;
        self.other_names.lock().unwrap().insert(video_id.to_string(), names.clone());
        names
    }
}

/// 時刻付きを優先する。片方が失敗していて、もう片方に無ければ「分からない」（無いとは決めない）。
fn choose(synced: &LrclibResult, plain: crate::innertube::ApiResult<Option<SongLyrics>>) -> LyricsResult {
    if let LrclibResult::Found(lines) = synced {
        return LyricsResult::Found(SongLyrics::Synced { lines: lines.clone(), source: Some(LRCLIB_SOURCE.into()) });
    }
    match plain {
        Err(_) => LyricsResult::Failed,
        Ok(Some(text)) => LyricsResult::Found(text),
        Ok(None) if *synced == LrclibResult::Failed => LyricsResult::Failed,
        Ok(None) => LyricsResult::None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::innertube::Failure;
    use crate::lyrics::LyricLine;

    #[test]
    fn prefers_synced_and_does_not_conclude_none_on_failure() {
        let line = vec![LyricLine { start_ms: 1, text: "a".into() }];
        let plain = || Ok(Some(SongLyrics::Plain { text: "t".into(), source: None }));
        assert!(matches!(choose(&LrclibResult::Found(line), plain()), LyricsResult::Found(SongLyrics::Synced { .. })));
        assert!(matches!(choose(&LrclibResult::NotFound, plain()), LyricsResult::Found(SongLyrics::Plain { .. })));
        assert_eq!(choose(&LrclibResult::NotFound, Ok(None)), LyricsResult::None);
        assert_eq!(choose(&LrclibResult::Failed, Ok(None)), LyricsResult::Failed);
        assert_eq!(choose(&LrclibResult::NotFound, Err(Failure::Network)), LyricsResult::Failed);
    }
}
