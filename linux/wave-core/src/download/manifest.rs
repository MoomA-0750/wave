//! 何を取っているかの目録（downloads.json）。丸ごと取るもの（プレイリスト・アルバム・高く評価した曲）と1曲ずつ取った曲を持ち、
//! 曲のファイルは、どれか1つでも持ち主がいれば残す。変える操作は新しい目録を返す。

use serde::{Deserialize, Serialize};

use super::smart::SmartTrack;
use crate::music::models::Song;

pub const KIND_PLAYLIST: &str = "playlist";
pub const KIND_ALBUM: &str = "album";
/// 高く評価した曲（LM）。
pub const KIND_LIKED: &str = "liked";

/// 丸ごと取っているもの。songs は最後に読んだ曲の並び（通信なしでもページを出す）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct DownloadCollection {
    pub kind: String,
    pub id: String,
    pub title: String,
    pub thumbnail_url: Option<String>,
    pub added_at_ms: i64,
    pub songs: Vec<Song>,
}

/// 1曲ずつ取った曲。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct DownloadSingle {
    pub song: Song,
    pub added_at_ms: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase", default)]
pub struct DownloadManifest {
    pub collections: Vec<DownloadCollection>,
    pub singles: Vec<DownloadSingle>,
    /// スマートダウンロードで選んだ曲。
    pub smart: Vec<SmartTrack>,
    pub smart_refreshed_at_ms: i64,
}

impl DownloadManifest {
    /// 取ってよい曲か（ライブ配信は取らない）。
    pub fn downloadable(song: &Song) -> bool {
        !song.is_live
    }

    /// 持ち主のいる曲（丸ごとのもの・1曲ずつの順。同じ曲は最初の1つ。取る順にも使う）。
    pub fn wanted_songs(&self) -> Vec<Song> {
        let mut seen = std::collections::HashSet::new();
        self.collections
            .iter()
            .flat_map(|c| c.songs.iter())
            .chain(self.singles.iter().map(|s| &s.song))
            .chain(self.smart.iter().map(|s| &s.song))
            .filter(|s| Self::downloadable(s) && seen.insert(s.video_id.clone()))
            .cloned()
            .collect()
    }

    pub fn is_wanted(&self, video_id: &str) -> bool {
        self.wanted_songs().iter().any(|s| s.video_id == video_id)
    }

    pub fn is_single(&self, video_id: &str) -> bool {
        self.singles.iter().any(|s| s.song.video_id == video_id)
    }

    pub fn collection(&self, id: &str) -> Option<&DownloadCollection> {
        self.collections.iter().find(|c| c.id == id)
    }

    /// 丸ごと取るものを足す（同じ ID があれば置き換える。足した時刻は前のまま）。
    pub fn with_collection(&self, collection: DownloadCollection) -> Self {
        let mut next = self.clone();
        match next.collections.iter_mut().find(|c| c.id == collection.id) {
            Some(old) => {
                let added = old.added_at_ms;
                *old = DownloadCollection { added_at_ms: added, ..collection };
            }
            None => next.collections.push(collection),
        }
        next
    }

    pub fn without_collection(&self, id: &str) -> Self {
        let mut next = self.clone();
        next.collections.retain(|c| c.id != id);
        next
    }

    pub fn with_single(&self, song: Song, now_ms: i64) -> Self {
        if self.is_single(&song.video_id) || !Self::downloadable(&song) {
            return self.clone();
        }
        let mut next = self.clone();
        next.singles.push(DownloadSingle { song, added_at_ms: now_ms });
        next
    }

    pub fn is_smart(&self, video_id: &str) -> bool {
        self.smart.iter().any(|s| s.song.video_id == video_id)
    }

    /// 1曲ずつ取ったもの・スマートダウンロードから外す（丸ごと取っているものに入っていれば、ファイルは残る）。
    pub fn without_single(&self, video_id: &str) -> Self {
        let mut next = self.clone();
        next.singles.retain(|s| s.song.video_id != video_id);
        next.smart.retain(|s| s.song.video_id != video_id);
        next
    }

    pub fn with_smart(&self, tracks: Vec<SmartTrack>, now_ms: i64) -> Self {
        Self { smart: tracks, smart_refreshed_at_ms: now_ms, ..self.clone() }
    }

    pub fn encode(&self) -> String {
        serde_json::to_string(self).expect("plain data always serializes")
    }

    /// 読めない・形の違うファイルは None（呼ぶ側で空にする）。
    pub fn decode(text: &str) -> Option<Self> {
        serde_json::from_str(text).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn song(id: &str, live: bool) -> Song {
        Song { video_id: id.into(), title: id.into(), artists: vec![], album: None, duration_seconds: None, thumbnail_url: None, kind: None, is_live: live }
    }

    #[test]
    fn wanted_songs_are_unique_and_skip_live() {
        let manifest = DownloadManifest::default()
            .with_collection(DownloadCollection { kind: KIND_PLAYLIST.into(), id: "p".into(), title: "P".into(), thumbnail_url: None, added_at_ms: 1, songs: vec![song("a", false), song("l", true)] })
            .with_single(song("a", false), 2)
            .with_single(song("b", false), 3)
            .with_single(song("live", true), 4);
        let ids: Vec<String> = manifest.wanted_songs().into_iter().map(|s| s.video_id).collect();
        assert_eq!(ids, ["a", "b"]);
        assert!(manifest.is_wanted("a") && !manifest.is_wanted("l"));
        // 1曲ずつから外しても、丸ごとのものに入っていれば残る
        assert!(manifest.without_single("a").is_wanted("a"));
        assert!(!manifest.without_single("b").is_wanted("b"));
    }

    #[test]
    fn replacing_a_collection_keeps_when_it_was_added() {
        let first = DownloadCollection { kind: KIND_ALBUM.into(), id: "x".into(), title: "X".into(), thumbnail_url: None, added_at_ms: 10, songs: vec![] };
        let manifest = DownloadManifest::default().with_collection(first.clone()).with_collection(DownloadCollection { added_at_ms: 99, songs: vec![song("a", false)], ..first });
        assert_eq!(manifest.collections.len(), 1);
        assert_eq!(manifest.collections[0].added_at_ms, 10);
        assert_eq!(DownloadManifest::decode(&manifest.encode()), Some(manifest));
    }
}
