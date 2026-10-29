//! 曲の音を端末に残す（Windows の AudioCache と同じ。上限 300MB、最後に使ったのが古い曲から消す）。
//! 曲ごとに、頭から途切れずに取れた分（<videoId>.bin）と、曲の形式（<videoId>.json。URL は含めない）を置く。
//! 頭が残っている曲は URL を調べ直さずに鳴らし始められる。ライブ配信と RSS のエピソードは残さない。

use std::collections::HashMap;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};

use regex::Regex;
use serde::{Deserialize, Serialize};

use super::resolver::ResolvedStream;

pub const DEFAULT_MAX_BYTES: u64 = 300 * 1024 * 1024;
/// 残した分の少し先から求められたとき、これ以下の間なら間も取って続けて残す。
pub const GAP_FILL_BYTES: u64 = 256 * 1024;
/// 次の曲を先に取っておく大きさ（30 秒ほど）。
pub const WARM_BYTES: u64 = 1024 * 1024;

static VIDEO_ID: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Za-z0-9_-]{11}$").expect("valid regex"));

/// 残した曲の形式（URL は含めない）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedFormat {
    pub video_id: String,
    pub itag: u32,
    pub mime_type: String,
    pub length: u64,
    pub last_used_ms: i64,
}

pub struct AudioCache {
    directory: PathBuf,
    max_bytes: u64,
    now_ms: Box<dyn Fn() -> i64 + Send + Sync>,
    state: Mutex<State>,
}

#[derive(Default)]
struct State {
    formats: HashMap<String, CachedFormat>,
    loaded: bool,
}

impl AudioCache {
    pub fn new(directory: impl Into<PathBuf>, max_bytes: u64, now_ms: impl Fn() -> i64 + Send + Sync + 'static) -> Self {
        Self { directory: directory.into(), max_bytes, now_ms: Box::new(now_ms), state: Mutex::default() }
    }

    /// 残してある曲の形式と、頭から残っているバイト数。無ければ None。
    pub fn entry(&self, video_id: &str) -> Option<(CachedFormat, u64)> {
        let mut state = self.state.lock().unwrap();
        self.load_if_needed(&mut state);
        state.formats.get(video_id).map(|f| (f.clone(), self.size(video_id)))
    }

    /// これから取る曲の形式を書く。前に残した分が別の形式（itag・長さが違う）なら捨てる。
    pub fn begin(&self, stream: &ResolvedStream) {
        let mut state = self.state.lock().unwrap();
        self.load_if_needed(&mut state);
        let now = (self.now_ms)();
        if let Some(old) = state.formats.get_mut(&stream.video_id)
            && old.itag == stream.itag
            && Some(old.length) == stream.content_length
        {
            old.last_used_ms = now;
            return;
        }
        let Some(length) = stream.content_length.filter(|l| *l > 0) else { return };
        if !is_safe_id(&stream.video_id) {
            return;
        }
        self.remove_files(&stream.video_id);
        let format = CachedFormat { video_id: stream.video_id.clone(), itag: stream.itag, mime_type: stream.mime_type.clone(), length, last_used_ms: now };
        let written = std::fs::create_dir_all(&self.directory)
            .and_then(|_| std::fs::write(self.format_path(&stream.video_id), serde_json::to_string(&format).expect("plain data")))
            .and_then(|_| std::fs::write(self.bytes_path(&stream.video_id), []));
        if written.is_err() {
            return;
        }
        state.formats.insert(stream.video_id.clone(), format);
        self.trim(&mut state, &stream.video_id);
    }

    /// start から end（含む）までが残っていれば返す。
    pub fn read(&self, video_id: &str, start: u64, end: u64) -> Option<Vec<u8>> {
        let mut state = self.state.lock().unwrap();
        if end < start || end >= self.size(video_id) {
            return None;
        }
        let format = state.formats.get_mut(video_id)?;
        let mut file = std::fs::File::open(self.bytes_path(video_id)).ok()?;
        file.seek(SeekFrom::Start(start)).ok()?;
        let mut data = vec![0; (end - start + 1) as usize];
        file.read_exact(&mut data).ok()?;
        format.last_used_ms = (self.now_ms)();
        Some(data)
    }

    /// offset から取ったバイトを足す。残している分の続き（か、それに重なる分）だけを足し、間が空くものは足さない。
    pub fn append(&self, video_id: &str, offset: u64, data: &[u8]) {
        let mut state = self.state.lock().unwrap();
        let Some(format) = state.formats.get_mut(video_id) else { return };
        let have = self.size(video_id);
        let end = offset + data.len() as u64;
        if offset > have || end <= have || have >= format.length {
            return;
        }
        let from = (have - offset) as usize;
        let count = (data.len() - from).min((format.length - have) as usize);
        let appended = std::fs::OpenOptions::new().append(true).open(self.bytes_path(video_id)).and_then(|mut f| f.write_all(&data[from..from + count]));
        if appended.is_ok() {
            format.last_used_ms = (self.now_ms)();
        }
    }

    /// ログアウトのとき（聴いた曲を残さない）。次に使うときに置き場を作り直す。
    pub fn clear(&self) {
        let mut state = self.state.lock().unwrap();
        let _ = std::fs::remove_dir_all(&self.directory);
        state.formats.clear();
        state.loaded = false;
    }

    /// 残しているバイトの合計。
    pub fn total_bytes(&self) -> u64 {
        let mut state = self.state.lock().unwrap();
        self.load_if_needed(&mut state);
        state.formats.keys().map(|id| self.size(id)).sum()
    }

