//! WaveApp（QML の WaveApp）: 中身（wave-desktop の Services）を QML に渡す。
//! 主スレッドの仕事は glib の主ループで回す（Qt は Linux では glib の主ループの上で動くので、GTK 版と同じ部品がそのまま動く）。
//! 読み込みの結果は loaded(token, json) で返し、再生の様子はプロパティで渡す（変わったら sync で入れ直す）。

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::rc::Rc;

use cxx_qt::{CxxQtThread, Threading};
use cxx_qt_lib::QString;
use serde_json::{Value, json};
use wave_core::innertube::InnerTube;
use wave_core::lyrics::search::normalize;
use wave_core::lyrics::service::LyricsResult;
use wave_core::lyrics::SongLyrics;
use wave_core::music::models::{HomeItem, Song};
use wave_core::music::repository::{LIKED_SONGS, MusicRepository};
use wave_desktop::playback::{Origin, QueueItem, Repeat};
use wave_desktop::services::{Services, language, region};
use wave_desktop::settings::{SearchHistoryEntry, Settings};
use wave_desktop::{browser_login, connect, discord_status, keyring, mpris, runtime};

use crate::bridge::qobject;
use crate::view;

#[derive(Default)]
pub struct WaveAppRust {
    pub phase: QString,
    pub logged_in: bool,
    pub has_song: bool,
    pub title: QString,
    pub artists: QString,
    pub artist_id: QString,
    pub album: QString,
    pub album_id: QString,
    pub art: QString,
    pub thumb: QString,
    pub video_id: QString,
    pub song_json: QString,
    pub playing: bool,
    pub live: bool,
    pub duration_ms: i32,
    pub volume: f64,
    pub shuffle: bool,
    pub repeat_mode: i32,
    pub liked: i32,
    pub remote_label: QString,
    pub connect_configured: bool,
    pub next_json: QString,
    pub queue_json: QString,
    pub history_json: QString,
    pub account_name: QString,
    pub account_photo: QString,
    pub library_version: i32,
    pub settings_version: i32,
    pub demo: QString,
    pub ai_version: i32,
    pub devices_version: i32,
    pub downloads_version: i32,
    pub has_video: bool,
    pub show_video: bool,
    pub video_state: QString,
}

/// マイライブラリの読んだもの。
#[derive(Default)]
struct Library {
    playlists: Vec<Value>,
    artists: Vec<Value>,
    albums: Vec<Value>,
    podcasts: Vec<Value>,
    /// 最近再生したものの目印（並べ方の「最近」）。
    recent: Vec<String>,
}

/// アプリの中身（主スレッドだけで使う）。
struct Core {
    repo: MusicRepository,
    thread: CxxQtThread<qobject::WaveApp>,
    services: Option<Rc<Services>>,
    mpris: Option<Rc<mpris::Mpris>>,
    /// 曲ごとの評価（分かったものだけ）。
    liked: HashMap<String, bool>,
    library: Library,
    profiles: Vec<browser_login::Profile>,
    volume_before_mute: f64,
}

thread_local! {
    static CORE: RefCell<Option<Core>> = const { RefCell::new(None) };
    static TOKEN: Cell<i32> = const { Cell::new(0) };
}

fn with_core<T>(f: impl FnOnce(&mut Core) -> T) -> Option<T> {
    CORE.with(|c| c.borrow_mut().as_mut().map(f))
}

pub(crate) fn services() -> Option<Rc<Services>> {
    CORE.with(|c| c.borrow().as_ref().and_then(|c| c.services.clone()))
}

pub(crate) fn repo() -> Option<MusicRepository> {
    CORE.with(|c| c.borrow().as_ref().map(|c| c.repo.clone()))
}

pub(crate) fn core_thread() -> Option<CxxQtThread<qobject::WaveApp>> {
    CORE.with(|c| c.borrow().as_ref().map(|c| c.thread.clone()))
}

pub(crate) fn next_token() -> i32 {
    TOKEN.with(|t| {
        t.set(t.get() + 1);
        t.get()
    })
}

/// 画面の下に短い知らせを出す。
pub(crate) fn toast(text: &str) {
    let text = text.to_string();
    if let Some(thread) = core_thread() {
        let _ = thread.queue(move |app| app.toast(QString::from(&text)));
    }
}

/// 再生の様子をプロパティに入れ直す（次の主ループの回で）。
pub(crate) fn request_sync() {
    if let Some(thread) = core_thread() {
        let _ = thread.queue(|app| app.sync());
    }
}

pub(crate) fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

pub(crate) fn parse_song(json: &QString) -> Option<Song> {
    let value: Value = serde_json::from_str(&json.to_string()).ok()?;
    serde_json::from_value(value.get("song").cloned().unwrap_or(value)).ok()
}

pub(crate) fn failure(e: impl std::fmt::Display) -> Value {
    json!({ "error": e.to_string() })
}

impl qobject::WaveApp {
    pub fn start(mut self: Pin<&mut Self>) {
        if CORE.with(|c| c.borrow().is_some()) {
            return;
        }
        self.as_mut().set_phase(QString::from("loading"));
        self.as_mut().set_volume(1.0);
        self.as_mut().set_liked(-1);
        self.as_mut().set_demo(QString::from(&std::env::var("WAVE_DEMO").unwrap_or_default()));
        // 窓が前面にあるか（端末の連携が使う）
        connect::set_foreground(crate::bridge::qobject::wave_window_active);
        let repo = MusicRepository::new(InnerTube::new(&language(), &region()));
        // 曲名を元の表記で（設定）。最初の読み取りから効かせる
        repo.client().set_original_titles(Settings::load().original_titles);
        let thread = self.qt_thread();
        CORE.with(|c| {
            *c.borrow_mut() = Some(Core { repo: repo.clone(), thread, services: None, mpris: None, liked: HashMap::new(), library: Library::default(), profiles: Vec::new(), volume_before_mute: 0.7 })
        });
        glib::spawn_future_local(async move {
            let saved = keyring::load().await;
            let logged_in = saved.is_some();
            repo.client().set_credentials(saved);
            if logged_in || std::env::var_os("WAVE_SKIP_LOGIN").is_some() {
                enter_main().await;
            } else if let Some(thread) = core_thread() {
                let _ = thread.queue(|mut app| {
                    app.as_mut().set_logged_in(false);
                    app.set_phase(QString::from("login"));
                });
            }
        });
    }

