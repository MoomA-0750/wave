//! GStreamer（playbin）で曲を鳴らす。再生の URL は wave-core の StreamResolver で解く。
//! googlevideo は範囲を付けない取り方を 403 で拒むので、URL を playbin に渡さず、core の RangeReader で
//! 範囲を区切って取ったものを appsrc で流し込む（シークされたらその位置から取り直す）。
//! 取り終えた曲（ダウンロード）は、通信せずにファイルから同じように流し込む。
//! URL は署名付きなので、ログ・画面に出さない。

use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use gst::prelude::*;
use wave_core::stream::range::RangeReader;
use wave_core::stream::resolver::{StreamFailure, StreamResolver};

use crate::runtime;

pub struct Player {
    playbin: gst::Element,
    resolver: StreamResolver,
    /// 取り終えた曲の置き場（あればそこから鳴らす）。
    downloads: Option<Arc<wave_core::download::store::DownloadStore>>,
    /// 今流している曲の読み手と、次に読む位置（appsrc の糸から読み書きする）。
    feed: Arc<Mutex<Feed>>,
    /// 読み込みが済んだら（ASYNC_DONE）飛ぶ位置と、そのあと鳴らすか（引き取った曲を途中から鳴らす）。
    pending_start: Arc<Mutex<Option<(u64, bool)>>>,
}

#[derive(Default)]
pub(crate) struct Feed {
    pub(crate) reader: Option<RangeReader>,
    /// 取り終えた曲のファイルと長さ（あれば reader の代わりに読む）。
    pub(crate) local: Option<(PathBuf, u64)>,
    pub(crate) offset: u64,
    /// 最後に取った塊（始まりの位置と中身）。demuxer は小さく何度も求めるので、ここから切り出す。
    pub(crate) cache: Option<(u64, Vec<u8>)>,
}

impl Feed {
    /// offset から length バイト（終わりで切れる）。塊に無ければ範囲で取り直す。
    /// 流しているものの長さ。
    pub(crate) fn length(&self) -> u64 {
        self.local.as_ref().map(|(_, l)| *l).or_else(|| self.reader.as_ref().map(RangeReader::length)).unwrap_or(0)
    }

    fn read(&mut self, offset: u64, length: u64) -> Result<Vec<u8>, wave_core::stream::range::RangeError> {
        if let Some((path, total)) = &self.local {
            let length = length.min(total.saturating_sub(offset)) as usize;
            let mut bytes = vec![0; length];
            let read = std::fs::File::open(path).and_then(|mut f| {
                f.seek(SeekFrom::Start(offset))?;
                f.read_exact(&mut bytes)
            });
            return read.map(|_| bytes).map_err(|_| wave_core::stream::range::RangeError::Network);
        }
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
    pub fn new(resolver: StreamResolver, downloads: Option<Arc<wave_core::download::store::DownloadStore>>) -> Self {
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
        Self { playbin, resolver, downloads, feed, pending_start: Arc::default() }
    }

    /// その曲を頭から鳴らす。取り終えていればファイルから、ほかは解いて範囲で取る。
    #[allow(dead_code)] // examples/play.rs が使う
    pub async fn play(&self, video_id: &str) -> Result<(), StreamFailure> {
        self.play_from(video_id, 0, true).await
    }

    /// その曲を start_ms から鳴らす（playing でなければ止めたまま用意する）。読み込みが済むまでは飛べないので、済んでから飛ぶ。
    pub async fn play_from(&self, video_id: &str, start_ms: u64, playing: bool) -> Result<(), StreamFailure> {
        let immediate = start_ms == 0 && playing;
        *self.pending_start.lock().unwrap() = (!immediate).then_some((start_ms, playing));
        let target = if immediate { gst::State::Playing } else { gst::State::Paused };
        if let Some(store) = &self.downloads
            && let (Some(file), Some(path)) = (store.completed(video_id), store.bytes_path(video_id))
        {
            self.playbin.set_state(gst::State::Null).ok();
            eprintln!("play source=download");
            *self.feed.lock().unwrap() = Feed { reader: None, local: Some((path, file.length)), offset: 0, cache: None };
            self.playbin.set_property("uri", "appsrc://");
            self.playbin.set_state(target).map_err(|_| StreamFailure::Unplayable)?;
            return Ok(());
        }
        let resolver = self.resolver.clone();
        let id = video_id.to_string();
        let reader = runtime::run(async move {
            let stream = resolver.resolve(&id).await?;
            RangeReader::open(stream).await.map_err(|_| StreamFailure::RejectedUrl)
        })
        .await?;
        self.playbin.set_state(gst::State::Null).ok();
        *self.feed.lock().unwrap() = Feed { reader: Some(reader), local: None, offset: 0, cache: None };
        self.playbin.set_property("uri", "appsrc://");
        self.playbin.set_state(target).map_err(|_| StreamFailure::Unplayable)?;
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
        let (pending, playbin) = (self.pending_start.clone(), self.playbin.clone());
        self.playbin
            .bus()
            .expect("playbin has a bus")
            .add_watch_local(move |_, message| {
                match message.view() {
                    gst::MessageView::Eos(_) => on_end(false),
                    // 途中から鳴らす曲: 読み込みが済んだので飛んでから鳴らす
                    gst::MessageView::AsyncDone(_) => {
                        if let Some((position, playing)) = pending.lock().unwrap().take() {
                            if position > 0 {
                                playbin.seek_simple(gst::SeekFlags::FLUSH | gst::SeekFlags::KEY_UNIT, gst::ClockTime::from_mseconds(position)).ok();
                            }
                            if playing {
                                playbin.set_state(gst::State::Playing).ok();
                            }
                        }
                    }
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
    let length = feed.lock().unwrap().length();
    source.set_format(gst::Format::Bytes);
    source.set_stream_type(gst_app::AppStreamType::RandomAccess);
    source.set_size(length as i64);
    let seek_feed = feed.clone();
    source.set_callbacks(
        gst_app::AppSrcCallbacks::builder()
            .need_data(move |source, length| {
                let mut feed = feed.lock().unwrap();
                let offset = feed.offset;
                if offset >= feed.length() {
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