    fn load_if_needed(&self, state: &mut State) {
        if state.loaded {
            return;
        }
        state.loaded = true;
        let _ = std::fs::create_dir_all(&self.directory);
        let Ok(entries) = std::fs::read_dir(&self.directory) else { return };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            if let Some(format) = std::fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str::<CachedFormat>(&t).ok()).filter(|f| is_safe_id(&f.video_id)) {
                state.formats.insert(format.video_id.clone(), format);
            }
        }
    }

    fn trim(&self, state: &mut State, keep: &str) {
        let mut total: u64 = state.formats.keys().map(|id| self.size(id)).sum();
        // 今から取る曲の分も見込む
        if let Some(kept) = state.formats.get(keep) {
            total += kept.length.saturating_sub(self.size(keep));
        }
        let mut oldest: Vec<(i64, String)> = state.formats.values().map(|f| (f.last_used_ms, f.video_id.clone())).collect();
        oldest.sort();
        for (_, id) in oldest {
            if total <= self.max_bytes {
                break;
            }
            if id == keep {
                continue;
            }
            total = total.saturating_sub(self.size(&id));
            self.remove_files(&id);
            state.formats.remove(&id);
        }
    }

    fn remove_files(&self, video_id: &str) {
        let _ = std::fs::remove_file(self.bytes_path(video_id));
        let _ = std::fs::remove_file(self.format_path(video_id));
    }

    fn bytes_path(&self, video_id: &str) -> PathBuf {
        self.directory.join(format!("{video_id}.bin"))
    }

    fn format_path(&self, video_id: &str) -> PathBuf {
        self.directory.join(format!("{video_id}.json"))
    }

    fn size(&self, video_id: &str) -> u64 {
        std::fs::metadata(self.bytes_path(video_id)).map_or(0, |m| m.len())
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }
}

/// ファイル名に使ってよい videoId（YouTube の 11 文字）。
pub fn is_safe_id(video_id: &str) -> bool {
    VIDEO_ID.is_match(video_id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicI64, Ordering};

    fn dir(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("wave-audio-cache-test-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    fn stream(id: &str, length: u64) -> ResolvedStream {
        ResolvedStream::for_test(id, 140, "audio/mp4", Some(length))
    }

    #[test]
    fn appends_only_contiguous_bytes_and_reads_them_back() {
        let cache = AudioCache::new(dir("contig"), DEFAULT_MAX_BYTES, || 0);
        cache.begin(&stream("aaaaaaaaaaa", 100));
        cache.append("aaaaaaaaaaa", 0, &[1; 40]);
        // 間が空くものは足さない
        cache.append("aaaaaaaaaaa", 60, &[2; 10]);
        assert_eq!(cache.entry("aaaaaaaaaaa").map(|e| e.1), Some(40));
        // 重なる分は続きだけを足す
        cache.append("aaaaaaaaaaa", 30, &[3; 30]);
        assert_eq!(cache.entry("aaaaaaaaaaa").map(|e| e.1), Some(60));
        assert_eq!(cache.read("aaaaaaaaaaa", 38, 41), Some(vec![1, 1, 3, 3]));
        assert_eq!(cache.read("aaaaaaaaaaa", 50, 70), None);
        // 作り直しても残している
        let again = AudioCache::new(cache.directory().to_path_buf(), DEFAULT_MAX_BYTES, || 0);
        assert_eq!(again.entry("aaaaaaaaaaa").map(|e| (e.0.itag, e.1)), Some((140, 60)));
    }

    #[test]
    fn oldest_songs_are_removed_over_the_limit_and_clear_removes_all() {
        let clock = Arc::new(AtomicI64::new(0));
        let c = clock.clone();
        let cache = AudioCache::new(dir("limit"), 2500, move || c.load(Ordering::SeqCst));
        for (i, id) in ["ccccccccccc", "ddddddddddd", "eeeeeeeeeee"].iter().enumerate() {
            clock.store(i as i64, Ordering::SeqCst);
            cache.begin(&stream(id, 1000));
            cache.append(id, 0, &[0; 1000]);
        }
        // 3曲目を始めるとき、一番古い1曲目を消す
        assert!(cache.entry("ccccccccccc").is_none());
        assert_eq!(cache.entry("eeeeeeeeeee").map(|e| e.1), Some(1000));
        cache.clear();
        assert!(cache.entry("eeeeeeeeeee").is_none());
        assert_eq!(cache.total_bytes(), 0);
        // 消した後も（ログインし直した後も）、置き場を作り直して残せる
        cache.begin(&stream("fffffffffff", 100));
        cache.append("fffffffffff", 0, &[9; 100]);
        assert_eq!(cache.entry("fffffffffff").map(|e| e.1), Some(100));
    }

    #[test]
    fn a_new_format_replaces_the_old_bytes_and_files_hold_no_url() {
        let cache = AudioCache::new(dir("format"), DEFAULT_MAX_BYTES, || 0);
        cache.begin(&stream("ggggggggggg", 100));
        cache.append("ggggggggggg", 0, &[1; 50]);
        cache.begin(&ResolvedStream::for_test("ggggggggggg", 251, "audio/webm", Some(80)));
        assert_eq!(cache.entry("ggggggggggg").map(|e| (e.0.itag, e.1)), Some((251, 0)));
        let json = std::fs::read_to_string(cache.directory().join("ggggggggggg.json")).unwrap();
        assert!(!json.contains("http"));
        // ファイル名に使えない ID は残さない
        cache.begin(&stream("../escape", 100));
        assert!(cache.entry("../escape").is_none());
    }
}
