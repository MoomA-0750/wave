//! 曲に重ねる MV（音を消した映像。Windows の MusicVideoPlayer と同じ）。音は Player が鳴らし、映像は音の位置に合わせ続ける。
//! 映像の出口（sink）は画面の側が渡す（GTK 版は gtk4paintablesink、QML 版は appsink で絵を受け取って描く）。

use std::cell::{Cell, RefCell};
use std::time::{Duration, Instant};
use std::sync::{Arc, Mutex};

use gst::prelude::*;
use wave_core::music::models::MusicVideo;
use wave_core::stream::range::RangeReader;
use wave_core::stream::resolver::{StreamKind, StreamResolver};

use crate::player::{Feed, configure_source};
use crate::runtime;

/// 映像と音のずれがこれより大きくなったら、映像を音の位置へ飛ばす。
const DRIFT_MS: i64 = 400;
/// シークしてから、次のシークを考えるまで待つ時間。解くのが遅い機械（cramgent の M1 のソフトの解き方など）では、
/// 指定の位置に着くまでにずれが DRIFT_MS を超え、待たずに見るとシークをやり直し続けて絵が1枚も出なかった。
const SEEK_SETTLE: Duration = Duration::from_millis(2500);
/// 鳴っている間にシークするときは、着くまでの間に音が進むぶん先へ飛ぶ。
const SEEK_LEAD_MS: u64 = 600;

pub struct VideoPlayer {
    playbin: gst::Element,
    resolver: StreamResolver,
    feed: Arc<Mutex<Feed>>,
    loaded: RefCell<Option<String>>,
    /// 最後にシークした時刻（落ち着くまで次のシークをしない）。
    last_seek: Cell<Option<Instant>>,
}

impl VideoPlayer {
    /// sink に映像を流す playbin を作る（作れなければ None。映像は出さない）。
    pub fn with_sink(resolver: StreamResolver, sink: &gst::Element) -> Option<Self> {
        gst::init().ok()?;
        let playbin = gst::ElementFactory::make("playbin").property_from_str("flags", "video").property("video-sink", sink).build().ok()?;
        let feed: Arc<Mutex<Feed>> = Arc::default();
        let setup_feed = feed.clone();
        playbin.connect("source-setup", false, move |values| {
            let source = values[1].get::<gst::Element>().ok()?.downcast::<gst_app::AppSrc>().ok()?;
            configure_source(&source, setup_feed.clone());
            None
        });
        Some(Self { playbin, resolver, feed, loaded: RefCell::default(), last_seek: Cell::new(None) })
    }

    /// 映像を読み込む（同じ映像なら何もしない）。読めなければ false。
    pub async fn load(&self, video: &MusicVideo) -> bool {
        if self.loaded.borrow().as_deref() == Some(video.video_id.as_str()) {
            return true;
        }
        self.stop();
        let (resolver, id) = (self.resolver.clone(), video.video_id.clone());
        let reader = runtime::run(async move {
            let stream = resolver.resolve_kind(&id, StreamKind::Video).await.ok()?;
            RangeReader::open(stream).await.ok()
        })
        .await;
        let Some(reader) = reader else { return false };
        *self.feed.lock().unwrap() = Feed { reader: Some(reader), local: None, offset: 0, cache: None, audio: None, source: None };
        self.playbin.set_property("uri", "appsrc://");
        self.playbin.set_state(gst::State::Paused).ok();
        *self.loaded.borrow_mut() = Some(video.video_id.clone());
        true
    }

    /// 音の位置と再生の状態に映像を合わせる。映像に当たる部分が無ければ false（画面では隠す）。
    pub fn sync(&self, video: &MusicVideo, audio_position_ms: u64, playing: bool) -> bool {
        if self.loaded.borrow().as_deref() != Some(video.video_id.as_str()) {
            return false;
        }
        let Some(target) = video.video_position_of(audio_position_ms) else {
            self.playbin.set_state(gst::State::Paused).ok();
            return false;
        };
        let settling = self.last_seek.get().is_some_and(|t| t.elapsed() < SEEK_SETTLE);
        let current = self.playbin.query_position::<gst::ClockTime>().map(|t| t.mseconds() as i64);
        if !settling && current.is_none_or(|c| (c - target as i64).abs() > DRIFT_MS) {
            let to = if playing { target + SEEK_LEAD_MS } else { target };
            self.playbin.seek_simple(gst::SeekFlags::FLUSH | gst::SeekFlags::ACCURATE, gst::ClockTime::from_mseconds(to)).ok();
            self.last_seek.set(Some(Instant::now()));
        }
        self.playbin.set_state(if playing { gst::State::Playing } else { gst::State::Paused }).ok();
        true
    }

    pub fn stop(&self) {
        self.playbin.set_state(gst::State::Null).ok();
        *self.loaded.borrow_mut() = None;
        self.last_seek.set(None);
    }
}

impl Drop for VideoPlayer {
    fn drop(&mut self) {
        self.playbin.set_state(gst::State::Null).ok();
    }
}
