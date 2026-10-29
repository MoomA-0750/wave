//! GStreamer（playbin）で曲を鳴らす。再生の URL は wave-core の StreamResolver で解く。
//! googlevideo は範囲を付けない取り方を 403 で拒むので、URL を playbin に渡さず、core の RangeReader で
//! 範囲を区切って取ったものを appsrc で流し込む（シークされたらその位置から取り直す）。
//! 取り終えた曲（ダウンロード）は、通信せずにファイルから同じように流し込む。
//! URL は署名付きなので、ログ・画面に出さない。

use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use gst::prelude::*;
use wave_core::stream::audio_cache::AudioCache;
use wave_core::stream::range::RangeReader;
use wave_core::stream::resolver::{StreamFailure, StreamResolver};

use crate::runtime;

/// 残した分から鳴らし始めるのに要る頭の大きさ（これより少なければ、URL を調べてから鳴らす）。
const MIN_CACHED_START: u64 = 256 * 1024;

pub struct Player {
    playbin: gst::Element,
    resolver: StreamResolver,
    /// 取り終えた曲の置き場（あればそこから鳴らす）。
    downloads: Option<Arc<wave_core::download::store::DownloadStore>>,
    /// 今流している曲の読み手と、次に読む位置（appsrc の糸から読み書きする）。
    feed: Arc<Mutex<Feed>>,
    /// 読み込みが済んだら（ASYNC_DONE）飛ぶ位置と、そのあと鳴らすか（引き取った曲を途中から鳴らす）。
    pending_start: Arc<Mutex<Option<(u64, bool)>>>,
    /// ライブ配信の HLS を解く口（無ければライブは流せない）。
    live: Option<wave_core::stream::live::LiveClient>,
    /// 今の曲を解いた結果（再生履歴を送るのに使う。取った曲から鳴らしたときは裏で解き直す）。
    current_stream: Arc<Mutex<Option<wave_core::stream::resolver::ResolvedStream>>>,
    /// RSS のエピソードの続きから流す位置（音声の URL → ミリ秒）。
    external_resume: std::cell::RefCell<Option<Box<dyn Fn(&str) -> u64>>>,
    /// 曲の音を残す置き場（無ければ残さない）。
    audio_cache: Option<Arc<AudioCache>>,
}

#[derive(Default)]
pub(crate) struct Feed {
    pub(crate) reader: Option<RangeReader>,
    /// 取り終えた曲のファイルと長さ（あれば reader の代わりに読む）。
    pub(crate) local: Option<(PathBuf, u64)>,
    pub(crate) offset: u64,
    /// 最後に取った塊（始まりの位置と中身）。demuxer は小さく何度も求めるので、ここから切り出す。
    pub(crate) cache: Option<(u64, Vec<u8>)>,
    /// 曲の音を残す置き場と、その曲の ID（残っている分はそこから読み、取った分は足す）。
    pub(crate) audio: Option<(Arc<AudioCache>, String)>,
    /// 残した分から鳴らし始めた曲: 続きが要ったときに URL を調べる口と、同じ形式か確かめる itag・長さ。
    pub(crate) source: Option<(StreamResolver, u32, u64)>,
}

impl Feed {
    /// offset から length バイト（終わりで切れる）。塊に無ければ範囲で取り直す。
    /// 流しているものの長さ。
    pub(crate) fn length(&self) -> u64 {
        self.local
            .as_ref()
            .map(|(_, l)| *l)
            .or_else(|| self.reader.as_ref().map(RangeReader::length))
            .or_else(|| self.source.as_ref().map(|(_, _, l)| *l))
            .unwrap_or(0)
    }

    /// 読み手が無ければ（残した分から鳴らし始めた曲）、今 URL を調べる。残した形式と違えば読めない。
    fn ensure_reader(&mut self) -> Result<RangeReader, wave_core::stream::range::RangeError> {
        if let Some(reader) = &self.reader {
            return Ok(reader.clone());
        }
        let (resolver, itag, length) = self.source.clone().ok_or(wave_core::stream::range::RangeError::Network)?;
        let video_id = self.audio.as_ref().map(|(_, id)| id.clone()).ok_or(wave_core::stream::range::RangeError::Network)?;
        let reader = runtime::block_on(async move {
            let stream = resolver.resolve(&video_id).await.map_err(|_| wave_core::stream::range::RangeError::Network)?;
            if stream.itag != itag || stream.content_length != Some(length) {
                return Err(wave_core::stream::range::RangeError::Rejected(0));
            }
            RangeReader::open(stream).await
        })?;
        self.reader = Some(reader.clone());
        Ok(reader)
    }

