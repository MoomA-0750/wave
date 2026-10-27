//! 曲に重ねる MV（音を消した映像。Windows の MusicVideoPlayer と同じ）。音は Player が鳴らし、映像は音の位置に合わせ続ける。
//! 映像は GStreamer の gtk4paintablesink で描き、その絵（Paintable）を画面の gtk::Picture に渡す。

use std::cell::RefCell;
use std::sync::{Arc, Mutex};

use gst::prelude::*;
use wave_core::music::models::MusicVideo;
use wave_core::stream::range::RangeReader;
use wave_core::stream::resolver::{StreamKind, StreamResolver};

use crate::player::{Feed, configure_source};
use crate::runtime;

/// 映像と音のずれがこれより大きくなったら、映像を音の位置へ飛ばす。
const DRIFT_MS: i64 = 400;

pub struct VideoPlayer {
    playbin: gst::Element,
    resolver: StreamResolver,
    feed: Arc<Mutex<Feed>>,
    paintable: gtk::gdk::Paintable,
    loaded: RefCell<Option<String>>,
}

impl VideoPlayer {
    /// gtk4paintablesink が無ければ None（映像は出さない）。
    pub fn new(resolver: StreamResolver) -> Option<Self> {
        gst::init().ok()?;
        let sink = gst::ElementFactory::make("gtk4paintablesink").build().ok()?;
        let paintable = sink.property::<gtk::gdk::Paintable>("paintable");
        let playbin = gst::ElementFactory::make("playbin").property_from_str("flags", "video").property("video-sink", &sink).build().ok()?;
        let feed: Arc<Mutex<Feed>> = Arc::default();
        let setup_feed = feed.clone();
        playbin.connect("source-setup", false, move |values| {
            let source = values[1].get::<gst::Element>().ok()?.downcast::<gst_app::AppSrc>().ok()?;
            configure_source(&source, setup_feed.clone());
            None
        });
        Some(Self { playbin, resolver, feed, paintable, loaded: RefCell::default() })
    }

    pub fn paintable(&self) -> &gtk::gdk::Paintable {
        &self.paintable
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
        *self.feed.lock().unwrap() = Feed { reader: Some(reader), local: None, offset: 0, cache: None };
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
        let current = self.playbin.query_position::<gst::ClockTime>().map(|t| t.mseconds() as i64);
        if current.is_none_or(|c| (c - target as i64).abs() > DRIFT_MS) {
            self.playbin.seek_simple(gst::SeekFlags::FLUSH | gst::SeekFlags::ACCURATE, gst::ClockTime::from_mseconds(target)).ok();
        }
        self.playbin.set_state(if playing { gst::State::Playing } else { gst::State::Paused }).ok();
        true
    }

    pub fn stop(&self) {
        self.playbin.set_state(gst::State::Null).ok();
        *self.loaded.borrow_mut() = None;
    }
}

impl Drop for VideoPlayer {
    fn drop(&mut self) {
        self.playbin.set_state(gst::State::Null).ok();
    }
}
