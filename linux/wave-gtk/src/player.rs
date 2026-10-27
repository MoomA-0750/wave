//! GStreamer（playbin）で曲を鳴らす。再生の URL は wave-core の StreamResolver で解く。
//! googlevideo は範囲を付けない取り方を 403 で拒むので、URL を playbin に渡さず、core の RangeReader で
//! 範囲を区切って取ったものを appsrc で流し込む（シークされたらその位置から取り直す）。
//! URL は署名付きなので、ログ・画面に出さない。

use std::sync::{Arc, Mutex};

use gst::prelude::*;
use wave_core::stream::range::RangeReader;
use wave_core::stream::resolver::{StreamFailure, StreamResolver};

use crate::runtime;

pub struct Player {
    playbin: gst::Element,
    resolver: StreamResolver,
    /// 今流している曲の読み手と、次に読む位置（appsrc の糸から読み書きする）。
    feed: Arc<Mutex<Feed>>,
}

#[derive(Default)]
pub(crate) struct Feed {
    pub(crate) reader: Option<RangeReader>,
    pub(crate) offset: u64,
    /// 最後に取った塊（始まりの位置と中身）。demuxer は小さく何度も求めるので、ここから切り出す。
    pub(crate) cache: Option<(u64, Vec<u8>)>,
}

impl Feed {
    /// offset から length バイト（終わりで切れる）。塊に無ければ範囲で取り直す。
    fn read(&mut self, offset: u64, length: u64) -> Result<Vec<u8>, wave_core::stream::range::RangeError> {
        let reader = self.reader.clone().expect("reader is set before playing");
        let length = length.min(reader.length().saturating_sub(offset));
        let cached = |cache: &Option<(u64, Vec<u8>)>| {
            cache.as_ref().filter(|(start, data)| offset >= *start && offset + length <= start + data.len() as u64).map(|(start, data)| {
                let from = (offset - start) as usize;
                data[from..from + length as usize].to_vec()
            })
        };
        if let Some(bytes) = cached(&self.cache) {
            return Ok(bytes);
        }
        let chunk = runtime::block_on(reader.read_chunk(offset, length))?;
        self.cache = Some((offset, chunk));
        Ok(cached(&self.cache).unwrap_or_default())
    }
}

impl Player {
    /// 音の出口は既定（PulseAudio・PipeWire）。WAVE_AUDIO_SINK があればその要素（確かめ用の「fakesink sync=true」など）。
    pub fn new(resolver: StreamResolver) -> Self {
        gst::init().expect("GStreamer");
        let playbin = gst::ElementFactory::make("playbin").property_from_str("flags", "audio").build().expect("playbin");
        if let Ok(sink) = std::env::var("WAVE_AUDIO_SINK") {
            // 「fakesink sync=true」のように属性も書ける
            playbin.set_property("audio-sink", gst::parse::launch(&sink).expect("audio sink"));
        }
        let feed: Arc<Mutex<Feed>> = Arc::default();
        let setup_feed = feed.clone();
        playbin.connect("source-setup", false, move |values| {
            let source = values[1].get::<gst::Element>().ok()?.downcast::<gst_app::AppSrc>().ok()?;
            configure_source(&source, setup_feed.clone());
            None
        });
        Self { playbin, resolver, feed }
    }

    /// その曲を解いて頭から鳴らす。
    pub async fn play(&self, video_id: &str) -> Result<(), StreamFailure> {
        let resolver = self.resolver.clone();
        let id = video_id.to_string();
        let reader = runtime::run(async move {
            let stream = resolver.resolve(&id).await?;
            RangeReader::open(stream).await.map_err(|_| StreamFailure::RejectedUrl)
        })
        .await?;
        self.playbin.set_state(gst::State::Null).ok();
        *self.feed.lock().unwrap() = Feed { reader: Some(reader), offset: 0, cache: None };
        self.playbin.set_property("uri", "appsrc://");
        self.playbin.set_state(gst::State::Playing).map_err(|_| StreamFailure::Unplayable)?;
        Ok(())
    }

