//! QML に渡す Rust の部品（cxx-qt）。中身は app.rs（WaveApp）と theme.rs（Theme）。

#[cxx_qt::bridge]
pub mod qobject {
    unsafe extern "C++" {
        include!("cxx-qt-lib/qstring.h");
        type QString = cxx_qt_lib::QString;
        include!("cxx-qt-lib/qqmlapplicationengine.h");
        type QQmlApplicationEngine = cxx_qt_lib::QQmlApplicationEngine;

        include!("helpers.h");
        fn wave_setup_application();
        fn wave_window_active() -> bool;
        fn wave_quit();
        fn wave_snapshot_later(engine: Pin<&mut QQmlApplicationEngine>, path: &QString, delay_ms: i32);
    }

    #[auto_cxx_name]
    unsafe extern "RustQt" {
        /// アプリの中身（再生・ページの読み込み・設定）。QML からは Wave で使う。
        #[qobject]
        #[qml_element]
        #[qml_singleton]
        // "loading"（キーリングを読んでいる）・"login"（ログインの入り口）・"main"（主の画面）
        #[qproperty(QString, phase)]
        #[qproperty(bool, logged_in)]
        #[qproperty(bool, has_song)]
        #[qproperty(QString, title)]
        #[qproperty(QString, artists)]
        #[qproperty(QString, artist_id)]
        #[qproperty(QString, album)]
        #[qproperty(QString, album_id)]
        #[qproperty(QString, art)]
        #[qproperty(QString, thumb)]
        #[qproperty(QString, video_id)]
        #[qproperty(QString, song_json)]
        #[qproperty(bool, playing)]
        #[qproperty(bool, live)]
        #[qproperty(i32, duration_ms)]
        #[qproperty(f64, volume)]
        #[qproperty(bool, shuffle)]
        // 0: 繰り返さない、1: すべて、2: 1曲
        #[qproperty(i32, repeat_mode)]
        // -1: 分からない、0: 評価していない、1: 高く評価した
        #[qproperty(i32, liked)]
        // ほかの端末で鳴らしているとき、その端末の名前
        #[qproperty(QString, remote_label)]
        #[qproperty(bool, connect_configured)]
        #[qproperty(QString, next_json)]
        #[qproperty(QString, queue_json)]
        #[qproperty(QString, history_json)]
        #[qproperty(QString, account_name)]
        #[qproperty(QString, account_photo)]
        #[qproperty(i32, library_version)]
        #[qproperty(i32, settings_version)]
        #[qproperty(QString, demo)]
        type WaveApp = super::WaveAppRust;

        /// 起動: キーリングのログインを読み、ログインしていればホームへ、いなければログインの入り口へ。
        #[qinvokable]
        fn start(self: Pin<&mut WaveApp>);

        /// ページの中身を読む。終わったら loaded(token, json) を出す。kind は app.rs の request を見る。
        #[qinvokable]
        fn request(self: Pin<&mut WaveApp>, kind: &QString, arg: &QString, extra: &QString) -> i32;
        #[qsignal]
        fn loaded(self: Pin<&mut WaveApp>, token: i32, json: QString);
        #[qsignal]
        fn toast(self: Pin<&mut WaveApp>, text: QString);
        #[qsignal]
        fn raise_requested(self: Pin<&mut WaveApp>);

        #[qinvokable]
        fn position_ms(self: &WaveApp) -> i32;
        #[qinvokable]
        fn toggle_play(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn next(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn previous(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn seek(self: Pin<&mut WaveApp>, position_ms: i32);
        #[qinvokable]
        fn set_volume_level(self: Pin<&mut WaveApp>, value: f64);
        #[qinvokable]
        fn toggle_mute(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn toggle_shuffle(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn cycle_repeat(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn toggle_like(self: Pin<&mut WaveApp>);

        /// 曲の並び（JSON の配列。各要素の "song" を読む）の index から鳴らす。source_id はアルバム・プレイリストの ID（無ければ空）。
        #[qinvokable]
        fn play_songs(self: Pin<&mut WaveApp>, songs_json: &QString, index: i32, source_id: &QString, shuffle: bool);
        /// カードの再生ボタン（ページを開かずに鳴らす）。item_json はカードの値。
        #[qinvokable]
        fn play_item(self: Pin<&mut WaveApp>, item_json: &QString, shuffle: bool);
        #[qinvokable]
        fn play_next_song(self: Pin<&mut WaveApp>, song_json: &QString);
        #[qinvokable]
        fn add_to_queue(self: Pin<&mut WaveApp>, song_json: &QString);
        #[qinvokable]
        fn play_queue_item(self: Pin<&mut WaveApp>, index: i32);
        #[qinvokable]
        fn remove_queue_item(self: Pin<&mut WaveApp>, item_id: &QString);
        #[qinvokable]
        fn clear_user_queue(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn clear_flow(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn set_song_liked(self: Pin<&mut WaveApp>, song_json: &QString, liked: bool);
        #[qinvokable]
        fn add_song_to_playlist(self: Pin<&mut WaveApp>, song_json: &QString, playlist_id: &QString, title: &QString);
        #[qinvokable]
        fn set_subscribed(self: Pin<&mut WaveApp>, channel_id: &QString, subscribed: bool);

        /// 自分で編集できるプレイリスト（曲のメニューの「プレイリストに追加」）。
        #[qinvokable]
        fn editable_playlists(self: &WaveApp) -> QString;
        /// マイライブラリの一覧（kind: ""・playlist・artist・album・podcast、query: 中の検索）。並べ方は設定のまま。
        #[qinvokable]
        fn library_entries(self: &WaveApp, kind: &QString, query: &QString) -> QString;
        #[qinvokable]
        fn reload_library(self: Pin<&mut WaveApp>);

        #[qinvokable]
        fn settings_json(self: &WaveApp) -> QString;
        /// 設定を1つ変える（key は settings.json の名前、value_json はその値の JSON）。
        #[qinvokable]
        fn set_setting(self: Pin<&mut WaveApp>, key: &QString, value_json: &QString);
        #[qinvokable]
        fn remember_search(self: Pin<&mut WaveApp>, entry_json: &QString);
        #[qinvokable]
        fn forget_search(self: Pin<&mut WaveApp>, key: &QString);
        #[qinvokable]
        fn clear_search_history(self: Pin<&mut WaveApp>);

        /// ログイン: ブラウザに残っているログイン（一覧は loaded で返す）、選んだものを使う、GTK 版のログインの窓を開く。
        #[qinvokable]
        fn browser_profiles(self: Pin<&mut WaveApp>) -> i32;
        #[qinvokable]
        fn use_browser_profile(self: Pin<&mut WaveApp>, index: i32) -> i32;
        #[qinvokable]
        fn open_login_window(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn log_out(self: Pin<&mut WaveApp>);
    }

    impl cxx_qt::Threading for WaveApp {}

    #[auto_cxx_name]
    unsafe extern "RustQt" {
        /// Omarchy のテーマの色と角の丸み（colors.toml を見張る）。Omarchy でなければ omarchy は false。
        #[qobject]
        #[qml_element]
        #[qml_singleton]
        #[qproperty(bool, omarchy)]
        #[qproperty(bool, dark)]
        #[qproperty(QString, accent)]
        #[qproperty(QString, accent_text)]
        #[qproperty(QString, background)]
        #[qproperty(QString, view)]
        #[qproperty(QString, raised)]
        #[qproperty(QString, foreground)]
        #[qproperty(QString, border)]
        #[qproperty(QString, red)]
        #[qproperty(QString, green)]
        #[qproperty(QString, yellow)]
        // Hyprland の窓の角の丸み（px）。Hyprland でなければ -1（画面の側が既定の丸みを使う）
        #[qproperty(i32, rounding)]
        type Theme = super::ThemeRust;

        #[qinvokable]
        fn reload(self: Pin<&mut Theme>);
        /// アイコンの SVG の場所（GTK 版と同じ Adwaita の記号の絵）。見つからなければ空。
        #[qinvokable]
        fn icon(self: &Theme, name: &QString) -> QString;
    }

    impl cxx_qt::Threading for Theme {}
    impl cxx_qt::Initialize for Theme {}
}

pub use crate::app::WaveAppRust;
pub use crate::theme::ThemeRust;