    /// 残した分から読む。足りなければ、残した分の続きから取って足してから読む（頭から途切れずに残す）。
    /// 残した分より遠く先を求められた（シーク）ときは None（残さずにネットから読む）。
    fn read_cached(&mut self, offset: u64, length: u64) -> Option<Result<Vec<u8>, wave_core::stream::range::RangeError>> {
        let (cache, id) = self.audio.clone()?;
        let total = self.length();
        let length = length.min(total.saturating_sub(offset));
        if length == 0 {
            return Some(Ok(Vec::new()));
        }
        let end = offset + length - 1;
        loop {
            if let Some(bytes) = cache.read(&id, offset, end) {
                return Some(Ok(bytes));
            }
            let have = cache.entry(&id).map_or(0, |e| e.1);
            if have >= total || offset > have + wave_core::stream::audio_cache::GAP_FILL_BYTES {
                return None;
            }
            let reader = match self.ensure_reader() {
                Ok(reader) => reader,
                Err(e) => return Some(Err(e)),
            };
            let chunk = match runtime::block_on(reader.read_chunk(have, end + 1 - have)) {
                Ok(chunk) if !chunk.is_empty() => chunk,
                Ok(_) => return None,
                Err(e) => return Some(Err(e)),
            };
            let before = have;
            cache.append(&id, have, &chunk);
            // 足せなかった（形式が替わった等）なら、残さずに読む
            if cache.entry(&id).map_or(0, |e| e.1) == before {
                return None;
            }
        }
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
        if let Some(result) = self.read_cached(offset, length) {
            return result;
        }
        let reader = self.ensure_reader()?;
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
        Self { playbin, resolver, downloads, feed, pending_start: Arc::default(), live: None, current_stream: Arc::default(), external_resume: Default::default(), audio_cache: None }
    }

    /// ライブ配信を流せるようにする（HLS を GStreamer に直に読ませる）。
    pub fn with_live(mut self, live: wave_core::stream::live::LiveClient) -> Self {
        self.live = Some(live);
        self
    }

    /// 曲の音を残す（頭が残っている曲は、URL を調べずにすぐ鳴らし始める）。
    pub fn with_audio_cache(mut self, cache: Arc<AudioCache>) -> Self {
        self.audio_cache = Some(cache);
        self
    }

    /// 音の取得を拒まれた（403 など）。次は解読の準備を取り直す。
    pub async fn on_stream_rejected(&self) {
        let resolver = self.resolver.clone();
        runtime::run(async move { resolver.on_stream_rejected().await }).await;
    }

    /// 取り終えた曲か（通信せずにファイルから鳴らす）。
    pub fn is_downloaded(&self, video_id: &str) -> bool {
        self.downloads.as_ref().is_some_and(|store| store.completed(video_id).is_some())
    }

    /// ログインの世代。
    pub fn login_generation(&self) -> u64 {
        self.resolver.generation()
    }

    /// Premium 音質を使えるか（分からなければ None）。
    pub async fn premium_audio(&self) -> Option<bool> {
        let resolver = self.resolver.clone();
        runtime::run(async move { resolver.premium_audio().await }).await
    }

    /// ログアウトのとき: 残した曲の音を消す。
    pub fn clear_audio_cache(&self) {
        if let Some(cache) = &self.audio_cache {
            cache.clear();
        }
    }

    /// 次の曲の頭（1MB、30秒ほど）を先に取って残しておく（曲送りですぐ鳴らす）。
    pub fn warm(&self, video_id: &str) {
        let Some(cache) = self.audio_cache.clone() else { return };
        if !wave_core::stream::audio_cache::is_safe_id(video_id) || cache.entry(video_id).is_some_and(|e| e.1 >= wave_core::stream::audio_cache::WARM_BYTES) {
            return;
        }
        let (resolver, id) = (self.resolver.clone(), video_id.to_string());
        runtime::spawn(async move {
            let Ok(stream) = resolver.resolve(&id).await else { return };
            cache.begin(&stream);
            let Ok(reader) = RangeReader::open(stream).await else { return };
            let target = wave_core::stream::audio_cache::WARM_BYTES.min(reader.length());
            loop {
                let have = cache.entry(&id).map_or(0, |e| e.1);
                if have >= target {
                    break;
                }
                let Ok(chunk) = reader.read_chunk(have, target - have).await else { break };
                if chunk.is_empty() {
                    break;
                }
                cache.append(&id, have, &chunk);
                if cache.entry(&id).map_or(0, |e| e.1) == have {
                    break;
                }
            }
        });
    }