    pub fn toggle(&self) {
        let (_, state, _) = self.playbin.state(gst::ClockTime::ZERO);
        let next = if state == gst::State::Playing { gst::State::Paused } else { gst::State::Playing };
        self.playbin.set_state(next).ok();
    }

    pub fn is_playing(&self) -> bool {
        self.playbin.current_state() == gst::State::Playing
    }

    pub fn position_ms(&self) -> Option<u64> {
        self.playbin.query_position::<gst::ClockTime>().map(|t| t.mseconds())
    }

    pub fn duration_ms(&self) -> Option<u64> {
        self.playbin.query_duration::<gst::ClockTime>().map(|t| t.mseconds())
    }

    /// 0〜1 の音量。
    pub fn set_volume(&self, volume: f64) {
        self.playbin.set_property("volume", volume.clamp(0.0, 1.0));
    }

    pub fn volume(&self) -> f64 {
        self.playbin.property("volume")
    }

    /// 曲が終わった・流せなくなったときに主スレッドで呼ぶ（返したものを持っている間だけ）。
    pub fn watch(&self, on_end: impl Fn(bool) + 'static) -> gst::bus::BusWatchGuard {
        self.playbin
            .bus()
            .expect("playbin has a bus")
            .add_watch_local(move |_, message| {
                match message.view() {
                    gst::MessageView::Eos(_) => on_end(false),
                    gst::MessageView::Error(e) => {
                        eprintln!("playback error: {}", e.error());
                        on_end(true);
                    }
                    _ => {}
                }
                gtk::glib::ControlFlow::Continue
            })
            .expect("one bus watch")
    }

    pub fn stop(&self) {
        self.playbin.set_state(gst::State::Null).ok();
    }

    pub fn seek_ms(&self, position: u64) {
        self.playbin.seek_simple(gst::SeekFlags::FLUSH | gst::SeekFlags::KEY_UNIT, gst::ClockTime::from_mseconds(position)).ok();
    }
}

/// appsrc を「どこからでも読める長さの決まった流れ」にし、求められた分を範囲で取って渡す。
pub(crate) fn configure_source(source: &gst_app::AppSrc, feed: Arc<Mutex<Feed>>) {
    let length = feed.lock().unwrap().reader.as_ref().map_or(0, RangeReader::length);
    source.set_format(gst::Format::Bytes);
    source.set_stream_type(gst_app::AppStreamType::RandomAccess);
    source.set_size(length as i64);
    let seek_feed = feed.clone();
    source.set_callbacks(
        gst_app::AppSrcCallbacks::builder()
            .need_data(move |source, length| {
                let mut feed = feed.lock().unwrap();
                let offset = feed.offset;
                if feed.reader.as_ref().is_none_or(|r| offset >= r.length()) {
                    let _ = source.end_of_stream();
                    return;
                }
                // 流しで読むとき（push）は長さの指定が無い（u32::MAX）ので、塊の大きさで渡す
                let want = if length == u32::MAX { wave_core::stream::range::CHUNK_SIZE } else { length as u64 };
                match feed.read(offset, want) {
                    Ok(bytes) => {
                        feed.offset = offset + bytes.len() as u64;
                        drop(feed);
                        let mut buffer = gst::Buffer::from_mut_slice(bytes);
                        buffer.get_mut().expect("new buffer").set_offset(offset);
                        let _ = source.push_buffer(buffer);
                    }
                    Err(e) => {
                        eprintln!("audio range failed at={offset}: {e}");
                        drop(feed);
                        let _ = source.end_of_stream();
                    }
                }
            })
            .seek_data(move |_, offset| {
                seek_feed.lock().unwrap().offset = offset;
                true
            })
            .build(),
    );
}

impl Drop for Player {
    fn drop(&mut self) {
        self.playbin.set_state(gst::State::Null).ok();
    }
}
