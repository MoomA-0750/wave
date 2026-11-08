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

        include!("videoitem.h");
        fn wave_register_video_item();
        fn wave_video_frame(data: &[u8], width: i32, height: i32, stride: i32);
        fn wave_video_clear();
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
        // 変わったら増やす（QML が読み直す）: Claude の会話・端末の連携・ダウンロード
        #[qproperty(i32, ai_version)]
        #[qproperty(i32, devices_version)]
        #[qproperty(i32, downloads_version)]
        // 映像: 今の曲に MV があるか・「動画」にしているか・その様子（off・loading・failed・showing・gap）
        #[qproperty(bool, has_video)]
        #[qproperty(bool, show_video)]
        #[qproperty(QString, video_state)]
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

        /// Claude の欄（会話・これまでの会話・つないでいるか・答えを待っているか）。
        #[qinvokable]
        fn ai_json(self: &WaveApp) -> QString;
        #[qinvokable]
        fn ai_send(self: Pin<&mut WaveApp>, text: &QString);
        #[qinvokable]
        fn ai_stop(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn ai_clear(self: Pin<&mut WaveApp>);
        #[qinvokable]
        fn ai_open(self: Pin<&mut WaveApp>, id: &QString);
        #[qinvokable]
        fn ai_delete(self: Pin<&mut WaveApp>, id: &QString);
        #[qinvokable]
        fn ai_pair(self: Pin<&mut WaveApp>, text: &QString) -> i32;
        #[qinvokable]
        fn ai_unpair(self: Pin<&mut WaveApp>);
        /// 答えの中の [mm:ss]（話している曲が鳴っているときだけ飛ぶ）。
        #[qinvokable]
        fn ai_seek(self: Pin<&mut WaveApp>, video_id: &QString, position_ms: i32);

        /// 端末の連携（知らせの帯・ほかの端末の再生・加入の手順・端末の一覧・中継）。
        #[qinvokable]
        fn devices_json(self: &WaveApp) -> QString;
        /// start_offer・accept（arg は文字列）・create_household・confirm_on_b・cancel_pairing・forget_household・
        /// accept_store_reset・transfer_here・play_on_device（arg は端末 ID）。
        #[qinvokable]
        fn connect_action(self: Pin<&mut WaveApp>, name: &QString, arg: &QString);
        #[qinvokable]
        fn connect_enter_code(self: Pin<&mut WaveApp>, code: &QString) -> bool;
        #[qinvokable]
        fn connect_remove(self: Pin<&mut WaveApp>, device_id: &QString, label: &QString);
        #[qinvokable]
        fn connect_grant(self: Pin<&mut WaveApp>, device_id: &QString, label: &QString);

        /// ダウンロード（容量と進み具合・スマートダウンロード・丸ごと取ったもの・曲・エピソード）。
        #[qinvokable]
        fn downloads_json(self: &WaveApp, category: &QString) -> QString;
        /// 0: 取っていない、1: 待っている、2: 取っている、3: 取った。
        #[qinvokable]
        fn download_state(self: &WaveApp, video_id: &QString) -> i32;
        #[qinvokable]
        fn download_song(self: Pin<&mut WaveApp>, song_json: &QString);
        #[qinvokable]
        fn remove_download(self: Pin<&mut WaveApp>, video_id: &QString);
        #[qinvokable]
        fn download_collection(self: Pin<&mut WaveApp>, kind: &QString, id: &QString, title: &QString, thumbnail: &QString, songs_json: &QString);
        #[qinvokable]
        fn remove_collection(self: Pin<&mut WaveApp>, id: &QString);
        #[qinvokable]
        fn clear_downloads(self: Pin<&mut WaveApp>);

        /// 番組の保存（YouTube）・登録（RSS）。終わったら loaded で { ok }。
        #[qinvokable]
        fn set_podcast_saved(self: Pin<&mut WaveApp>, show_json: &QString, saved: bool) -> i32;
        /// プレイリストを作る（song_json があればその曲を入れる）。終わったら loaded で { ok, id }。
        #[qinvokable]
        fn create_playlist(self: Pin<&mut WaveApp>, title: &QString, song_json: &QString) -> i32;
        /// プレイリストから外す。終わったら loaded で { ok }。
        #[qinvokable]
        fn remove_from_playlist(self: Pin<&mut WaveApp>, playlist_id: &QString, video_id: &QString, set_video_id: &QString) -> i32;

        /// 「曲｜動画」の動画にする・やめる。
        #[qinvokable]
        fn set_video_on(self: Pin<&mut WaveApp>, on: bool);
        #[qsignal]
        fn open_requested(self: Pin<&mut WaveApp>, kind: QString, id: QString, title: QString);
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
