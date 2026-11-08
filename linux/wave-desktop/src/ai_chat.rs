//! Claude との会話（主スレッド。Windows の AiChat と同じ流れ）。頼みを中継へ送り、届いた文を足していき、
//! 道具の呼び出しは手元で行って結果を返す。この会話で出てきた曲の id だけを受ける（知らない id は断る）。

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use serde_json::{Value, json};
use tokio::sync::mpsc;
use wave_core::ai::bridge::{Bridge, BridgeConfig, BridgeEvent};
use wave_core::ai::history::{self, Conversation, SavedMessage};
use wave_core::ai::spec::AgentSpec;
use wave_core::ai::text;
use wave_core::lyrics::service::LyricsResult;
use wave_core::music::models::{AddToPlaylistResult, Song};
use wave_core::music::repository::LIKED_SONGS;

use crate::keyring;
use crate::runtime;
use crate::services::Services as App;

/// 会話の1つ分の表示。
#[derive(Clone)]
pub enum AiMessage {
    User(String),
    /// Claude の答え。video_id は時刻付きの歌詞を添えて聞いたときの曲（答えの中の [mm:ss] はこの曲が鳴っているときだけ押せる）。
    Assistant(String, Option<String>),
    Action(String),
    Songs(Vec<Song>),
    Failure(String),
}

pub struct AiChat {
    pub messages: RefCell<Vec<AiMessage>>,
    pub running: Cell<bool>,
    config: RefCell<Option<BridgeConfig>>,
    known: RefCell<HashMap<String, Song>>,
    task: RefCell<Option<tokio::task::JoinHandle<()>>>,
    /// 会話の世代。止めた・新しい会話にしたら変え、古い流れの結果を捨てる。
    generation: Cell<u64>,
    listeners: RefCell<Vec<Box<dyn Fn()>>>,
    /// 今の会話の id（新しい会話ごとに変わる。履歴から開けばその会話の id）と、始めた時刻。
    conversation_id: RefCell<String>,
    created_at_ms: Cell<i64>,
    /// これまでの会話（~/.local/share/wave/ai_history.json。新しく話した順）。
    pub history: RefCell<Vec<Conversation>>,
}

