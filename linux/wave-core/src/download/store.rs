//! 取った曲を置く、消えない置き場。曲は <videoId>.bin（音）と <videoId>.json（形式と曲の情報）、目録は downloads.json。
//! 取っている途中の曲は complete が false のまま、頭から取れた分だけを .bin に持つ。署名付き URL は書かない。

use std::collections::HashSet;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};

use regex::Regex;
use serde::{Deserialize, Serialize};

use super::manifest::DownloadManifest;
use crate::music::models::Song;

pub const MANIFEST_NAME: &str = "downloads.json";

static SAFE_ID: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Za-z0-9_-]{11}$").unwrap());

/// 取った曲の形式。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct DownloadedFile {
    pub video_id: String,
    pub mime_type: String,
    pub length: u64,
    #[serde(default)]
    pub itag: u32,
    #[serde(default)]
    pub complete: bool,
    pub song: Option<Song>,
}

pub struct DownloadStore {
    directory: PathBuf,
    /// この起動で取り始めた曲（途中の分から続けるのはこれだけ。起動し直したら頭から取り直す。ほかの端末と同じ）。
    begun: Mutex<HashSet<String>>,
}

impl DownloadStore {
    pub fn new(directory: impl Into<PathBuf>) -> Self {
        Self { directory: directory.into(), begun: Mutex::default() }
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }

    fn key_of(video_id: &str) -> Option<&str> {
        SAFE_ID.is_match(video_id).then_some(video_id)
    }

    pub fn bytes_path(&self, video_id: &str) -> Option<PathBuf> {
        Some(self.directory.join(format!("{}.bin", Self::key_of(video_id)?)))
    }

    fn format_path(&self, video_id: &str) -> Option<PathBuf> {
        Some(self.directory.join(format!("{}.json", Self::key_of(video_id)?)))
    }

    pub fn entry(&self, video_id: &str) -> Option<DownloadedFile> {
        serde_json::from_str(&fs::read_to_string(self.format_path(video_id)?).ok()?).ok()
    }

    fn size(&self, video_id: &str) -> u64 {
        self.bytes_path(video_id).and_then(|p| fs::metadata(p).ok()).map_or(0, |m| m.len())
    }

    /// 取り終えた曲の形式（取っていない・途中・大きさが合わなければ None）。
    pub fn completed(&self, video_id: &str) -> Option<DownloadedFile> {
        self.entry(video_id).filter(|f| f.complete && self.size(video_id) == f.length)
    }

    /// 取れているバイト数。
    pub fn downloaded(&self, video_id: &str) -> u64 {
        self.size(video_id)
    }

    /// これから取る曲の形式を書き、続きを取り始める位置を返す。この起動で取り始めた同じ形式の分なら続きから、ほかは頭から。
    pub fn begin(&self, format: DownloadedFile) -> std::io::Result<u64> {
        let key = Self::key_of(&format.video_id).ok_or_else(|| std::io::Error::other("unsafe id"))?.to_string();
        let mut begun = self.begun.lock().unwrap();
        if begun.contains(&key)
            && let Some(old) = self.entry(&key)
            && old.itag == format.itag
            && old.length == format.length
            && old.length > 0
        {
            let have = self.size(&key);
            if have <= old.length {
                return Ok(have);
            }
        }
        fs::create_dir_all(&self.directory)?;
        begun.insert(key.clone());
        fs::write(self.bytes_path(&key).unwrap(), [])?;
        self.write_format(&DownloadedFile { complete: false, ..format })?;
        Ok(0)
    }

    /// offset から取ったバイトを足す（取れている分の続きのときだけ）。足せたか。
    pub fn append(&self, video_id: &str, offset: u64, data: &[u8]) -> bool {
        if self.entry(video_id).is_none_or(|f| f.complete) || self.size(video_id) != offset {
            return false;
        }
        let Some(path) = self.bytes_path(video_id) else { return false };
        fs::OpenOptions::new().append(true).open(path).and_then(|mut f| f.write_all(data)).is_ok()
    }

    /// 取り終えた印を付ける（大きさが合わなければ付けない）。
    pub fn complete(&self, video_id: &str) -> bool {
        let Some(format) = self.entry(video_id) else { return false };
        if self.size(video_id) != format.length {
            return false;
        }
        self.write_format(&DownloadedFile { complete: true, ..format }).is_ok()
    }