    /// 再生の様子（ほかの端末を映している間はその端末の様子）をプロパティに入れる。
    pub fn sync(mut self: Pin<&mut Self>) {
        let Some(services) = services() else { return };
        let mirror = services.connect.mirror();
        let (song, playing, volume, shuffle, repeat, next) = match &mirror {
            Some(m) => (Some(m.song.clone()), m.playing, m.volume_percent.map_or(1.0, |p| f64::from(p) / 100.0), m.shuffle.unwrap_or(false), m.repeat, m.next.clone()),
            None => {
                let player = services.playback.player();
                (services.playback.current(), player.wants_to_play(), player.volume(), services.playback.shuffled(), services.playback.repeat(), services.playback.upcoming().into_iter().next())
            }
        };
        let duration = match &mirror {
            Some(m) => m.song.duration_seconds.map(|s| s as i64 * 1000),
            None => services.playback.player().duration_ms().map(|d| d as i64).or(song.as_ref().and_then(|s| s.duration_seconds).map(|s| s as i64 * 1000)),
        };
        self.as_mut().set_has_song(song.is_some());
        let s = song.clone();
        self.as_mut().set_title(QString::from(s.as_ref().map(|s| s.title.as_str()).unwrap_or("")));
        self.as_mut().set_artists(QString::from(&s.as_ref().map(|s| s.artists_text()).unwrap_or_default()));
        self.as_mut().set_artist_id(QString::from(&s.as_ref().and_then(|s| s.artists.iter().find_map(|a| a.browse_id.clone())).unwrap_or_default()));
        self.as_mut().set_album(QString::from(&s.as_ref().and_then(|s| s.album.as_ref().map(|a| a.title.clone())).unwrap_or_default()));
        self.as_mut().set_album_id(QString::from(&s.as_ref().and_then(|s| s.album.as_ref().and_then(|a| a.browse_id.clone())).unwrap_or_default()));
        self.as_mut().set_art(QString::from(&view::image(s.as_ref().and_then(|s| s.thumbnail_url.as_deref()), view::LARGE)));
        self.as_mut().set_thumb(QString::from(&view::image(s.as_ref().and_then(|s| s.thumbnail_url.as_deref()), 120)));
        self.as_mut().set_song_json(QString::from(&s.as_ref().map(|s| view::song(s).to_string()).unwrap_or_default()));
        self.as_mut().set_live(s.as_ref().is_some_and(|s| s.is_live));
        self.as_mut().set_playing(playing);
        self.as_mut().set_duration_ms(duration.unwrap_or(0).clamp(0, i32::MAX as i64) as i32);
        self.as_mut().set_volume(volume);
        self.as_mut().set_shuffle(shuffle);
        self.as_mut().set_repeat_mode(match repeat {
            Repeat::Off => 0,
            Repeat::All => 1,
            Repeat::One => 2,
        });
        self.as_mut().set_remote_label(QString::from(&mirror.as_ref().map(|m| m.label.clone()).unwrap_or_default()));
        self.as_mut().set_connect_configured(services.connect.info().configured);
        self.as_mut().set_next_json(QString::from(&next.as_ref().map(|s| view::song(s).to_string()).unwrap_or_default()));
        // 評価（分からなければ読みに行く）
        let video_id = s.as_ref().map(|s| s.video_id.clone()).unwrap_or_default();
        let known = with_core(|c| c.liked.get(&video_id).copied()).flatten();
        self.as_mut().set_liked(match known {
            Some(true) => 1,
            Some(false) => 0,
            None => -1,
        });
        if known.is_none() && !video_id.is_empty() && services.repo.client().is_logged_in() && !wave_core::music::feed::is_external(&video_id) {
            load_like(&services, video_id.clone());
        }
        self.as_mut().set_video_id(QString::from(&video_id));
        // キュー（今の曲・自分で足した曲・次に流れる曲）と履歴
        let (items, index) = services.playback.queue_items();
        self.as_mut().set_queue_json(QString::from(&queue_entries(&items, index, false).to_string()));
        self.as_mut().set_history_json(QString::from(&queue_entries(&items, index, true).to_string()));
    }

    pub fn request(self: Pin<&mut Self>, kind: &QString, arg: &QString, extra: &QString) -> i32 {
        let token = next_token();
        let (kind, arg, extra) = (kind.to_string(), arg.to_string(), extra.to_string());
        let thread = self.qt_thread();
        glib::spawn_future_local(async move {
            let value = load(&kind, arg, extra).await;
            let text = value.to_string();
            let _ = thread.queue(move |app| app.loaded(token, QString::from(&text)));
        });
        token
    }

    pub fn position_ms(&self) -> i32 {
        let Some(services) = services() else { return 0 };
        let ms = match services.connect.mirror() {
            Some(mirror) => mirror.position_ms.max(0) as u64,
            None => services.playback.position_ms(),
        };
        ms.min(i32::MAX as u64) as i32
    }

