//! アプリの設定（~/.config/wave/settings.json）。ログインの情報は入れない（キーリングに置く）。

use serde::{Deserialize, Serialize};
use wave_core::download::smart::{DEFAULT_COUNT, DownloadNetwork};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// スマートダウンロード（既定は切。入れた途端に容量と通信を使うため。ほかの端末と同じ）。
    pub smart_downloads: bool,
    pub smart_download_count: usize,
    /// ダウンロードに使う通信（既定は従量制でない通信だけ）。
    pub download_network: DownloadNetwork,
    /// 聴いた曲を YouTube Music の再生履歴に載せる（既定は入。ほかの端末と同じ）。
    pub send_history: bool,
    /// 登録したアーティストの新作を確かめて知らせる（既定は入）。
    pub release_notices: bool,
    /// 検索の履歴（新しい順、最大 30 件。Windows と同じ）。ログアウトで消す。
    pub search_history: Vec<SearchHistoryEntry>,
    /// 端末の連携の中継（wss://ホスト:ポート）。リポジトリには書かない、本人がここで入れる値。ログに出さない。
    pub relay_url: Option<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self { smart_downloads: false, smart_download_count: DEFAULT_COUNT, download_network: DownloadNetwork::Unmetered, relay_url: None, send_history: true, release_notices: true, search_history: Vec::new() }
    }
}

fn path() -> std::path::PathBuf {
    gtk::glib::user_config_dir().join("wave").join("settings.json")
}

/// 検索の履歴の1件（語・曲・アルバム・アーティスト。画像付きで出す）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SearchHistoryEntry {
    /// "query"・"song"・"album"・"artist"。
    pub kind: String,
    pub title: String,
    pub subtitle: Option<String>,
    pub thumbnail_url: Option<String>,
    /// アルバム・アーティストの browseId。
    pub id: Option<String>,
    /// 曲なら、その曲（押すと鳴らす）。
    pub song: Option<wave_core::music::models::Song>,
}

impl Default for SearchHistoryEntry {
    fn default() -> Self {
        Self { kind: "query".into(), title: String::new(), subtitle: None, thumbnail_url: None, id: None, song: None }
    }
}

impl SearchHistoryEntry {
    pub const MAX: usize = 30;

    pub fn key(&self) -> String {
        format!("{}:{}", self.kind, self.id.clone().or_else(|| self.song.as_ref().map(|s| s.video_id.clone())).unwrap_or_else(|| self.title.clone()))
    }

    pub fn query(text: &str) -> Self {
        Self { kind: "query".into(), title: text.into(), ..Default::default() }
    }

    pub fn song(song: &wave_core::music::models::Song) -> Self {
        Self { kind: "song".into(), title: song.title.clone(), subtitle: Some(song.artists_text()), thumbnail_url: song.thumbnail_url.clone(), id: None, song: Some(song.clone()) }
    }

    pub fn album(id: &str, title: &str, subtitle: Option<&str>, thumbnail: Option<&str>) -> Self {
        Self { kind: "album".into(), title: title.into(), subtitle: subtitle.map(String::from), thumbnail_url: thumbnail.map(String::from), id: Some(id.into()), song: None }
    }

    pub fn artist(id: &str, name: &str, thumbnail: Option<&str>) -> Self {
        Self { kind: "artist".into(), title: name.into(), subtitle: Some("アーティスト".into()), thumbnail_url: thumbnail.map(String::from), id: Some(id.into()), song: None }
    }
}

impl Settings {
    /// 検索の履歴に足す（同じものは先頭へ動かす）。
    pub fn add_search(&mut self, entry: SearchHistoryEntry) {
        let key = entry.key();
        self.search_history.retain(|e| e.key() != key);
        self.search_history.insert(0, entry);
        self.search_history.truncate(SearchHistoryEntry::MAX);
    }

    pub fn load() -> Self {
        std::fs::read_to_string(path()).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
    }

    pub fn save(&self) {
        let path = path();
        let written = std::fs::create_dir_all(path.parent().unwrap()).and_then(|_| std::fs::write(&path, serde_json::to_string_pretty(self).expect("plain data")));
        if let Err(e) = written {
            eprintln!("settings save failed: {}", e.kind());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_history_moves_repeats_to_the_front_and_keeps_30() {
        let mut settings = Settings::default();
        for i in 0..35 {
            settings.add_search(SearchHistoryEntry::query(&format!("q{i}")));
        }
        assert_eq!(settings.search_history.len(), 30);
        settings.add_search(SearchHistoryEntry::query("q20"));
        assert_eq!(settings.search_history[0].title, "q20");
        assert_eq!(settings.search_history.iter().filter(|e| e.title == "q20").count(), 1);
    }
}
