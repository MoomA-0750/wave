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
    /// 端末の連携の中継（wss://ホスト:ポート）。リポジトリには書かない、本人がここで入れる値。ログに出さない。
    pub relay_url: Option<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self { smart_downloads: false, smart_download_count: DEFAULT_COUNT, download_network: DownloadNetwork::Unmetered, relay_url: None }
    }
}

fn path() -> std::path::PathBuf {
    gtk::glib::user_config_dir().join("wave").join("settings.json")
}

impl Settings {
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