    pub fn toggle_play(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            s.playback.toggle();
        }
    }

    pub fn next(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            s.playback.next();
        }
    }

    pub fn previous(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            s.playback.previous();
        }
    }

    pub fn seek(self: Pin<&mut Self>, position_ms: i32) {
        let Some(s) = services() else { return };
        let position = position_ms.max(0) as u64;
        // ほかの端末を映していれば、その端末をシークする
        if s.connect.mirror().is_some() {
            s.connect.remote_seek(position as i64);
        } else {
            s.playback.player().seek_ms(position);
        }
    }

    pub fn set_volume_level(mut self: Pin<&mut Self>, value: f64) {
        let Some(s) = services() else { return };
        let value = value.clamp(0.0, 1.0);
        if s.connect.mirror().is_some() {
            s.connect.remote_volume((value * 100.0).round() as i32);
        } else {
            s.playback.player().set_volume(value);
        }
        self.as_mut().set_volume(value);
    }

    pub fn toggle_mute(mut self: Pin<&mut Self>) {
        let now = *self.volume();
        let value = if now > 0.0 {
            with_core(|c| c.volume_before_mute = now);
            0.0
        } else {
            with_core(|c| c.volume_before_mute).unwrap_or(0.7)
        };
        self.as_mut().set_volume_level(value);
    }

    pub fn toggle_shuffle(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            s.connect.toggle_shuffle();
        }
        request_sync();
    }

    pub fn cycle_repeat(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            s.connect.cycle_repeat();
        }
        request_sync();
    }

    pub fn toggle_like(mut self: Pin<&mut Self>) {
        let song = self.song_json().clone();
        let liked = *self.liked() == 1;
        self.as_mut().set_song_liked(&song, !liked);
    }

    pub fn play_songs(self: Pin<&mut Self>, songs_json: &QString, index: i32, source_id: &QString, shuffle: bool) {
        let Some(s) = services() else { return };
        let list: Vec<Value> = serde_json::from_str(&songs_json.to_string()).unwrap_or_default();
        let songs: Vec<Song> = list.into_iter().filter_map(|v| serde_json::from_value(v.get("song").cloned().unwrap_or(v)).ok()).collect();
        if songs.is_empty() {
            return;
        }
        let source = source_id.to_string();
        let source = (!source.is_empty()).then_some(source);
        if shuffle {
            s.playback.play_shuffled(songs, source);
        } else {
            let index = (index.max(0) as usize).min(songs.len() - 1);
            s.playback.play_list_from(songs, index, source);
        }
    }

    pub fn play_item(self: Pin<&mut Self>, item_json: &QString, shuffle: bool) {
        let Some(s) = services() else { return };
        let item: Value = serde_json::from_str(&item_json.to_string()).unwrap_or_default();
        let kind = item["kind"].as_str().unwrap_or_default().to_string();
        let id = item["id"].as_str().unwrap_or_default().to_string();
        glib::spawn_future_local(async move {
            let repo = s.repo.clone();
            match kind.as_str() {
                "song" => {
                    if let Ok(song) = serde_json::from_value::<Song>(item["song"].clone()) {
                        s.playback.play_list(vec![song], 0);
                    }
                }
                "album" => {
                    let wanted = id.clone();
                    match runtime::run(async move { repo.album(&wanted).await }).await {
                        Ok(page) if !page.songs.is_empty() => {
                            if shuffle {
                                s.playback.play_shuffled(page.songs, Some(id));
                            } else {
                                s.playback.play_list_from(page.songs, 0, Some(id));
                            }
                        }
                        _ => toast("アルバムを読み込めませんでした"),
                    }
                }
                "playlist" => play_playlist(&s, &id, shuffle).await,
                "artist" => {
                    let wanted = id.clone();
                    match runtime::run(async move { repo.artist(&wanted).await }).await {
                        Ok(page) if !page.top_songs.is_empty() => s.playback.play_artist(&id, page.top_songs, 0, shuffle, page.all_songs_playlist_id),
                        _ => toast("アーティストの曲を読み込めませんでした"),
                    }
                }
                _ => {}
            }
        });
    }

    pub fn play_next_song(self: Pin<&mut Self>, song_json: &QString) {
        if let (Some(s), Some(song)) = (services(), parse_song(song_json)) {
            s.playback.play_next(song);
            toast("次に再生します");
        }
    }

    pub fn add_to_queue(self: Pin<&mut Self>, song_json: &QString) {
        if let (Some(s), Some(song)) = (services(), parse_song(song_json)) {
            s.playback.add_to_queue(song);
            toast("キューに追加しました");
        }
    }

    pub fn play_queue_item(self: Pin<&mut Self>, index: i32) {
        if let Some(s) = services() {
            s.playback.play_at(index.max(0) as usize);
        }
    }

    pub fn remove_queue_item(self: Pin<&mut Self>, item_id: &QString) {
        if let Some(s) = services() {
            s.playback.remove_items(&[item_id.to_string()]);
        }
    }

    /// 自分で足した曲のかたまりを外す（Mac の「キューを消去」）。
    pub fn clear_user_queue(self: Pin<&mut Self>) {
        let Some(s) = services() else { return };
        let (items, Some(index)) = s.playback.queue_items() else { return };
        let end = user_queue_end(&items, index);
        let ids: Vec<String> = items.get(index + 1..end).unwrap_or_default().iter().map(|q| q.item_id.clone()).collect();
        s.playback.remove_items(&ids);
    }

    /// 元の流れの曲をまとめて外し、ラジオも止める（自分で足した曲は残す）。
    pub fn clear_flow(self: Pin<&mut Self>) {
        let Some(s) = services() else { return };
        let (items, Some(index)) = s.playback.queue_items() else { return };
        let Some(current) = items.get(index) else { return };
        let keep: Vec<QueueItem> = items[index + 1..].iter().filter(|q| q.origin == Origin::UserAdded).cloned().collect();
        if s.playback.clear_flow(&current.item_id) && !keep.is_empty() {
            s.playback.insert_items(keep, true, None);
        }
    }

    pub fn set_song_liked(self: Pin<&mut Self>, song_json: &QString, liked: bool) {
        let (Some(s), Some(song)) = (services(), parse_song(song_json)) else { return };
        if !s.repo.client().is_logged_in() {
            toast("ログインすると評価できます");
            return;
        }
        let video_id = song.video_id.clone();
        with_core(|c| c.liked.insert(video_id.clone(), liked));
        request_sync();
        glib::spawn_future_local(async move {
            let repo = s.repo.clone();
            let id = video_id.clone();
            let result = runtime::run(async move { repo.set_liked(&id, liked).await }).await;
            if result.is_err() {
                with_core(|c| c.liked.insert(video_id, !liked));
                toast("評価を変えられませんでした");
                request_sync();
            } else {
                toast(if liked { "高く評価した曲に追加しました" } else { "高く評価した曲から削除しました" });
            }
        });
    }

    pub fn add_song_to_playlist(self: Pin<&mut Self>, song_json: &QString, playlist_id: &QString, title: &QString) {
        let (Some(s), Some(song)) = (services(), parse_song(song_json)) else { return };
        let (playlist_id, title) = (playlist_id.to_string(), title.to_string());
        glib::spawn_future_local(async move {
            let repo = s.repo.clone();
            let result = runtime::run(async move { repo.add_to_playlist(&playlist_id, &song.video_id, false).await }).await;
            use wave_core::music::models::AddToPlaylistResult as R;
            toast(&match result {
                R::Added => format!("「{title}」に追加しました"),
                R::AlreadyInPlaylist => format!("「{title}」にもう入っています"),
                R::NotChecked => "プレイリストを読み切れなかったので、追加しませんでした".to_string(),
                R::Failed => "追加できませんでした".to_string(),
            });
        });
    }

    pub fn set_subscribed(self: Pin<&mut Self>, channel_id: &QString, subscribed: bool) {
        let Some(s) = services() else { return };
        let channel = channel_id.to_string();
        glib::spawn_future_local(async move {
            let repo = s.repo.clone();
            let result = runtime::run(async move { repo.set_subscribed(&channel, subscribed).await }).await;
            toast(match (result.is_ok(), subscribed) {
                (true, true) => "登録しました",
                (true, false) => "登録を解除しました",
                _ => "登録を変えられませんでした",
            });
        });
    }

    pub fn editable_playlists(&self) -> QString {
        let list: Vec<Value> = services().map(|s| s.editable_playlists.borrow().iter().map(view::playlist).collect()).unwrap_or_default();
        QString::from(&Value::Array(list).to_string())
    }

    pub fn library_entries(&self, kind: &QString, query: &QString) -> QString {
        let Some(s) = services() else { return QString::from("[]") };
        let kind = kind.to_string();
        let query = normalize(&query.to_string());
        let sort = s.downloads.settings.borrow().library_sort.clone();
        let wants = |k: &str| kind.is_empty() || kind == k;
        let matches = |title: &str| query.is_empty() || normalize(title).contains(&query);
        let entries = with_core(|c| {
            let lib = &c.library;
            let mut entries: Vec<Value> = Vec::new();
            if wants("playlist") {
                entries.extend(lib.playlists.iter().cloned());
            }
            if wants("artist") {
                entries.extend(lib.artists.iter().cloned());
            }
            if wants("album") {
                entries.extend(lib.albums.iter().cloned());
            }
            // YouTube で保存した番組の後ろに、RSS で登録した番組を並べる
            if wants("podcast") {
                let mut seen = std::collections::HashSet::new();
                let feeds: Vec<Value> = s.podcasts.library.state().subscriptions.iter().map(|f| view::podcast(&f.summary())).collect();
                entries.extend(lib.podcasts.iter().cloned().chain(feeds).filter(|e| seen.insert(e["id"].as_str().unwrap_or_default().to_string())));
            }
            entries.retain(|e| matches(e["title"].as_str().unwrap_or_default()));
            match sort.as_str() {
                "alpha" => entries.sort_by_key(|e| e["title"].as_str().unwrap_or_default().to_lowercase()),
                "added" => {}
                _ => {
                    let rank: HashMap<&str, usize> = lib.recent.iter().enumerate().rev().map(|(i, k)| (k.as_str(), i)).collect();
                    entries.sort_by_key(|e| rank.get(e["id"].as_str().unwrap_or_default()).copied().unwrap_or(usize::MAX));
                }
            }
            entries
        })
        .unwrap_or_default();
        let mut head: Vec<Value> = Vec::new();
        // ポッドキャストで絞ったときは「新しいエピソード」を先頭に（GTK 版と同じ）
        if kind == "podcast" && matches("新しいエピソード") {
            head.push(json!({ "kind": "newepisodes", "id": wave_core::music::repository::NEW_EPISODES_BROWSE, "title": "新しいエピソード", "subtitle": "プレイリスト • 自動", "thumb": "", "round": false }));
        }
        // 高く評価した曲はいつも先頭、ダウンロードはその次（Mac と同じ）
        if wants("playlist") && matches("高く評価した曲") {
            head.push(json!({ "kind": "liked", "id": LIKED_SONGS, "title": "高く評価した曲", "subtitle": "プレイリスト • 自動", "thumb": "", "round": false }));
        }
        if wants("playlist") && matches("ダウンロード") {
            let count = s.downloads.manifest().wanted_songs().iter().filter(|song| s.downloads.state(&song.video_id) == wave_desktop::downloads::SongState::Done).count();
            head.push(json!({ "kind": "downloads", "id": "downloads", "title": "ダウンロード", "subtitle": format!("プレイリスト • 自動 • {count} 曲"), "thumb": "", "round": false }));
        }
        head.extend(entries);
        QString::from(&Value::Array(head).to_string())
    }

    pub fn reload_library(self: Pin<&mut Self>) {
        if let Some(s) = services() {
            load_library(&s);
        }
    }

    pub fn settings_json(&self) -> QString {
        let settings = services().map(|s| s.downloads.settings.borrow().clone()).unwrap_or_else(Settings::load);
        let mut value = serde_json::to_value(&settings).unwrap_or_default();
        // 画面に出す補足（保存はしない）
        value["discord_status"] = Value::String(discord_status::status_text().to_string());
        value["data_dir"] = Value::String(glib::user_data_dir().join("wave").display().to_string());
        value["version"] = Value::String(env!("CARGO_PKG_VERSION").to_string());
        QString::from(&value.to_string())
    }

    pub fn set_setting(mut self: Pin<&mut Self>, key: &QString, value_json: &QString) {
        let Some(s) = services() else { return };
        let (key, raw) = (key.to_string(), value_json.to_string());
        let Ok(value) = serde_json::from_str::<Value>(&raw) else { return };
        let mut ok = false;
        s.downloads.update_settings(|settings| {
            let mut current = serde_json::to_value(&*settings).unwrap_or_default();
            if current.get(&key).is_none() {
                return;
            }
            current[&key] = value.clone();
            if let Ok(next) = serde_json::from_value::<Settings>(current) {
                *settings = next;
                ok = true;
            }
        });
        if !ok {
            eprintln!("setting rejected key={key}");
            return;
        }
        match key.as_str() {
            // 次の読み取り（ページを開く・検索する）から効く
            "original_titles" => s.repo.client().set_original_titles(value.as_bool().unwrap_or(false)),
            "discord_presence" | "discord_client_id" => discord_status::push(),
            "library_sort" => {
                let v = *self.library_version() + 1;
                self.as_mut().set_library_version(v);
            }
            _ => {}
        }
        let v = *self.settings_version() + 1;
        self.as_mut().set_settings_version(v);
    }

    pub fn remember_search(mut self: Pin<&mut Self>, entry_json: &QString) {
        let Some(s) = services() else { return };
        let Ok(value) = serde_json::from_str::<Value>(&entry_json.to_string()) else { return };
        let entry = match value["kind"].as_str().unwrap_or("query") {
            "song" => match serde_json::from_value::<Song>(value["song"].clone()) {
                Ok(song) => SearchHistoryEntry::song(&song),
                Err(_) => return,
            },
            kind => SearchHistoryEntry {
                kind: kind.to_string(),
                title: value["title"].as_str().unwrap_or_default().to_string(),
                subtitle: value["subtitle"].as_str().filter(|t| !t.is_empty()).map(String::from),
                thumbnail_url: value["thumbnailUrl"].as_str().or(value["thumb"].as_str()).filter(|t| !t.is_empty()).map(String::from),
                id: value["id"].as_str().filter(|t| !t.is_empty()).map(String::from),
                song: None,
            },
        };
        s.downloads.update_settings(|settings| settings.add_search(entry));
        let v = *self.settings_version() + 1;
        self.as_mut().set_settings_version(v);
    }

    pub fn forget_search(mut self: Pin<&mut Self>, key: &QString) {
        let Some(s) = services() else { return };
        let key = key.to_string();
        s.downloads.update_settings(|settings| settings.search_history.retain(|e| e.key() != key));
        let v = *self.settings_version() + 1;
        self.as_mut().set_settings_version(v);
    }

    pub fn clear_search_history(mut self: Pin<&mut Self>) {
        let Some(s) = services() else { return };
        s.downloads.update_settings(|settings| settings.search_history.clear());
        let v = *self.settings_version() + 1;
        self.as_mut().set_settings_version(v);
    }

    pub fn browser_profiles(self: Pin<&mut Self>) -> i32 {
        let token = next_token();
        let thread = self.qt_thread();
        glib::spawn_future_local(async move {
            let home = glib::home_dir();
            let profiles = gio::spawn_blocking(move || browser_login::find(&home)).await.unwrap_or_default();
            let labels: Vec<String> = profiles.iter().map(|p| p.label.clone()).collect();
            with_core(|c| c.profiles = profiles);
            let text = json!({ "profiles": labels }).to_string();
            let _ = thread.queue(move |app| app.loaded(token, QString::from(&text)));
        });
        token
    }

    pub fn use_browser_profile(self: Pin<&mut Self>, index: i32) -> i32 {
        let token = next_token();
        let thread = self.qt_thread();
        let profile = with_core(|c| c.profiles.get(index.max(0) as usize).cloned()).flatten();
        glib::spawn_future_local(async move {
            let Some(profile) = profile else { return };
            let result = browser_login::use_profile(&profile).await;
            let text = match &result {
                Ok(_) => json!({ "ok": true }),
                Err(message) => json!({ "ok": false, "title": format!("{}のログインを使えませんでした", profile.label), "message": message }),
            }
            .to_string();
            let _ = thread.queue(move |app| app.loaded(token, QString::from(&text)));
            if let Ok(credentials) = result
                && let Some(repo) = repo()
            {
                repo.client().set_credentials(Some(credentials));
                enter_main().await;
            }
        });
        token
    }

    /// アプリの中の窓でログインする（WebKitGTK のログインの窓は GTK 版にしか無いので、wave-gtk --login を別に開き、
    /// 閉じたらキーリングを読み直す）。
    pub fn open_login_window(self: Pin<&mut Self>) {
        let program = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.join("wave-gtk"))).filter(|p| p.is_file()).unwrap_or_else(|| "wave-gtk".into());
        let child = match std::process::Command::new(&program).arg("--login").spawn() {
            Ok(child) => child,
            Err(e) => {
                eprintln!("login window failed: {}", e.kind());
                toast("ログインの窓を開けませんでした（wave の GTK 版が要ります）");
                return;
            }
        };
        glib::spawn_future_local(async move {
            let mut child = child;
            let _ = gio::spawn_blocking(move || child.wait()).await;
            let saved = keyring::load().await;
            if let (Some(credentials), Some(repo)) = (saved, repo()) {
                repo.client().set_credentials(Some(credentials));
                enter_main().await;
            }
        });
    }

    pub fn log_out(self: Pin<&mut Self>) {
        let Some(s) = services() else { return };
        glib::spawn_future_local(async move {
            s.log_out().await;
            crate::more::detach();
            with_core(|c| {
                c.services = None;
                c.mpris = None;
                c.liked.clear();
                c.library = Library::default();
            });
            if let Some(thread) = core_thread() {
                let _ = thread.queue(|mut app| {
                    app.as_mut().set_logged_in(false);
                    app.as_mut().set_has_song(false);
                    app.as_mut().set_account_name(QString::default());
                    app.as_mut().set_account_photo(QString::default());
                    app.set_phase(QString::from("login"));
                });
            }
        });
    }
}

