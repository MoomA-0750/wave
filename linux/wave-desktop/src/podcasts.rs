//! ポッドキャストのうち RSS の番組（ADR-0012。Windows の FeedStore・FeedLibrary と同じ）: 登録・聴いたところ（~/.local/share/wave/podcast-feeds.json）、
//! 読んだ番組の置き場、鳴らしているエピソードの位置の書き留め。YouTube の番組は MusicRepository がそのまま読む。

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;

use wave_core::music::feed::{self, FeedClient, FeedShow};
use wave_core::music::feed_library::{FeedLibrary, FeedLibraryState};

use crate::playback::Playback;
use crate::runtime;

/// 鳴らしている間、聴いたところを書き留める間隔。
const REMEMBER_EVERY: Duration = Duration::from_secs(10);

pub struct Podcasts {
    pub client: FeedClient,
    pub library: Arc<FeedLibrary>,
    /// 読んだ番組（フィードの URL ごと）。開き直すたびに読まない。
    shows: RefCell<HashMap<String, Arc<FeedShow>>>,
}

fn path() -> std::path::PathBuf {
    glib::user_data_dir().join("wave").join("podcast-feeds.json")
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

impl Podcasts {
    pub fn new(region: &str) -> Rc<Self> {
        let initial = std::fs::read_to_string(path()).ok().and_then(|t| FeedLibraryState::decode(&t)).unwrap_or_default();
        let library = Arc::new(FeedLibrary::new(initial, now_ms, |state: &FeedLibraryState| {
            let path = path();
            let written = std::fs::create_dir_all(path.parent().expect("data path has a parent")).and_then(|_| std::fs::write(&path, state.encode()));
            if let Err(e) = written {
                eprintln!("podcast feeds save failed: {}", e.kind());
            }
        }));
        let client = FeedClient::new(region, concat!("wave/", env!("CARGO_PKG_VERSION")));
        Rc::new(Self { client, library, shows: RefCell::default() })
    }

    /// 番組を読む（読んであれば置いてあるもの。refresh なら読み直す）。登録している番組なら、題・絵を覚え直す。
    pub async fn show(&self, feed_url: &str, refresh: bool) -> Option<Arc<FeedShow>> {
        if !refresh && let Some(show) = self.shows.borrow().get(feed_url) {
            return Some(show.clone());
        }
        let (client, url) = (self.client.clone(), feed_url.to_string());
        let show = Arc::new(runtime::run(async move { client.show(&url).await }).await.ok()?);
        self.library.refresh(&show);
        self.shows.borrow_mut().insert(feed_url.to_string(), show.clone());
        Some(show)
    }

    /// 登録している番組をまとめて読み直す（新しいエピソードに混ぜる）。
    pub async fn subscribed_shows(&self) -> Vec<Arc<FeedShow>> {
        let urls: Vec<String> = self.library.state().subscriptions.iter().map(|s| s.feed_url.clone()).collect();
        if urls.is_empty() {
            return Vec::new();
        }
        let client = self.client.clone();
        let shows: Vec<Arc<FeedShow>> = runtime::run(async move { client.shows(&urls).await }).await.into_iter().map(Arc::new).collect();
        for show in &shows {
            self.library.refresh(show);
            self.shows.borrow_mut().insert(show.feed_url.clone(), show.clone());
        }
        shows
    }

    /// RSS のエピソードを鳴らしている間、聴いたところを書き留める。
    pub fn track(self: &Rc<Self>, playback: &Rc<Playback>) {
        let (weak, playback) = (Rc::downgrade(self), playback.clone());
        glib::timeout_add_local(REMEMBER_EVERY, move || {
            let Some(this) = weak.upgrade() else { return glib::ControlFlow::Break };
            if let Some(song) = playback.current()
                && let Some(url) = feed::audio_url_of(&song.video_id)
                && playback.player().is_playing()
            {
                let player = playback.player();
                let position = (player.position_ms().unwrap_or(0) / 1000) as i32;
                let duration = player.duration_ms().map(|d| (d / 1000) as i32).or(song.duration_seconds.map(|d| d as i32));
                this.library.remember(url, position, duration);
            }
            glib::ControlFlow::Continue
        });
    }

    /// 続きから流す位置（ミリ秒）。聴き終えていれば頭から。
    pub fn resume_ms(&self, audio_url: &str) -> u64 {
        self.library.position(audio_url).map_or(0, |p| p.resume_seconds().max(0) as u64 * 1000)
    }
}