    /// RSS のエピソードを続きから流すための、聴いたところの引き方。
    pub fn set_external_resume(&self, resume: impl Fn(&str) -> u64 + 'static) {
        *self.external_resume.borrow_mut() = Some(Box::new(resume));
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
        *self.current_stream.lock().unwrap() = None;
        // RSS のエピソード（ext:）は、音声の URL を GStreamer に直に読ませる（続きから）
        if let Some(url) = wave_core::music::feed::audio_url_of(video_id) {
            let start = if start_ms > 0 { start_ms } else { self.external_resume.borrow().as_ref().map_or(0, |f| f(url)) };
            *self.pending_start.lock().unwrap() = (start > 0 || !playing).then_some((start, playing));
            self.playbin.set_state(gst::State::Null).ok();
            eprintln!("play source=feed");
            self.playbin.set_property("uri", url);
            let state = if start > 0 || !playing { gst::State::Paused } else { gst::State::Playing };
            self.playbin.set_state(state).map_err(|_| StreamFailure::Unplayable)?;
            return Ok(());
        }
        if let Some(store) = &self.downloads
            && let (Some(file), Some(path)) = (store.completed(video_id), store.bytes_path(video_id))
        {
            self.playbin.set_state(gst::State::Null).ok();
            eprintln!("play source=download");
            *self.feed.lock().unwrap() = Feed { reader: None, local: Some((path, file.length)), offset: 0, cache: None, audio: None, source: None };
            self.playbin.set_property("uri", "appsrc://");
            self.playbin.set_state(target).map_err(|_| StreamFailure::Unplayable)?;
            // 再生履歴のために、裏で解いておく（通信できなければ送らないだけ）
            let (resolver, id, slot) = (self.resolver.clone(), video_id.to_string(), self.current_stream.clone());
            runtime::spawn(async move {
                if let Ok(stream) = resolver.resolve(&id).await {
                    let mut slot = slot.lock().unwrap();
                    if slot.is_none() {
                        *slot = Some(stream);
                    }
                }
            });
            return Ok(());
        }
        // 頭が残っている曲は、URL を調べずにすぐ鳴らし始める（続きが要ったら調べる。再生履歴のために裏でも調べておく）
        if let Some(cache) = &self.audio_cache
            && let Some((format, cached)) = cache.entry(video_id)
            && cached >= MIN_CACHED_START.min(format.length)
        {
            self.playbin.set_state(gst::State::Null).ok();
            eprintln!("play source=cache");
            *self.feed.lock().unwrap() = Feed {
                reader: None,
                local: None,
                offset: 0,
                cache: None,
                audio: Some((cache.clone(), video_id.to_string())),
                source: Some((self.resolver.clone(), format.itag, format.length)),
            };
            self.playbin.set_property("uri", "appsrc://");
            self.playbin.set_state(target).map_err(|_| StreamFailure::Unplayable)?;
            let (resolver, id, slot) = (self.resolver.clone(), video_id.to_string(), self.current_stream.clone());
            runtime::spawn(async move {
                if let Ok(stream) = resolver.resolve(&id).await {
                    *slot.lock().unwrap() = Some(stream);
                }
            });
            return Ok(());
        }
        let resolver = self.resolver.clone();
        let id = video_id.to_string();
        let slot = self.current_stream.clone();
        let audio_cache = self.audio_cache.clone();
        let opened = runtime::run(async move {
            let stream = resolver.resolve(&id).await?;
            *slot.lock().unwrap() = Some(stream.clone());
            if let Some(cache) = &audio_cache
                && !stream.video_id.is_empty()
            {
                cache.begin(&stream);
            }
            RangeReader::open(stream).await.map_err(|_| StreamFailure::RejectedUrl)
        })
        .await;
        let reader = match opened {
            // ライブ配信は HLS で流す（長さもシークも無い。途中の位置へは飛ばない）
            Err(StreamFailure::LiveStream) if self.live.is_some() => {
                let live = self.live.clone().expect("checked above");
                let id = video_id.to_string();
                let stream = runtime::run(async move { live.resolve(&id, false).await }).await.map_err(|_| StreamFailure::LiveStream)?;
                *self.pending_start.lock().unwrap() = None;
                self.playbin.set_state(gst::State::Null).ok();
                eprintln!("play source=live");
                self.playbin.set_property("uri", stream.reveal_url());
                self.playbin.set_state(if playing { gst::State::Playing } else { gst::State::Paused }).map_err(|_| StreamFailure::Unplayable)?;
                return Ok(());
            }
            other => other?,
        };
        self.playbin.set_state(gst::State::Null).ok();
        let audio = self.audio_cache.clone().filter(|c| c.entry(video_id).is_some()).map(|c| (c, video_id.to_string()));
        *self.feed.lock().unwrap() = Feed { reader: Some(reader), local: None, offset: 0, cache: None, audio, source: None };
        self.playbin.set_property("uri", "appsrc://");
        self.playbin.set_state(target).map_err(|_| StreamFailure::Unplayable)?;
        Ok(())
    }

    /// 今の曲を解いた結果（video_id の曲のときだけ）。
    pub fn current_stream(&self, video_id: &str) -> Option<wave_core::stream::resolver::ResolvedStream> {
        self.current_stream.lock().unwrap().clone().filter(|s| s.video_id == video_id)
    }

    pub fn toggle(&self) {
        let (_, state, _) = self.playbin.state(gst::ClockTime::ZERO);
        let next = if state == gst::State::Playing { gst::State::Paused } else { gst::State::Playing };
        self.playbin.set_state(next).ok();
    }

    /// 鳴っているか、鳴らそうとしている（読み込み中）か。端末の連携のカーソルの「再生中」に使う（読み込みの間に止まっている扱いにしない）。
    pub fn wants_to_play(&self) -> bool {
        let (_, current, pending) = self.playbin.state(gst::ClockTime::ZERO);
        current == gst::State::Playing || pending == gst::State::Playing || self.pending_start.lock().unwrap().is_some_and(|(_, playing)| playing)
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