/// ログインできたら（か、ログインせずに使うと決めたら）中身を動かし、主の画面にする。
async fn enter_main() {
    let Some(repo) = repo() else { return };
    // ログインし直したときは、古い方を止めてから
    with_core(|c| {
        c.services = None;
        c.mpris = None;
    });
    let services = Services::start(repo.clone()).await;
    {
        // 再生の知らせ（飛ばした・止めた理由）と連携の知らせを、画面の下に出す
        services.playback.on_notice(toast);
        services.connect.on_notice(toast);
        services.playback.on_change(request_sync);
        services.connect.on_change(request_sync);
    }
    // メディアキー・GNOME などのメディアの操作（MPRIS）
    let mpris = mpris::start(
        services.playback.clone(),
        || {
            if let Some(thread) = core_thread() {
                let _ = thread.queue(|app| app.raise_requested());
            }
        },
        crate::bridge::qobject::wave_quit,
    );
    discord_status::start(&services);
    with_core(|c| {
        c.services = Some(services.clone());
        c.mpris = Some(mpris);
    });
    // Claude・連携・ダウンロードの見張り、映像、新作の通知
    crate::more::attach(&services);
    let logged_in = repo.client().is_logged_in();
    load_library(&services);
    if logged_in {
        let r = repo.clone();
        glib::spawn_future_local(async move {
            if let Ok(account) = runtime::run(async move { r.account().await }).await
                && let Some(thread) = core_thread()
            {
                let (name, photo) = (account.name.unwrap_or_default(), view::image(account.photo_url.as_deref(), 96));
                let _ = thread.queue(move |mut app| {
                    app.as_mut().set_account_name(QString::from(&name));
                    app.set_account_photo(QString::from(&photo));
                });
            }
        });
    }
    if let Some(thread) = core_thread() {
        let _ = thread.queue(move |mut app| {
            app.as_mut().set_logged_in(logged_in);
            app.as_mut().sync();
            app.set_phase(QString::from("main"));
        });
    }
    // 確かめ用: WAVE_DEMO_DEVICES（offer・code・wrong・show・working・done・failed）で、中継につながずに加入の手順のその様子を出す（GTK 版と同じ）
    if let Ok(state) = std::env::var("WAVE_DEMO_DEVICES") {
        use wave_desktop::connect::PairingView;
        let view = match state.as_str() {
            "offer" => Some(PairingView::ShowingOffer("wave-pair:eyJ2IjoxLCJwa0EiOiJkZW1vIiwiZUEiOiJkZW1vIiwidG9rZW4iOiJkZW1vIn0".into())),
            "code" => Some(PairingView::EnterCode(None)),
            "wrong" => Some(PairingView::EnterCode(Some("数字が違います".into()))),
            "show" => Some(PairingView::ShowCode { code: "123456".into(), peer: "Pixel 3".into() }),
            "working" => Some(PairingView::Working("相手が加えるのを待っています…".into())),
            "done" => Some(PairingView::Done { peer: "Pixel 3".into(), grant_device_id: None }),
            "failed" => Some(PairingView::Failed("時間内に読み取られませんでした".into())),
            _ => None,
        };
        if let Some(view) = view {
            services.connect.show_demo_pairing(view);
        }
    }
    // 確かめ用: WAVE_DEMO_PLAY_ID の動画（MV など）を1曲として鳴らす（題は WAVE_DEMO_PLAY_TITLE）
    if let Ok(id) = std::env::var("WAVE_DEMO_PLAY_ID") {
        let title = std::env::var("WAVE_DEMO_PLAY_TITLE").unwrap_or_else(|_| id.clone());
        let song = Song { video_id: id, title, artists: Vec::new(), album: None, duration_seconds: None, thumbnail_url: None, kind: Some("MUSIC_VIDEO_TYPE_OMV".into()), is_live: false };
        services.playback.play_list(vec![song], 0);
    }
    // 確かめ用: WAVE_DEMO_QUERY の検索の1曲目を鳴らす（GTK 版と同じ）
    if let Ok(query) = std::env::var("WAVE_DEMO_QUERY") {
        let s = services.clone();
        glib::spawn_future_local(async move {
            let repo = s.repo.clone();
            if let Ok(page) = runtime::run(async move { repo.search_songs(&query, None).await }).await
                && let Some(first) = page.songs.into_iter().next()
            {
                s.playback.play_list(vec![first], 0);
            }
        });
    }
}