fn history_path() -> std::path::PathBuf {
    glib::user_data_dir().join("wave").join("ai_history.json")
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn new_id() -> String {
    format!("{:x}-{:x}", now_ms(), std::process::id())
}

impl Default for AiChat {
    fn default() -> Self {
        let history = std::fs::read_to_string(history_path()).map(|t| history::decode(&t)).unwrap_or_default();
        Self {
            messages: RefCell::default(),
            running: Cell::default(),
            config: RefCell::default(),
            known: RefCell::default(),
            task: RefCell::default(),
            generation: Cell::default(),
            listeners: RefCell::default(),
            conversation_id: RefCell::new(new_id()),
            created_at_ms: Cell::new(now_ms()),
            history: RefCell::new(history),
        }
    }
}

impl AiChat {
    /// 今の会話を履歴に残す（自分の発言が無ければ残さない）。
    fn archive(&self) {
        let saved: Vec<SavedMessage> = self.messages.borrow().iter().map(saved_of).collect();
        if !saved.iter().any(|m| m.kind == history::USER) {
            return;
        }
        let conversation = Conversation {
            id: self.conversation_id.borrow().clone(),
            title: history::title(&saved),
            created_at_ms: self.created_at_ms.get(),
            updated_at_ms: now_ms(),
            messages: saved,
        };
        let list = history::upsert(&self.history.borrow(), conversation);
        self.write_history(list);
    }

    fn write_history(&self, list: Vec<Conversation>) {
        let path = history_path();
        let written = if list.is_empty() {
            std::fs::remove_file(&path).or_else(|e| if e.kind() == std::io::ErrorKind::NotFound { Ok(()) } else { Err(e) })
        } else {
            std::fs::create_dir_all(path.parent().unwrap())
                .and_then(|_| std::fs::write(path.with_extension("json.tmp"), history::encode(&list)))
                .and_then(|_| std::fs::rename(path.with_extension("json.tmp"), &path))
        };
        if let Err(e) = written {
            eprintln!("ai history save failed: {}", e.kind());
        }
        *self.history.borrow_mut() = list;
    }

    /// 履歴の会話を開いて続ける。
    pub fn open(&self, id: &str) {
        let Some(conversation) = self.history.borrow().iter().find(|c| c.id == id).cloned() else { return };
        self.stop();
        self.archive();
        let messages: Vec<AiMessage> = conversation.messages.iter().filter_map(message_of).collect();
        for message in &messages {
            if let AiMessage::Songs(songs) = message {
                songs.iter().for_each(|s| self.remember(s));
            }
        }
        *self.messages.borrow_mut() = messages;
        *self.conversation_id.borrow_mut() = conversation.id;
        self.created_at_ms.set(conversation.created_at_ms);
        self.changed();
    }

    /// 履歴から消す。開いている会話なら、新しい会話にする。
    pub fn delete(&self, id: &str) {
        if *self.conversation_id.borrow() == id {
            self.stop();
            self.reset();
        }
        let list = history::remove(&self.history.borrow(), id);
        self.write_history(list);
        self.changed();
    }

    /// ログアウトのとき: 今の会話も履歴も消す。
    pub fn wipe(&self) {
        self.stop();
        self.write_history(Vec::new());
        self.reset();
    }

    fn reset(&self) {
        self.messages.borrow_mut().clear();
        self.known.borrow_mut().clear();
        *self.conversation_id.borrow_mut() = new_id();
        self.created_at_ms.set(now_ms());
        self.changed();
    }

    pub fn on_change(&self, listener: impl Fn() + 'static) {
        self.listeners.borrow_mut().push(Box::new(listener));
    }

    fn changed(&self) {
        for listener in self.listeners.borrow().iter() {
            listener();
        }
    }

    /// 今開いている会話の id（これまでの会話の一覧で、開いている会話を塗る）。
    pub fn conversation_id(&self) -> String {
        self.conversation_id.borrow().clone()
    }

    pub fn configured(&self) -> Option<String> {
        self.config.borrow().as_ref().map(|c| c.name.clone().unwrap_or_else(|| c.url.clone()))
    }

    /// キーリングからつなぎ先を読む（起動のとき）。
    pub async fn load(&self) {
        // 確かめ用: WAVE_DEMO_AI_PAIR の接続の文字列でつなぐ（キーリングには入れない）
        let demo = std::env::var("WAVE_DEMO_AI_PAIR").ok().and_then(|t| BridgeConfig::pair(&t));
        let config = match demo {
            Some(config) => Some(config),
            None => keyring::load_ai().await.and_then(|t| BridgeConfig::deserialize(&t)),
        };
        *self.config.borrow_mut() = config;
        self.changed();
    }

    /// 接続の文字列（waveai://pair?u=&t=&n=）を読んで保存する。形が違えば false。
    pub async fn pair(&self, text: &str) -> bool {
        let Some(config) = BridgeConfig::pair(text) else { return false };
        if !keyring::save_ai(config.serialize()).await {
            return false;
        }
        *self.config.borrow_mut() = Some(config);
        self.changed();
        true
    }

    pub async fn unpair(&self) {
        self.stop();
        keyring::delete_ai().await;
        *self.config.borrow_mut() = None;
        self.changed();
    }

    pub fn stop(&self) {
        self.generation.set(self.generation.get() + 1);
        if let Some(task) = self.task.borrow_mut().take() {
            task.abort();
        }
        self.running.set(false);
        self.changed();
    }

    /// 新しい会話にする（今の会話は履歴に残す）。
    pub fn clear(&self) {
        self.stop();
        self.archive();
        self.reset();
    }

    fn push(&self, message: AiMessage) {
        self.messages.borrow_mut().push(message);
        self.changed();
    }

    fn remember(&self, song: &Song) {
        self.known.borrow_mut().insert(song.video_id.clone(), song.clone());
    }

    /// 頼みを送る。答えは messages に流れてくる。
    pub fn send(self: &Rc<Self>, app: &Rc<App>, task: &str) {
        let task = task.trim().to_string();
        if task.is_empty() || self.running.get() {
            return;
        }
        let history: Vec<(String, String)> = self
            .messages
            .borrow()
            .iter()
            .filter_map(|m| match m {
                AiMessage::User(t) => Some(("user".to_string(), t.clone())),
                AiMessage::Assistant(t, _) => Some(("assistant".to_string(), t.clone())),
                _ => None,
            })
            .collect();
        self.push(AiMessage::User(task.clone()));
        self.archive();
        let Some(config) = self.config.borrow().clone() else {
            self.push(AiMessage::Failure("Claude につないでいません。設定の「Claude」でつないでください。".into()));
            return;
        };
        self.running.set(true);
        self.changed();
        let generation = self.generation.get();
        let (this, app) = (self.clone(), app.clone());
        glib::spawn_future_local(async move {
            this.run(&app, Bridge::new(config), task, history, generation).await;
            // 止めたあとに別の会話へ移っていれば、そちらには触れない
            if this.generation.get() == generation {
                this.running.set(false);
                this.archive();
                this.changed();
            }
        });
    }

    async fn run(self: &Rc<Self>, app: &Rc<App>, bridge: Bridge, task: String, history: Vec<(String, String)>, generation: u64) {
        let song = app.playback.current();
        let mut lyrics_text = None;
        let mut synced_video = None;
        if let Some(song) = &song {
            self.remember(song);
            if let Some(lyrics) = lyrics_of(app, song).await {
                // 時刻付きなら行ごとに [mm:ss] を付けて送る（Claude が歌詞を引くときに時刻を添える）
                if matches!(lyrics, wave_core::lyrics::SongLyrics::Synced { .. }) {
                    synced_video = Some(song.video_id.clone());
                }
                lyrics_text = Some(text::lyrics_text(&lyrics));
            }
        }
        let body = AgentSpec::bundled().body(&task, &history, song.as_ref(), lyrics_text.as_deref());
        let (tx, mut rx) = mpsc::unbounded_channel();
        let runner = bridge.clone();
        *self.task.borrow_mut() = Some(runtime::spawn(async move { runner.run(body, tx).await }));
        let mut session = String::new();
        let mut appending = false;
        while let Some(event) = rx.recv().await {
            if self.generation.get() != generation {
                return;
            }
            match event {
                BridgeEvent::Session(s) => session = s,
                BridgeEvent::Delta(delta) => {
                    if delta.trim().is_empty() && !appending {
                        continue;
                    }
                    let mut messages = self.messages.borrow_mut();
                    match messages.last_mut() {
                        Some(AiMessage::Assistant(text, _)) if appending => text.push_str(&delta),
                        _ => messages.push(AiMessage::Assistant(delta.trim_start().to_string(), synced_video.clone())),
                    }
                    drop(messages);
                    appending = delta != "\n\n";
                    self.changed();
                }
                BridgeEvent::ToolCall { id, name, arguments } => {
                    appending = false;
                    let (result, shown) = self.call_tool(app, &name, &arguments).await;
                    if self.generation.get() != generation {
                        return;
                    }
                    if let Some(shown) = shown {
                        self.push(shown);
                    }
                    let (bridge, session) = (bridge.clone(), session.clone());
                    runtime::run(async move { bridge.post_result(&session, &id, result).await }).await;
                }
                BridgeEvent::Error(e) => self.push(AiMessage::Failure(format!("Claude が失敗しました: {e}"))),
                BridgeEvent::Unauthorized => self.push(AiMessage::Failure("Claude の中継にトークンを断られました。つなぎ直してください。".into())),
                BridgeEvent::Unreachable => {
                    self.push(AiMessage::Failure("Claude の中継に届きませんでした。中継を動かしている PC が起きているか確かめてください。".into()))
                }
                BridgeEvent::Done => break,
            }
        }
    }

    fn songs_of(&self, args: &Value) -> (Vec<Song>, Vec<String>) {
        let ids: Vec<String> = args.get("video_ids").and_then(Value::as_array).map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect()).unwrap_or_default();
        let known = self.known.borrow();
        (ids.iter().filter_map(|id| known.get(id).cloned()).collect(), ids.into_iter().filter(|id| !known.contains_key(id)).collect())
    }

    /// 道具を手元で行う（ai/agent.json で定義した道具）。
    async fn call_tool(self: &Rc<Self>, app: &Rc<App>, name: &str, args: &Value) -> (Result<Value, String>, Option<AiMessage>) {
        let unknown = |missing: Vec<String>| (Err(format!("unknown video_ids (use search_songs first): {}", missing.join(","))), None);
        match name {
            "now_playing" => {
                let Some(song) = app.playback.current() else { return (Ok(json!("nothing is playing")), None) };
                self.remember(&song);
                let mut out = json!({ "song": song_json(&song) });
                if let Some(lyrics) = lyrics_of(app, &song).await {
                    out["lyrics"] = json!(text::lyrics_text(&lyrics));
                }
                (Ok(out), None)
            }
            "get_queue" => {
                let (songs, index) = app.playback.queue();
                songs.iter().for_each(|s| self.remember(s));
                let from = index.unwrap_or(0).saturating_sub(20);
                (Ok(json!({ "current_index": index, "songs": songs.iter().skip(from).take(100).map(song_json).collect::<Vec<_>>() })), None)
            }
            "search_songs" => {
                let query = args.get("query").and_then(Value::as_str).unwrap_or("").to_string();
                let limit = args.get("limit").and_then(Value::as_u64).unwrap_or(5).clamp(1, 20) as usize;
                let (repo, q) = (app.repo.clone(), query.clone());
                match runtime::run(async move { repo.search_songs(&q, None).await }).await {
                    Ok(page) => {
                        let songs: Vec<Song> = page.songs.into_iter().take(limit).collect();
                        songs.iter().for_each(|s| self.remember(s));
                        (Ok(json!(songs.iter().map(song_json).collect::<Vec<_>>())), Some(AiMessage::Action(format!("「{query}」を探しました"))))
                    }
                    Err(_) => (Err("search failed".into()), None),
                }
            }
            "liked_songs" => {
                let limit = args.get("limit").and_then(Value::as_u64).unwrap_or(50).clamp(1, 200) as usize;
                let repo = app.repo.clone();
                let songs = runtime::run(async move {
                    let mut songs = Vec::new();
                    let mut token: Option<String> = None;
                    while songs.len() < limit {
                        let Ok(page) = repo.playlist(LIKED_SONGS, token.as_deref()).await else { break };
                        songs.extend(page.songs);
                        match page.continuation {
                            Some(next) if Some(&next) != token.as_ref() => token = Some(next),
                            _ => break,
                        }
                    }
                    songs.truncate(limit);
                    songs
                })
                .await;
                songs.iter().for_each(|s| self.remember(s));
                (Ok(json!(songs.iter().map(song_json).collect::<Vec<_>>())), Some(AiMessage::Action("高く評価した曲を見ました".into())))
            }
            "show_songs" => {
                let (songs, missing) = self.songs_of(args);
                if songs.is_empty() {
                    return unknown(missing);
                }
                (Ok(json!(format!("shown {}", songs.len()))), Some(AiMessage::Songs(songs)))
            }
            "play_songs" => {
                let (songs, missing) = self.songs_of(args);
                if songs.is_empty() {
                    return unknown(missing);
                }
                let count = songs.len();
                app.playback.play_list(songs, 0);
                (Ok(json!(format!("playing {count} songs"))), Some(AiMessage::Action(format!("{count} 曲を再生しました"))))
            }
            "add_to_queue" => {
                let (songs, missing) = self.songs_of(args);
                if songs.is_empty() {
                    return unknown(missing);
                }
                let count = songs.len();
                if args.get("next").and_then(Value::as_bool) == Some(true) {
                    // 次に流すときは、頼まれた順のまま今の曲の後ろに並ぶよう逆から入れる
                    songs.into_iter().rev().for_each(|s| app.playback.play_next(s));
                } else {
                    songs.into_iter().for_each(|s| app.playback.add_to_queue(s));
                }
                (Ok(json!(format!("queued {count} songs"))), Some(AiMessage::Action(format!("{count} 曲をキューに足しました"))))
            }
            "list_playlists" => {
                let list: Vec<Value> = app.editable_playlists.borrow().iter().map(|p| json!({ "playlist_id": p.playlist_id, "title": p.title })).collect();
                (Ok(json!(list)), None)
            }
            "create_playlist" => {
                let title = args.get("title").and_then(Value::as_str).unwrap_or("").to_string();
                if title.is_empty() {
                    return (Err("title is empty".into()), None);
                }
                let (songs, _) = self.songs_of(args);
                let (repo, t) = (app.repo.clone(), title.clone());
                let created = runtime::run(async move {
                    let id = repo.create_playlist(&t).await.ok()?;
                    let mut added = 0;
                    for (i, song) in songs.iter().enumerate() {
                        // 作ったばかりで空なので、最初の1曲は重複を確かめない
                        if repo.add_to_playlist(&id, &song.video_id, i == 0).await == AddToPlaylistResult::Added {
                            added += 1;
                        }
                    }
                    Some((id, added))
                })
                .await;
                match created {
                    Some((id, added)) => {
                        app.editable_playlists.borrow_mut().insert(
                            0,
                            wave_core::music::models::PlaylistSummary { playlist_id: id.clone(), title: title.clone(), subtitle: Some("非公開".into()), thumbnail_url: None, editable: true },
                        );
                        (Ok(json!({ "playlist_id": id, "added": added })), Some(AiMessage::Action(format!("「{title}」を作って {added} 曲入れました"))))
                    }
                    None => (Err("create failed".into()), None),
                }
            }
            "add_to_playlist" => {
                let id = args.get("playlist_id").and_then(Value::as_str).unwrap_or("").to_string();
                let Some(title) = app.editable_playlists.borrow().iter().find(|p| p.playlist_id == id).map(|p| p.title.clone()) else {
                    return (Err("unknown playlist_id".into()), None);
                };
                let (songs, missing) = self.songs_of(args);
                if songs.is_empty() {
                    return unknown(missing);
                }
                let repo = app.repo.clone();
                let (added, already) = runtime::run(async move {
                    let (mut added, mut already) = (0, 0);
                    for song in &songs {
                        match repo.add_to_playlist(&id, &song.video_id, false).await {
                            AddToPlaylistResult::Added => added += 1,
                            AddToPlaylistResult::AlreadyInPlaylist => already += 1,
                            _ => {}
                        }
                    }
                    (added, already)
                })
                .await;
                (Ok(json!({ "added": added, "already_in_playlist": already })), Some(AiMessage::Action(format!("「{title}」に {added} 曲足しました"))))
            }
            _ => (Err(format!("unknown tool: {name}")), None),
        }
    }
}