    fn write_format(&self, format: &DownloadedFile) -> std::io::Result<()> {
        let path = self.format_path(&format.video_id).ok_or_else(|| std::io::Error::other("unsafe id"))?;
        fs::write(path, serde_json::to_string(format).expect("plain data always serializes"))
    }

    pub fn remove(&self, video_id: &str) {
        for path in [self.bytes_path(video_id), self.format_path(video_id)].into_iter().flatten() {
            let _ = fs::remove_file(path);
        }
        if let Some(key) = Self::key_of(video_id) {
            self.begun.lock().unwrap().remove(key);
        }
    }

    /// 置き場にある曲の ID。
    fn stored_ids(&self) -> Vec<String> {
        let Ok(entries) = fs::read_dir(&self.directory) else { return Vec::new() };
        entries
            .filter_map(|e| e.ok()?.file_name().to_str()?.strip_suffix(".json").map(String::from))
            .filter(|id| Self::key_of(id).is_some())
            .collect()
    }

    /// 持ち主のいない曲のファイルを消す（目録を変えたあと）。消した数を返す。
    pub fn purge(&self, wanted: &HashSet<String>) -> usize {
        let stale: Vec<String> = self.stored_ids().into_iter().filter(|id| !wanted.contains(id)).collect();
        stale.iter().for_each(|id| self.remove(id));
        stale.len()
    }

    /// 取ったファイルの大きさの合計（途中の分も含む）。
    pub fn total_bytes(&self) -> u64 {
        self.stored_ids().iter().map(|id| self.size(id)).sum()
    }

    pub fn read_manifest(&self) -> DownloadManifest {
        fs::read_to_string(self.directory.join(MANIFEST_NAME)).ok().and_then(|t| DownloadManifest::decode(&t)).unwrap_or_default()
    }

    /// 書いている途中で落ちても前の目録が残るよう、別の名前に書いてから置き換える。
    pub fn write_manifest(&self, manifest: &DownloadManifest) -> std::io::Result<()> {
        fs::create_dir_all(&self.directory)?;
        let path = self.directory.join(MANIFEST_NAME);
        let tmp = self.directory.join(format!("{MANIFEST_NAME}.tmp"));
        fs::write(&tmp, manifest.encode())?;
        fs::rename(tmp, path)
    }

    /// 全部消す（「ダウンロードをすべて削除」・ログアウト）。
    pub fn clear(&self) {
        let _ = fs::remove_dir_all(&self.directory);
        self.begun.lock().unwrap().clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_store(name: &str) -> DownloadStore {
        let dir = std::env::temp_dir().join(format!("wave-store-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        DownloadStore::new(dir)
    }

    fn format(length: u64) -> DownloadedFile {
        DownloadedFile { video_id: "abcdefghijk".into(), mime_type: "audio/mp4".into(), length, itag: 140, complete: false, song: None }
    }

    #[test]
    fn appends_in_order_and_completes_only_when_full() {
        let store = temp_store("append");
        assert_eq!(store.begin(format(6)).unwrap(), 0);
        assert!(store.append("abcdefghijk", 0, b"abc"));
        // 続きでない位置には足さない
        assert!(!store.append("abcdefghijk", 1, b"x"));
        assert!(!store.complete("abcdefghijk"));
        assert!(store.append("abcdefghijk", 3, b"def"));
        assert!(store.complete("abcdefghijk"));
        assert_eq!(store.completed("abcdefghijk").map(|f| f.length), Some(6));
        // 同じ起動で同じ形式なら続きから（ここでは全部取れているので、続きの位置は終わり）
        assert_eq!(store.begin(format(6)).unwrap(), 6);
        // 形式が違えば頭から
        assert_eq!(store.begin(format(7)).unwrap(), 0);
        store.clear();
    }

    #[test]
    fn purge_keeps_only_wanted_and_rejects_unsafe_ids() {
        let store = temp_store("purge");
        store.begin(format(1)).unwrap();
        assert_eq!(store.purge(&HashSet::from(["abcdefghijk".to_string()])), 0);
        assert_eq!(store.purge(&HashSet::new()), 1);
        assert!(store.entry("abcdefghijk").is_none());
        assert!(store.bytes_path("../etc/passwd").is_none());
        store.clear();
    }
}