fn load_like(services: &Rc<Services>, video_id: String) {
    let repo = services.repo.clone();
    glib::spawn_future_local(async move {
        let id = video_id.clone();
        if let Ok(Some(status)) = runtime::run(async move { repo.like_status(&id).await }).await {
            with_core(|c| c.liked.insert(video_id, status == wave_core::music::models::LikeStatus::Like));
            request_sync();
        }
    });
}

/// マイライブラリを読み直す（届いた種類から出す）。
pub(crate) fn load_library(services: &Rc<Services>) {
    if !services.repo.client().is_logged_in() {
        return;
    }
    let s = services.clone();
    let bump = || {
        if let Some(thread) = core_thread() {
            let _ = thread.queue(|mut app| {
                let v = *app.library_version() + 1;
                app.as_mut().set_library_version(v);
            });
        }
    };
    glib::spawn_future_local(async move {
        let repo = s.repo.clone();
        let r = repo.clone();
        if let Ok(playlists) = runtime::run(async move { r.library_playlists().await }).await {
            *s.editable_playlists.borrow_mut() = playlists.iter().filter(|p| p.editable).cloned().collect();
            let list: Vec<Value> = playlists.iter().filter(|p| p.playlist_id != LIKED_SONGS).map(|p| library_entry(view::playlist(p), "プレイリスト")).collect();
            with_core(|c| c.library.playlists = list);
            bump();
        }
        let r = repo.clone();
        if let Ok(artists) = runtime::run(async move { r.library_artists().await }).await {
            let list: Vec<Value> = artists.iter().map(|a| library_entry(view::artist(a), "アーティスト")).collect();
            with_core(|c| c.library.artists = list);
            bump();
        }
        let r = repo.clone();
        if let Ok(albums) = runtime::run(async move { r.library_albums().await }).await {
            let list: Vec<Value> = albums.iter().map(|a| library_entry(view::album(a), "アルバム")).collect();
            with_core(|c| c.library.albums = list);
            bump();
        }
        let r = repo.clone();
        if let Ok(shows) = runtime::run(async move { r.library_podcasts().await }).await {
            let list: Vec<Value> = shows.iter().map(|p| library_entry(view::podcast(p), "ポッドキャスト")).collect();
            with_core(|c| c.library.podcasts = list);
            bump();
        }
        let r = repo.clone();
        if let Ok(recent) = runtime::run(async move { r.recent_activity().await }).await {
            let keys: Vec<String> = recent.iter().map(|i| view::home_item(i)["id"].as_str().unwrap_or_default().to_string()).collect();
            with_core(|c| c.library.recent = keys);
            bump();
        }
    });
}