fn saved_of(message: &AiMessage) -> SavedMessage {
    let plain = |kind: &str, text: &str| SavedMessage { kind: kind.into(), text: text.into(), video_id: None, songs: None };
    match message {
        AiMessage::User(t) => plain(history::USER, t),
        AiMessage::Assistant(t, video) => SavedMessage { video_id: video.clone(), ..plain(history::ASSISTANT, t) },
        AiMessage::Action(t) => plain(history::ACTION, t),
        AiMessage::Songs(songs) => SavedMessage { songs: Some(songs.clone()), ..plain(history::SONGS, "") },
        AiMessage::Failure(t) => plain(history::FAILURE, t),
    }
}

fn message_of(saved: &SavedMessage) -> Option<AiMessage> {
    Some(match saved.kind.as_str() {
        history::USER => AiMessage::User(saved.text.clone()),
        history::ASSISTANT => AiMessage::Assistant(saved.text.clone(), saved.video_id.clone()),
        history::ACTION => AiMessage::Action(saved.text.clone()),
        history::SONGS => AiMessage::Songs(saved.songs.clone().filter(|s| !s.is_empty())?),
        history::FAILURE => AiMessage::Failure(saved.text.clone()),
        _ => return None,
    })
}

async fn lyrics_of(app: &Rc<App>, song: &Song) -> Option<wave_core::lyrics::SongLyrics> {
    let (service, song) = (app.lyrics.clone(), song.clone());
    match runtime::run(async move { service.lyrics(&song).await }).await {
        LyricsResult::Found(lyrics) => Some(lyrics),
        _ => None,
    }
}

fn song_json(s: &Song) -> Value {
    let mut out = json!({ "video_id": s.video_id, "title": s.title, "artists": s.artists_text() });
    if let Some(album) = &s.album {
        out["album"] = json!(album.title);
    }
    if let Some(d) = s.duration_seconds {
        out["duration_seconds"] = json!(d);
    }
    if let Some(k) = &s.kind {
        out["kind"] = json!(k);
    }
    out
}
