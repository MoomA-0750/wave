//! wave の Linux 版の、画面に依らない部分（再生・プレーヤー・ログインの保存・ブラウザのログイン・MPRIS・キューの保存・
//! 設定・ダウンロード・端末の連携・新作・番組・Claude・Discord）。GTK 版（wave-gtk）と QML 版（wave-qt）が同じものを使う。
//! GTK にも Qt にも依らない。主スレッドの仕事は glib の主ループで回す（Qt も Linux では glib の主ループで回るので、そのまま動く）。

pub mod ai_chat;
pub mod art;
pub mod browser_login;
pub mod connect;
pub mod discord_status;
pub mod downloads;
pub mod keyring;
pub mod mpris;
pub mod omarchy_theme;
pub mod playback;
pub mod player;
pub mod podcasts;
pub mod queue_store;
pub mod releases;
pub mod runtime;
pub mod services;
pub mod settings;