/// マイライブラリの行の補足は「種類 • 元の補足」（GTK 版と同じ）。
fn library_entry(mut value: Value, kind: &str) -> Value {
    let rest = value["subtitle"].as_str().unwrap_or_default().to_string();
    value["subtitle"] = Value::String(if rest.is_empty() { kind.to_string() } else { format!("{kind} • {rest}") });
    value
}

/// プレイリストを頭から流す（最初のページで鳴らし始め、残りは読めたら足す）。
async fn play_playlist(s: &Rc<Services>, playlist_id: &str, shuffle: bool) {
    let (repo, id) = (s.repo.clone(), playlist_id.to_string());
    let Ok(first) = runtime::run(async move { repo.playlist(&id, None).await }).await else {
        toast("プレイリストを読み込めませんでした");
        return;
    };
    if first.songs.is_empty() {
        toast("プレイリストを読み込めませんでした");
        return;
    }
    let more = first.continuation.is_some();
    s.playback.play_list_pending(first.songs, 0, Some(playlist_id.to_string()), more);
    if shuffle {
        s.playback.set_shuffle(true);
    }
    if !more {
        return;
    }
    let (repo, id) = (s.repo.clone(), playlist_id.to_string());
    let all = runtime::run(async move { wave_desktop::playback::full_playlist(&repo, &id).await }).await;
    if s.playback.is_playing_source(playlist_id) {
        s.playback.append_from(all);
    }
}

/// 今の曲のすぐ後ろに続く、自分で足した曲のかたまりの終わり（その次の位置）。
fn user_queue_end(items: &[QueueItem], index: usize) -> usize {
    let mut p = index + 1;
    while items.get(p).is_some_and(|q| q.origin == Origin::UserAdded) {
        p += 1;
    }
    p
}

