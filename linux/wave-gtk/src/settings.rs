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
    /// 曲名・アーティスト名・アルバム名を曲の言語のまま出す（既定は切。YouTube に2回問い合わせるため。Android と同じ）。
    pub original_titles: bool,
    /// 検索の履歴（新しい順、最大 30 件。Windows と同じ）。ログアウトで消す。
    pub search_history: Vec<SearchHistoryEntry>,
    /// マイライブラリの並べ方（"recent"・"added"・"alpha"。Windows と同じ）。
    pub library_sort: String,
    /// マイライブラリをグリッドで出す。
    pub library_grid: bool,
    /// プレイリストごとに選んだ並べ方（"Title:desc" の形。Mac と同じ）。
    pub playlist_orders: std::collections::HashMap<String, String>,
    /// Discord に再生中の曲を出す（既定は切。Windows と同じ）。
    pub discord_presence: bool,
    /// Discord のアプリケーション ID（本人が discord.com/developers で作ったもの）。
    pub discord_client_id: Option<String>,
    /// 端末の連携の中継（wss://ホスト:ポート）。リポジトリには書かない、本人がここで入れる値。ログに出さない。
    pub relay_url: Option<String>,
    /// 右の欄（再生中・キュー）を開いているか（次に開いたときも同じにする。Mac と同じ）。
    pub right_pane_open: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self { smart_downloads: false, smart_download_count: DEFAULT_COUNT, download_network: DownloadNetwork::Unmetered, relay_url: None, send_history: true, release_notices: true, original_titles: false, search_history: Vec::new(), discord_presence: false, discord_client_id: None, library_sort: "recent".into(), library_grid: false, playlist_orders: Default::default(), right_pane_open: true }
    }
}

fn path() -> std::path::PathBuf {
    gtk::glib::user_config_dir().join("wave").join("settings.json")
}

/// 検索の履歴の1件（語・曲・アルバム・アーティスト。画像付きで出す）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SearchHistoryEntry {
    /// "query"・"song"・"album"・"artist"・"playlist"・"podcast"。
    pub kind: String,
    pub title: String,
    pub subtitle: Option<String>,
    pub thumbnail_url: Option<String>,
    /// アルバム・アーティスト・番組の browseId、プレイリストの ID。
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

    pub fn playlist(id: &str, title: &str, subtitle: Option<&str>, thumbnail: Option<&str>) -> Self {
        Self { kind: "playlist".into(), title: title.into(), subtitle: subtitle.map(String::from), thumbnail_url: thumbnail.map(String::from), id: Some(id.into()), song: None }
    }

    /// ポッドキャストの番組（YouTube の MPSP… も RSS の rss:… も）。
    pub fn podcast(show: &wave_core::music::podcast::PodcastSummary) -> Self {
        Self {
            kind: "podcast".into(),
            title: show.title.clone(),
            subtitle: Some(show.author.clone().unwrap_or_else(|| "ポッドキャスト".into())),
            thumbnail_url: show.thumbnail_url.clone(),
            id: Some(show.browse_id.clone()),
            song: None,
        }
    }

    /// カード（曲・アルバム・プレイリスト・アーティスト）から（Mac の SearchHistoryEntry.of と同じ）。
    pub fn of(item: &wave_core::music::models::HomeItem) -> Self {
        use wave_core::music::models::HomeItem;
        match item {
            HomeItem::Song(s) => Self::song(s),
            HomeItem::Album(a) => Self::album(&a.browse_id, &a.title, a.subtitle.as_deref(), a.thumbnail_url.as_deref()),
            HomeItem::Playlist(p) => Self::playlist(&p.playlist_id, &p.title, p.subtitle.as_deref(), p.thumbnail_url.as_deref()),
            HomeItem::Artist(a) => Self::artist(&a.browse_id, &a.name, a.thumbnail_url.as_deref()),
        }
    }

    /// 押したときに開くもの（語なら None）。
    pub fn item(&self) -> Option<wave_core::music::models::HomeItem> {
        use wave_core::music::models::{AlbumSummary, ArtistSummary, HomeItem, PlaylistSummary};
        let id = self.id.clone();
        match self.kind.as_str() {
            "song" => self.song.clone().map(HomeItem::Song),
            "album" => Some(HomeItem::Album(AlbumSummary { browse_id: id?, title: self.title.clone(), subtitle: self.subtitle.clone(), thumbnail_url: self.thumbnail_url.clone() })),
            "artist" => Some(HomeItem::Artist(ArtistSummary { browse_id: id?, name: self.title.clone(), subtitle: None, thumbnail_url: self.thumbnail_url.clone() })),
            "playlist" => Some(HomeItem::Playlist(PlaylistSummary { playlist_id: id?, title: self.title.clone(), subtitle: self.subtitle.clone(), thumbnail_url: self.thumbnail_url.clone(), editable: false })),
            _ => None,
        }
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

    #[test]
    fn search_history_entries_open_what_was_remembered() {
        use wave_core::music::models::{HomeItem, PlaylistSummary};
        let playlist = HomeItem::Playlist(PlaylistSummary { playlist_id: "PL1".into(), title: "P".into(), subtitle: None, thumbnail_url: None, editable: false });
        let entry = SearchHistoryEntry::of(&playlist);
        assert_eq!(entry.key(), "playlist:PL1");
        assert!(matches!(entry.item(), Some(HomeItem::Playlist(p)) if p.playlist_id == "PL1"));
        assert_eq!(SearchHistoryEntry::query("q").item(), None);
        let show = wave_core::music::podcast::PodcastSummary { browse_id: "rss:https://example.com/f".into(), title: "S".into(), author: None, thumbnail_url: None };
        let entry = SearchHistoryEntry::podcast(&show);
        assert_eq!((entry.kind.as_str(), entry.id.as_deref(), entry.item()), ("podcast", Some("rss:https://example.com/f"), None));
    }
}