/// キューの欄の並び（GTK 版・Mac と同じ）: 一番上に今の曲、その下に自分で足した曲、その下に元から流れる予定の曲。
/// history なら、もう流れた曲を新しい順に。
fn queue_entries(items: &[QueueItem], index: Option<usize>, history: bool) -> Value {
    let Some(index) = index.filter(|&i| i < items.len()) else { return json!([]) };
    let entry = |i: usize, section: &str, flow_header: bool| {
        let mut value = view::song(&items[i].song);
        value["index"] = json!(i);
        value["itemId"] = json!(items[i].item_id);
        value["current"] = json!(i == index && !history);
        value["section"] = json!(section);
        value["flowHeader"] = json!(flow_header);
        value
    };
    if history {
        return Value::Array((0..index).rev().map(|i| entry(i, "", false)).collect());
    }
    let user_end = user_queue_end(items, index);
    Value::Array(
        (index..items.len())
            .map(|i| {
                let section = if i == index {
                    "再生中"
                } else if i == index + 1 && i < user_end {
                    "キューの次の曲"
                } else if i == user_end {
                    "次に流れる曲"
                } else {
                    ""
                };
                entry(i, section, i == user_end && i > index)
            })
            .collect(),
    )
}

type Loading = Pin<Box<dyn Future<Output = Value>>>;

/// request の中身。kind ごとに読み、QML に渡す JSON を返す。
fn load(kind: &str, arg: String, extra: String) -> Loading {
    let Some(repo) = repo() else { return Box::pin(async { failure("not started") }) };
    let services = services();
    let continuation = (!extra.is_empty()).then_some(extra.clone());
    match kind {
        "home" => Box::pin(load_home(repo, services, arg, continuation)),
        "album" => Box::pin(async move {
            match runtime::run(async move { repo.album(&arg).await }).await {
                Ok(page) => view::album_page(&page),
                Err(e) => failure(e),
            }
        }),
        "playlist" => Box::pin(async move {
            let id = arg.clone();
            match runtime::run(async move { repo.playlist(&id, continuation.as_deref()).await }).await {
                Ok(page) => view::playlist_page(&arg, &page),
                Err(e) => failure(e),
            }
        }),
        "artist" => Box::pin(async move {
            let id = arg.clone();
            match runtime::run(async move { repo.artist(&id).await }).await {
                Ok(page) => view::artist_page(&arg, &page),
                Err(e) => failure(e),
            }
        }),
        "search.all" => Box::pin(async move {
            let q = arg.clone();
            let (songs, albums, artists) = runtime::run(async move { tokio::join!(repo.search_songs(&q, None), repo.search_albums(&q, None), repo.search_artists(&q, None)) }).await;
            let (songs, albums, artists) = (songs.map(|p| p.songs).unwrap_or_default(), albums.map(|p| p.items).unwrap_or_default(), artists.map(|p| p.items).unwrap_or_default());
            let top = wave_core::music::collection::top_result(&arg, &songs, &artists, &albums);
            json!({
                "top": top.as_ref().map(view::home_item),
                "songs": view::songs(&songs.iter().take(4).cloned().collect::<Vec<_>>()),
                "artists": artists.iter().map(view::artist).collect::<Vec<_>>(),
                "albums": albums.iter().map(view::album).collect::<Vec<_>>(),
            })
        }),
        "search.songs" | "search.live" => {
            let live = kind == "search.live";
            Box::pin(async move {
                let page = runtime::run(async move { if live { repo.search_live(&arg, continuation.as_deref()).await } else { repo.search_songs(&arg, continuation.as_deref()).await } }).await;
                match page {
                    Ok(p) => json!({ "items": view::songs(&p.songs), "continuation": p.continuation.unwrap_or_default() }),
                    Err(e) => failure(e),
                }
            })
        }
        "search.albums" => Box::pin(async move {
            match runtime::run(async move { repo.search_albums(&arg, continuation.as_deref()).await }).await {
                Ok(p) => json!({ "items": p.items.iter().map(view::album).collect::<Vec<_>>(), "continuation": p.continuation.unwrap_or_default() }),
                Err(e) => failure(e),
            }
        }),
        "search.artists" => Box::pin(async move {
            match runtime::run(async move { repo.search_artists(&arg, continuation.as_deref()).await }).await {
                Ok(p) => json!({ "items": p.items.iter().map(view::artist).collect::<Vec<_>>(), "continuation": p.continuation.unwrap_or_default() }),
                Err(e) => failure(e),
            }
        }),
        "search.podcasts" => Box::pin(async move {
            match runtime::run(async move { repo.search_podcasts(&arg, continuation.as_deref()).await }).await {
                Ok(p) => json!({ "items": p.items.iter().map(view::podcast).collect::<Vec<_>>(), "continuation": p.continuation.unwrap_or_default() }),
                Err(e) => failure(e),
            }
        }),
        "search.episodes" => Box::pin(async move {
            match runtime::run(async move { repo.search_episodes(&arg, continuation.as_deref()).await }).await {
                Ok(p) => json!({ "items": p.items.iter().map(view::episode).collect::<Vec<_>>(), "continuation": p.continuation.unwrap_or_default() }),
                Err(e) => failure(e),
            }
        }),
        "suggestions" => Box::pin(async move {
            match runtime::run(async move { repo.suggestions(&arg).await }).await {
                Ok(s) => json!({
                    "queries": s.queries,
                    "items": s.songs.iter().map(view::song).chain(s.artists.iter().map(view::artist)).chain(s.albums.iter().map(view::album)).collect::<Vec<_>>(),
                }),
                Err(e) => failure(e),
            }
        }),
        "moods" => Box::pin(async move {
            match runtime::run(async move { repo.moods().await }).await {
                Ok(sections) => {
                    let tiles: Vec<Value> = sections
                        .iter()
                        .flat_map(|s| s.categories.iter())
                        .enumerate()
                        .map(|(i, m)| {
                            let (r, g, b) = wave_desktop::art::tile_color(m.color, i);
                            json!({ "title": m.title, "params": m.params, "color": format!("#{r:02x}{g:02x}{b:02x}") })
                        })
                        .collect();
                    json!({ "tiles": tiles })
                }
                Err(e) => failure(e),
            }
        }),
        "mood" => Box::pin(async move {
            match runtime::run(async move { repo.mood_category(&arg).await }).await {
                Ok(page) => json!({ "shelves": view::shelves(&page.shelves) }),
                Err(e) => failure(e),
            }
        }),
        "history" => Box::pin(async move {
            match runtime::run(async move { repo.history_sections().await }).await {
                Ok(sections) => json!({ "sections": sections.iter().map(|(title, songs)| json!({ "title": title.clone().unwrap_or_default(), "songs": view::songs(songs) })).collect::<Vec<_>>() }),
                Err(e) => failure(e),
            }
        }),
        "lyrics" => Box::pin(async move { load_lyrics(services).await }),
        // 番組のページ（arg は browseId（MPSP…・rss:…・新しいエピソード）、extra は題）・続き（extra は「題\n続き」）
        "podcast" => Box::pin(crate::more::load_podcast(arg, extra, None)),
        "podcast.more" => {
            let (title, token) = extra.split_once('\n').map(|(a, b)| (a.to_string(), b.to_string())).unwrap_or_default();
            Box::pin(crate::more::load_podcast(arg, title, Some(token)))
        }
        "feed.search" => Box::pin(crate::more::search_feeds(arg)),
        "account" => Box::pin(async move {
            let logged_in = repo.client().is_logged_in();
            let premium = match &services {
                Some(s) if logged_in => s.playback.player().premium_audio().await,
                _ => None,
            };
            json!({ "loggedIn": logged_in, "premium": premium })
        }),
        other => {
            let other = other.to_string();
            Box::pin(async move { failure(format!("unknown request {other}")) })
        }
    }
}

async fn load_home(repo: MusicRepository, services: Option<Rc<Services>>, chip: String, continuation: Option<String>) -> Value {
    let chip = (!chip.is_empty()).then_some(chip);
    if let Some(token) = continuation {
        let page = runtime::run(async move { repo.home(Some(&token), None).await }).await;
        return match page {
            Ok(page) => json!({ "shelves": view::shelves(&page.shelves), "continuation": page.continuation.unwrap_or_default() }),
            Err(e) => failure(e),
        };
    }
    let all = chip.is_none();
    let logged_in = repo.client().is_logged_in();
    let r = repo.clone();
    let (home, new_releases, recent) = runtime::run(async move {
        let home = async {
            // 最初は棚が8つ以上になるまで続きを読む（Mac・Android・GTK 版と同じ）。起動直後の時間切れは1回だけやり直す
            let mut first = r.home(None, chip.as_deref()).await;
            if matches!(first, Err(wave_core::innertube::Failure::Network)) {
                first = r.home(None, chip.as_deref()).await;
            }
            let mut page = first?;
            let mut seen: std::collections::HashSet<String> = page.shelves.iter().map(wave_core::music::releases::shelf_key).collect();
            for _ in 0..3 {
                let Some(token) = page.continuation.clone().filter(|_| page.shelves.len() < 8) else { break };
                let Ok(more) = r.home(Some(&token), None).await else { break };
                page.shelves.extend(more.shelves.into_iter().filter(|s| seen.insert(wave_core::music::releases::shelf_key(s))));
                page.continuation = more.continuation;
            }
            Ok::<_, wave_core::innertube::Failure>(page)
        };
        let new_releases = async { if all { r.new_releases().await.unwrap_or_default() } else { Vec::new() } };
        let recent = async { if all && logged_in { r.recent_activity().await.unwrap_or_default() } else { Vec::new() } };
        tokio::join!(home, new_releases, recent)
    })
    .await;
    let page = match home {
        Ok(page) => page,
        Err(e) => return failure(e),
    };
    // 登録したアーティストの新作（「すべて」のときだけ。アーティストごとに2件まで交互に、見つけて7日は NEW）
    let releases: Vec<HomeItem> = match (&services, all) {
        (Some(s), true) => wave_core::music::releases::diversified(&s.releases.recent(), 2)
            .into_iter()
            .take(wave_core::music::releases::NEW_RELEASES_COUNT)
            .filter_map(|r| wave_core::music::releases::release_item(&r, now_ms()))
            .collect(),
        _ => Vec::new(),
    };
    // 新作の棚（ホームに同じ新作の棚が来ていれば出さない）
    let new_shelf = if new_releases.is_empty() || wave_core::music::releases::duplicates_new_releases(&new_releases, &page.shelves) {
        None
    } else {
        wave_core::music::releases::new_releases_shelf("新作", new_releases)
    };
    json!({
        "chips": page.chips.iter().map(|c| json!({ "title": c.title, "params": c.params })).collect::<Vec<_>>(),
        "recent": recent.iter().take(8).map(view::home_item).collect::<Vec<_>>(),
        "releases": if releases.is_empty() { Value::Null } else { view::shelf("登録したアーティストの新作", None, &releases) },
        "newReleases": new_shelf.map(|s| view::shelf(&s.title, None, &s.items)),
        "shelves": view::shelves(&page.shelves),
        "continuation": page.continuation.unwrap_or_default(),
    })
}

/// 今の曲の歌詞（時刻付きなら行と時刻、無ければ文）。
async fn load_lyrics(services: Option<Rc<Services>>) -> Value {
    let Some(s) = services else { return json!({ "state": "empty" }) };
    let song = match s.connect.mirror() {
        Some(m) => Some(m.song),
        None => s.playback.current(),
    };
    let Some(song) = song else { return json!({ "state": "empty", "message": "曲を再生すると、ここに歌詞が出ます" }) };
    let base = json!({ "videoId": song.video_id, "art": view::image(song.thumbnail_url.as_deref(), view::LARGE) });
    let with = |mut value: Value| {
        value["videoId"] = base["videoId"].clone();
        value["art"] = base["art"].clone();
        value
    };
    if song.is_live {
        return with(json!({ "state": "message", "message": "ライブ配信には歌詞がありません" }));
    }
    // ポッドキャストのエピソードなど YouTube Music の外の音には歌詞を探さない
    if wave_core::music::feed::is_external(&song.video_id) {
        return with(json!({ "state": "message", "message": "この曲の歌詞は見つかりませんでした" }));
    }
    let service = s.lyrics.clone();
    let result = runtime::run(async move { service.lyrics(&song).await }).await;
    with(match result {
        LyricsResult::Found(SongLyrics::Synced { lines, source }) => json!({
            "state": "synced",
            "lines": lines.iter().enumerate().map(|(i, l)| json!({
                "ms": l.start_ms,
                "text": if l.text.trim().is_empty() { "♪".to_string() } else { l.text.clone() },
                // 同じ時刻の行（原文に続く読み・訳）は、前の行に添える小さな行として出す
                "sub": wave_core::lyrics::lrc::is_sub_line(&lines, i),
            })).collect::<Vec<_>>(),
            "source": source.map(|s| wave_desktop::labels::lyrics_source(&s)).unwrap_or_default(),
        }),
        LyricsResult::Found(SongLyrics::Plain { text, source }) => json!({ "state": "plain", "text": text, "source": source.unwrap_or_default() }),
        LyricsResult::None => json!({ "state": "message", "message": "この曲の歌詞は見つかりませんでした" }),
        LyricsResult::Failed => json!({ "state": "message", "message": "歌詞を読み込めませんでした", "retry": true }),
    })
}
