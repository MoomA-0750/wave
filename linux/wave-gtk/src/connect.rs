//! 端末の連携（Connect v2）の Linux 側の調停役（Windows の ConnectService と同じ役割）。
//! ConnectClient は主スレッドに置き、中継 v2・再生・画面をつなぐ。中継との通信は tokio の上で動かす。
//! ログは種類と件数だけ（中継の場所・端末 ID・本文・アカウントの識別子は出さない）。

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::rc::{Rc, Weak};
use std::sync::Arc;
use std::time::{Duration, Instant};

use gtk::glib;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use wave_core::connect::chain::{self, ChainVerification, DeviceIdentity, DeviceSigner};
use wave_core::connect::client::{ClientEvent, ClientPersisted, Clock, ConnectClient, Effects, JoinInfo, LocalCursor};
use wave_core::connect::crypto::b64;
use wave_core::connect::device::SoftwareDevice;
use wave_core::connect::models::{ControlAction, CursorBody, MemberKeys, Role, account_tag, connect_json};
use wave_core::connect::pairing;
use wave_core::connect::relay::RelayMessage;
use wave_core::connect::snapshot::{self, HandoffDecodeResult, HandoffItem, HandoffOrigin, HandoffRef, HandoffRepeat, HandoffSnapshot, HandoffSource};
use wave_core::connect::socket::{RelayEndpoint, RelaySocket, RelayStatus, SocketEvent};
use wave_core::music::models::{AlbumRef, ArtistRef, Song};
use wave_core::music::repository::MusicRepository;

use crate::playback::{Origin, Playback, QueueItem, QueueSource, Repeat, Transport, UserAction};
use crate::{keyring, runtime};

const TICK_MS: u64 = 1_000;
const CURSOR_DEBOUNCE_MS: u64 = 1_000;
const SETTLE_WAIT_MS: u64 = 10_000;
const MAX_LABEL_CHARS: usize = 64;
/// キューの doc の handoffId（Android の V2_SNAPSHOT_ID と同じ。中身の同じキューは同じ文字列になる）。
const SNAPSHOT_ID: &str = "00000000-0000-4000-8000-000000000000";

/// 画面に出す接続の様子。
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ConnectInfo {
    /// 中継の場所が設定されていて、端末の鍵が読めた。
    pub configured: bool,
    /// 端末の鍵か保存が読めない。
    pub unreadable: bool,
    pub in_household: bool,
    pub is_writer: bool,
    pub store_reset_pending: bool,
    pub relay: Option<RelayStatus>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MemberView {
    pub device_id: String,
    pub label: String,
    pub is_self: bool,
    pub online: bool,
    pub active: bool,
    /// 端末の種類（"pc"・"phone"・"api"。知らなければ None）。
    pub kind: Option<String>,
}

/// ほかの端末で鳴っている再生（下の帯に映す）。
#[derive(Clone, Debug, PartialEq)]
pub struct Mirror {
    pub device_id: String,
    pub label: String,
    pub song: Song,
    pub playing: bool,
    pub position_ms: i64,
    pub volume_percent: Option<i32>,
    /// 次に流れる曲（右の欄の「次の曲」）。
    pub next: Option<Song>,
    /// その端末のシャッフル（None は、シャッフル・繰り返しの命令を受けない前の版の端末）。
    pub shuffle: Option<bool>,
    pub repeat: Repeat,
}

#[derive(Clone, Debug, PartialEq)]
pub enum PairingView {
    Idle,
    Working(String),
    /// 招く側: 加わる端末に見せる文字列（wave-pair:…）と、6桁を入れる欄（届いてから）。
    ShowingOffer(String),
    EnterCode(Option<String>),
    /// 加わる側: 相手に入れてもらう6桁。
    ShowCode { code: String, peer: String },
    Done(String),
    Failed(String),
}

/// 家の端末の表示名と種類（知らせてきたもの。中継につながっているときにしか届かないのでファイルに覚える）。
#[derive(Default, Serialize, Deserialize)]
struct Names {
    labels: HashMap<String, String>,
    kinds: HashMap<String, String>,
}

#[derive(Clone)]
struct TagState {
    household_id: String,
    tag: String,
    generation: u64,
}

/// ConnectClient の外の作用と共有する値（ConnectClient の中から呼ばれるので、ConnectClient を借りない）。
struct Shared {
    playback: Rc<Playback>,
    repo: MusicRepository,
    tag: RefCell<Option<TagState>>,
    events: RefCell<Vec<ClientEvent>>,
    /// ほかの端末に「ここで再生」を頼まれた（止められた知らせを抑えるのに使う）。
    transfer_requested_at: Cell<Option<Instant>>,
    started: Instant,
}

struct GtkEffects {
    shared: Rc<Shared>,
}

impl Effects for GtkEffects {
    fn pause(&self) -> Option<LocalCursor> {
        self.shared.playback.set_playing(false);
        local_cursor(&self.shared.playback, now_ms(&self.shared))
    }

    fn adopt(&self, snapshot_text: &str, cursor: &CursorBody, position_ms: i64) -> bool {
        let Some(state) = self.shared.tag.borrow().clone() else { return false };
        if cursor.account_tag != state.tag || self.shared.repo.client().generation() != state.generation {
            return false;
        }
        let Some((items, index, source)) = decode_queue(snapshot_text, &cursor.current_item_id) else { return false };
        self.shared.playback.adopt(items, index, position_ms.max(0) as u64, true, source);
        true
    }

    fn event(&self, event: ClientEvent) {
        eprintln!("connect-v2 event={}", event_name(&event));
        self.shared.events.borrow_mut().push(event);
    }

    fn persist(&self, state: &ClientPersisted) {
        write_atomically(&data_path("connect-v2.json"), &connect_json::encode(state));
    }

    fn account_tag(&self) -> Option<String> {
        let state = self.shared.tag.borrow();
        let state = state.as_ref()?;
        (state.generation == self.shared.repo.client().generation()).then(|| state.tag.clone())
    }

    fn control(&self, action: ControlAction, position_ms: Option<i64>) -> bool {
        eprintln!("connect-v2 control received action={action:?}");
        let playback = &self.shared.playback;
        match action {
            ControlAction::Transfer => {
                self.shared.transfer_requested_at.set(Some(Instant::now()));
                true
            }
            ControlAction::Play => {
                playback.set_playing(true);
                true
            }
            ControlAction::Pause => {
                playback.set_playing(false);
                true
            }
            ControlAction::Next => {
                playback.advance();
                true
            }
            ControlAction::Previous => {
                playback.back();
                true
            }
            ControlAction::Seek => {
                let Some(position) = position_ms else { return false };
                playback.player().seek_ms(position.max(0) as u64);
                true
            }
            _ => false,
        }
    }

    fn queue_add(&self, items: &[HandoffItem], next: bool, after_item_id: Option<&str>) -> bool {
        let entries = items.iter().map(entry_of).collect();
        let added = self.shared.playback.insert_items(entries, next, after_item_id);
        eprintln!("connect-v2 queue add received items={} added={added}", items.len());
        added
    }

    fn queue_move(&self, item_id: &str, after_item_id: Option<&str>) -> bool {
        self.shared.playback.move_item(item_id, after_item_id)
    }

    fn queue_remove(&self, item_ids: &[String]) -> bool {
        self.shared.playback.remove_items(item_ids)
    }

    fn queue_clear_flow(&self, current_item_id: &str) -> bool {
        self.shared.playback.clear_flow(current_item_id)
    }

    fn shuffle(&self, on: bool) -> bool {
        eprintln!("connect-v2 shuffle received on={on}");
        self.shared.playback.set_shuffle(on);
        true
    }

    fn repeat(&self, mode: HandoffRepeat) -> bool {
        eprintln!("connect-v2 repeat received mode={mode:?}");
        self.shared.playback.set_repeat(repeat_of(mode));
        true
    }

    fn volume(&self, volume_percent: i32) -> bool {
        self.shared.playback.player().set_volume(f64::from(volume_percent.clamp(0, 100)) / 100.0);
        true
    }
}

type Notices = RefCell<Vec<Box<dyn Fn(&str)>>>;

pub struct Connect {
    shared: Rc<Shared>,
    device: Option<Arc<SoftwareDevice>>,
    socket: Option<RelaySocket>,
    client: RefCell<Option<ConnectClient>>,
    unreadable: Cell<bool>,
    status: Cell<RelayStatus>,
    names: RefCell<Names>,
    listeners: RefCell<Vec<Box<dyn Fn()>>>,
    notices: Notices,
    pairing: RefCell<PairingView>,
    /// 今の加入の手順の番号（取り下げたら古い手順は止まる）。
    pairing_epoch: Cell<u64>,
    pair_hellos: RefCell<Option<mpsc::UnboundedSender<(String, serde_json::Value)>>>,
    codes: RefCell<Option<mpsc::UnboundedSender<String>>>,
    chain_reply: RefCell<Option<tokio::sync::oneshot::Sender<RelayMessage>>>,
    join_waiter: RefCell<Option<tokio::sync::oneshot::Sender<ClientEvent>>>,
    pending_on_b: RefCell<Option<pairing::Established>>,
    tag_computing: Cell<bool>,
    seen_generation: Cell<Option<u64>>,
    last_significant: RefCell<Option<(String, bool)>>,
    cursor_timer: RefCell<Option<glib::SourceId>>,
    play_intent: Cell<u64>,
    profile_sent_for: Cell<Option<usize>>,
    profile_seen_online: RefCell<HashSet<String>>,
    /// 映している再生のキュー（doc のハッシュと、読んだ曲）。
    mirrored: RefCell<Option<(String, Vec<QueueItem>)>>,
    last_info: RefCell<ConnectInfo>,
    last_members: RefCell<Vec<MemberView>>,
}

impl Connect {
    /// relay_url が無ければ、連携を使わない（画面には「設定されていません」と出す）。
    pub async fn start(repo: MusicRepository, playback: Rc<Playback>, relay_url: Option<String>) -> Rc<Self> {
        let shared = Rc::new(Shared {
            playback: playback.clone(),
            repo,
            tag: RefCell::default(),
            events: RefCell::default(),
            transfer_requested_at: Cell::default(),
            started: Instant::now(),
        });
        let endpoint = relay_url.as_deref().and_then(RelayEndpoint::parse);
        let (device, unreadable) = if endpoint.is_some() { load_or_create_device().await } else { (None, false) };
        let (events_tx, mut events_rx) = mpsc::unbounded_channel();
        let socket = match (&endpoint, &device) {
            (Some(endpoint), Some(device)) => {
                let signer = device.clone();
                let sign: wave_core::connect::socket::Signer = Arc::new(move |label, message| signer.sign(label, message));
                Some(RelaySocket::new(endpoint.clone(), device.identity_pub().to_vec(), sign, events_tx))
            }
            _ => None,
        };
        let connect = Rc::new(Self {
            shared,
            device,
            socket,
            client: RefCell::default(),
            unreadable: Cell::new(unreadable),
            status: Cell::new(RelayStatus::Disconnected),
            names: RefCell::new(read_json(&data_path("connect-v2-names.json")).unwrap_or_default()),
            listeners: RefCell::default(),
            notices: RefCell::default(),
            pairing: RefCell::new(PairingView::Idle),
            pairing_epoch: Cell::new(0),
            pair_hellos: RefCell::default(),
            codes: RefCell::default(),
            chain_reply: RefCell::default(),
            join_waiter: RefCell::default(),
            pending_on_b: RefCell::default(),
            tag_computing: Cell::new(false),
            seen_generation: Cell::new(None),
            last_significant: RefCell::default(),
            cursor_timer: RefCell::default(),
            play_intent: Cell::new(0),
            profile_sent_for: Cell::new(None),
            profile_seen_online: RefCell::default(),
            mirrored: RefCell::default(),
            last_info: RefCell::default(),
            last_members: RefCell::default(),
        });
        if connect.socket.is_none() {
            return connect;
        }
        connect.load_client();
        // 中継からの知らせを主スレッドで受ける
        let weak = Rc::downgrade(&connect);
        glib::spawn_future_local(async move {
            while let Some(event) = events_rx.recv().await {
                let Some(connect) = weak.upgrade() else { break };
                connect.on_socket(event);
            }
        });
        connect.hook_playback(&playback);
        // 時間の経過（transfer の期限・アカウントタグの計算・名前の知らせ）
        let weak = Rc::downgrade(&connect);
        glib::timeout_add_local(Duration::from_millis(TICK_MS), move || {
            let Some(connect) = weak.upgrade() else { return glib::ControlFlow::Break };
            connect.on_tick();
            glib::ControlFlow::Continue
        });
        // PC は画面がある間ずっと中継につなぐ（家に入っていなければ、中継は認証で断る）
        connect.with_socket(RelaySocket::start);
        connect
    }

    pub fn on_change(&self, listener: impl Fn() + 'static) {
        self.listeners.borrow_mut().push(Box::new(listener));
    }

    /// 知らせ（トースト）。
    pub fn on_notice(&self, listener: impl Fn(&str) + 'static) {
        self.notices.borrow_mut().push(Box::new(listener));
    }

    fn changed(&self) {
        for listener in self.listeners.borrow().iter() {
            listener();
        }
    }

    fn notify(&self, text: &str) {
        for listener in self.notices.borrow().iter() {
            listener(text);
        }
    }

    fn with_socket(&self, f: impl FnOnce(&RelaySocket)) {
        if let Some(socket) = &self.socket {
            let _guard = runtime::enter();
            f(socket);
        }
    }

    fn send(&self, message: RelayMessage) -> bool {
        self.socket.as_ref().is_some_and(|s| s.send(message.encode()))
    }

    /// ConnectClient を借りて動かし、出た知らせを処理する。
    fn with_client<R>(self: &Rc<Self>, f: impl FnOnce(&mut ConnectClient) -> R) -> Option<R> {
        let result = {
            let mut client = self.client.borrow_mut();
            client.as_mut().map(f)
        };
        self.drain_events();
        result
    }

    fn load_client(self: &Rc<Self>) {
        let Some(device) = self.device.clone() else { return };
        let path = data_path("connect-v2.json");
        let initial = match std::fs::read_to_string(&path) {
            Ok(text) => match connect_json::decode::<ClientPersisted>(&text) {
                Some(state) => state,
                None => {
                    self.unreadable.set(true);
                    return;
                }
            },
            Err(_) => ClientPersisted::default(),
        };
        let socket = self.socket.clone();
        let send: Box<dyn Fn(RelayMessage) -> bool> = Box::new(move |message| socket.as_ref().is_some_and(|s| s.send(message.encode())));
        let started = self.shared.started;
        let clock = Clock {
            now_ms: Box::new(move || started.elapsed().as_millis() as i64),
            epoch_ms: Box::new(epoch_ms),
            new_id: Box::new(crate::playback::new_item_id),
        };
        let effects = Box::new(GtkEffects { shared: self.shared.clone() });
        *self.client.borrow_mut() = Some(ConnectClient::new(device, Role::Player, send, effects, clock, initial));
        self.seen_generation.set(Some(self.shared.repo.client().generation()));
    }

    // --- 画面の値 ---

    /// 画面から読む値は、ConnectClient が動いている最中（再生の取り込みが画面の描き直しを呼んだとき）には前の値を返す。
    pub fn info(&self) -> ConnectInfo {
        let Ok(client) = self.client.try_borrow() else { return self.last_info.borrow().clone() };
        let info = ConnectInfo {
            configured: self.socket.is_some(),
            unreadable: self.unreadable.get(),
            in_household: client.as_ref().is_some_and(|c| c.household_id().is_some()),
            is_writer: client.as_ref().is_some_and(ConnectClient::is_writer),
            store_reset_pending: client.as_ref().is_some_and(ConnectClient::store_reset_pending),
            relay: self.socket.as_ref().map(|_| self.status.get()),
        };
        *self.last_info.borrow_mut() = info.clone();
        info
    }

    pub fn members(&self) -> Vec<MemberView> {
        let Ok(client) = self.client.try_borrow() else { return self.last_members.borrow().clone() };
        let Some(client) = client.as_ref() else { return Vec::new() };
        let me = self.device.as_ref().map(|d| d.device_id().to_string()).unwrap_or_default();
        let names = self.names.borrow();
        let mut members: Vec<MemberView> = client
            .members()
            .into_iter()
            .map(|m| MemberView {
                label: self.label_of(&m.device_id, &me, &names),
                is_self: m.device_id == me,
                online: client.online().contains(&m.device_id),
                active: client.active_device_id() == Some(m.device_id.as_str()),
                kind: names.kinds.get(&m.device_id).cloned(),
                device_id: m.device_id,
            })
            .collect();
        members.sort_by_key(|m| !m.is_self);
        *self.last_members.borrow_mut() = members.clone();
        members
    }

    fn label_of(&self, device_id: &str, me: &str, names: &Names) -> String {
        if device_id == me {
            "この PC".to_string()
        } else if let Some(name) = names.labels.get(device_id) {
            name.clone()
        } else {
            format!("端末 {}", device_id.chars().take(6).collect::<String>())
        }
    }

    pub fn pairing(&self) -> PairingView {
        self.pairing.borrow().clone()
    }

    fn set_pairing(&self, view: PairingView) {
        *self.pairing.borrow_mut() = view;
        self.changed();
    }

    /// ほかの端末で鳴っている再生（同じアカウントで、その端末が中継につながっているときだけ）。
    pub fn mirror(&self) -> Option<Mirror> {
        let client = self.client.try_borrow().ok()?;
        let client = client.as_ref()?;
        if client.is_writer() {
            return None;
        }
        let tag = self.shared.tag.borrow().as_ref().map(|t| t.tag.clone())?;
        let now = client.remote_now_position()?;
        if now.cursor.account_tag != tag || !client.online().contains(&now.writer) {
            return None;
        }
        // キューは引き取りの後も前の端末が書いた doc を指すので、書き手ではなくカーソルの queueRef で照らす
        if client.latest_queue_hash() != Some(now.cursor.queue_ref.as_str()) {
            return None;
        }
        let (_, queue) = client.latest_queue_body()?;
        let mut mirrored = self.mirrored.borrow_mut();
        if mirrored.as_ref().is_none_or(|(hash, _)| *hash != now.cursor.queue_ref) {
            let items = decode_snapshot(&queue.snapshot).map(|s| s.items.iter().map(entry_of).collect()).unwrap_or_default();
            *mirrored = Some((now.cursor.queue_ref.clone(), items));
        }
        let (_, items) = mirrored.as_ref()?;
        let index = items.iter().position(|i| i.item_id == now.cursor.current_item_id)?;
        let me = self.device.as_ref().map(|d| d.device_id().to_string()).unwrap_or_default();
        Some(Mirror {
            label: self.label_of(&now.writer, &me, &self.names.borrow()),
            device_id: now.writer,
            song: items[index].song.clone(),
            playing: now.cursor.playing,
            position_ms: now.position_ms,
            volume_percent: now.cursor.volume_percent,
            next: items.get(index + 1).map(|i| i.song.clone()),
            shuffle: now.cursor.shuffle,
            repeat: repeat_of(now.cursor.repeat),
        })
    }

    // --- 中継からの知らせ ---

    fn on_socket(self: &Rc<Self>, event: SocketEvent) {
        match event {
            SocketEvent::Status(status) => {
                self.status.set(status);
                if status == RelayStatus::Connected {
                    self.profile_sent_for.set(None);
                }
                self.changed();
            }
            SocketEvent::Lost => {
                self.with_client(ConnectClient::on_disconnected);
            }
            SocketEvent::Text(text) => {
                let Some(message) = RelayMessage::decode(&text) else { return };
                match &message {
                    RelayMessage::PairHello { token_hash, hello } => {
                        if let Some(tx) = self.pair_hellos.borrow().as_ref() {
                            let _ = tx.send((token_hash.clone(), hello.clone()));
                        }
                        return;
                    }
                    // 鎖の追記への返事（state_put への error は requestId が付くので除く）
                    RelayMessage::ChainOk { .. } | RelayMessage::ChainConflict { .. } | RelayMessage::Error { request_id: None, .. } => {
                        if let Some(tx) = self.chain_reply.borrow_mut().take() {
                            let _ = tx.send(message.clone());
                        }
                    }
                    _ => {}
                }
                self.with_client(|c| c.on_message(message));
                self.changed();
            }
        }
    }

    fn drain_events(self: &Rc<Self>) {
        let events = std::mem::take(&mut *self.shared.events.borrow_mut());
        if events.is_empty() {
            return;
        }
        for event in events {
            self.on_event(event);
        }
        self.changed();
    }

    fn on_event(self: &Rc<Self>, event: ClientEvent) {
        if let ClientEvent::Profile { device_id, name, kind } = &event {
            let name: String = name.chars().filter(|c| !c.is_control() && !is_bidi_control(*c)).collect::<String>().trim().chars().take(MAX_LABEL_CHARS).collect();
            let mut names = self.names.borrow_mut();
            let mut dirty = false;
            if let Some(kind) = kind.as_deref().filter(|k| matches!(*k, "pc" | "phone" | "api"))
                && names.kinds.get(device_id).map(String::as_str) != Some(kind)
            {
                names.kinds.insert(device_id.clone(), kind.to_string());
                dirty = true;
            }
            if !name.is_empty() && names.labels.get(device_id) != Some(&name) {
                names.labels.insert(device_id.clone(), name);
                dirty = true;
            }
            if dirty {
                write_atomically(&data_path("connect-v2-names.json"), &serde_json::to_string(&*names).expect("plain data"));
            }
            return;
        }
        if matches!(event, ClientEvent::Joined | ClientEvent::ChainForked | ClientEvent::ChainRolledBack | ClientEvent::ChainInvalid { .. })
            && let Some(tx) = self.join_waiter.borrow_mut().take()
        {
            let _ = tx.send(event.clone());
        }
        let text = match &event {
            ClientEvent::Stopped { by } => {
                let label = self.member_label(by);
                // ほかの端末が「この PC で鳴らして」と頼む流れでは、止められた直後に引き取りを頼まれる。そのときは知らせない
                let stopped_at = Instant::now();
                let weak = Rc::downgrade(self);
                glib::timeout_add_local_once(Duration::from_secs(2), move || {
                    let Some(connect) = weak.upgrade() else { return };
                    if connect.shared.transfer_requested_at.get().is_none_or(|at| at < stopped_at) {
                        connect.notify(&format!("{label}で再生を始めたので、ここでは止めました"));
                    }
                });
                return;
            }
            ClientEvent::TransferCompleted { from } => format!("{}から引き継ぎました", self.member_label(from)),
            ClientEvent::TransferFailed { .. } => "引き継げませんでした".to_string(),
            ClientEvent::StoreReset => "中継の状態が作り直されました。設定の「端末の連携」で確かめてください".to_string(),
            ClientEvent::ChainForked | ClientEvent::ChainRolledBack | ClientEvent::ChainInvalid { .. } => "中継から届いた端末の一覧を確かめられませんでした".to_string(),
            ClientEvent::StateEquivocation => "中継から食い違った状態が届きました".to_string(),
            ClientEvent::ServerError { code } => match code.as_str() {
                REMOTE_PLAYING => "ほかの端末で再生中なので、ここでは鳴らしませんでした".to_string(),
                RELAY_UNREACHABLE => "中継につながらず、ほかの端末の再生を確かめられませんでした".to_string(),
                NOT_MEMBER_PLAY => "中継がこの PC を連携した端末として受け付けませんでした".to_string(),
                ACCOUNT_UNVERIFIED_PLAY => "アカウントを確かめられませんでした".to_string(),
                HOUSEHOLD_CREATE_UNSENT => "中継に送れませんでした（この PC では新しく始められない設定かもしれません）".to_string(),
                _ => return,
            },
            _ => return,
        };
        self.notify(&text);
    }

    fn member_label(&self, device_id: &str) -> String {
        let me = self.device.as_ref().map(|d| d.device_id().to_string()).unwrap_or_default();
        self.label_of(device_id, &me, &self.names.borrow())
    }

    // --- 時間の経過 ---

    fn on_tick(self: &Rc<Self>) {
        self.check_login_generation();
        self.with_client(ConnectClient::tick);
        let household = self.client.borrow().as_ref().and_then(|c| c.household_id().map(String::from));
        if let Some(household) = household {
            self.refresh_account_tag(household);
        }
        self.maybe_send_profile();
        self.changed();
    }

    /// ログインが変わった（ログアウト・別のアカウント）: 書き手なら前のアカウントのタグで止めたカーソルを置いて、手元を捨てる。
    fn check_login_generation(self: &Rc<Self>) {
        let generation = self.shared.repo.client().generation();
        let previous = self.seen_generation.replace(Some(generation));
        let Some(previous) = previous.filter(|p| *p != generation) else { return };
        let final_tag = self.shared.tag.borrow().as_ref().filter(|t| t.generation == previous).map(|t| t.tag.clone());
        *self.shared.tag.borrow_mut() = None;
        self.with_client(|c| c.end_local(final_tag));
        eprintln!("connect-v2 login changed ended local");
    }

    fn refresh_account_tag(self: &Rc<Self>, household: String) {
        let generation = self.shared.repo.client().generation();
        if self.shared.tag.borrow().as_ref().is_some_and(|t| t.household_id == household && t.generation == generation) {
            return;
        }
        if !self.shared.repo.client().is_logged_in() || self.tag_computing.replace(true) {
            return;
        }
        let repo = self.shared.repo.clone();
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let account = runtime::run(async move { repo.verified_account().await }).await;
            let Some(connect) = weak.upgrade() else { return };
            connect.tag_computing.set(false);
            let Some(account) = account else { return };
            connect.check_login_generation();
            if account.generation != connect.shared.repo.client().generation() {
                return;
            }
            if connect.client.borrow().as_ref().and_then(|c| c.household_id().map(String::from)).as_deref() != Some(household.as_str()) {
                return;
            }
            let tag = account_tag::of(&household, &account.id);
            *connect.shared.tag.borrow_mut() = Some(TagState { household_id: household, tag, generation: account.generation });
            let writer = connect.client.borrow().as_ref().is_some_and(ConnectClient::is_writer);
            if !writer {
                connect.load_local_from_player();
            }
            connect.with_client(ConnectClient::on_account_tag_available);
            connect.changed();
        });
    }

    /// 中継につながったとき・家の端末が増えたとき・ほかの端末が新しくつながってきたときに、この PC の名前を家に知らせる。
    fn maybe_send_profile(self: &Rc<Self>) {
        if self.status.get() != RelayStatus::Connected {
            return;
        }
        let (count, online) = {
            let client = self.client.borrow();
            let Some(client) = client.as_ref().filter(|c| c.household_id().is_some()) else { return };
            (client.members().len(), client.online().clone())
        };
        let me = self.device.as_ref().map(|d| d.device_id().to_string()).unwrap_or_default();
        let newcomer = online.iter().any(|id| *id != me && !self.profile_seen_online.borrow().contains(id));
        *self.profile_seen_online.borrow_mut() = online;
        if self.profile_sent_for.get() == Some(count) && !newcomer {
            return;
        }
        if self.with_client(|c| c.send_profile(&device_name(), Some("pc"))) == Some(true) {
            self.profile_sent_for.set(Some(count));
        }
    }

    // --- 再生からの知らせ ---

    fn hook_playback(self: &Rc<Self>, playback: &Rc<Playback>) {
        let weak = Rc::downgrade(self);
        playback.on_change(move || {
            if let Some(connect) = weak.upgrade() {
                connect.on_playback_changed();
            }
        });
        let weak = Rc::downgrade(self);
        playback.on_user(move |action| {
            if let Some(connect) = weak.upgrade() {
                // 再生の中から呼ばれるので、終わってから決める
                let connect = connect.clone();
                glib::idle_add_local_once(move || connect.on_user_action(action));
            }
        });
        // ほかの端末を映している間は、どの入口の再生ボタンも、その端末への命令になる（手元で鳴らすのは「ここで再生」か曲を選んだときだけ）
        let weak: Weak<Self> = Rc::downgrade(self);
        playback.set_interceptor(Some(Box::new(move |transport| {
            let Some(connect) = weak.upgrade() else { return false };
            let Some(mirror) = connect.mirror() else { return false };
            let action = match transport {
                Transport::Toggle if mirror.playing => ControlAction::Pause,
                Transport::Toggle => ControlAction::Play,
                Transport::Next => ControlAction::Next,
                Transport::Previous => ControlAction::Previous,
            };
            let connect2 = connect.clone();
            glib::idle_add_local_once(move || {
                let sent = connect2.with_client(|c| c.send_control(action, None));
                eprintln!("connect-v2 control action={action:?} sent={sent:?}");
            });
            true
        })));
        // 「次に再生」「キューに追加」も、映している端末のキューへ入れる
        let weak: Weak<Self> = Rc::downgrade(self);
        playback.set_queue_interceptor(Some(Box::new(move |song, next| {
            let Some(connect) = weak.upgrade() else { return false };
            if connect.mirror().is_none() {
                return false;
            }
            let item = item_of(&QueueItem::new(song.clone(), Origin::UserAdded));
            let connect2 = connect.clone();
            glib::idle_add_local_once(move || {
                let sent = connect2.with_client(|c| c.send_queue_add(std::slice::from_ref(&item), next));
                eprintln!("connect-v2 queue add next={next} sent={sent:?}");
                if sent == Some(true) {
                    connect2.notify(if next { "再生中の端末で次に再生します" } else { "再生中の端末のキューに追加しました" });
                }
            });
            true
        })));
    }

    /// 再生の位置・曲・再生中かが変わった。曲と再生中かが変わったらすぐ、ほかは1秒に間引いて置く。
    fn on_playback_changed(self: &Rc<Self>) {
        let key = self.shared.playback.current_item().map(|i| (i.item_id, self.shared.playback.player().is_playing()));
        let significant = *self.last_significant.borrow() != key;
        if significant {
            *self.last_significant.borrow_mut() = key;
            let connect = self.clone();
            glib::idle_add_local_once(move || connect.push_local());
            return;
        }
        if self.cursor_timer.borrow().is_some() {
            return;
        }
        let weak = Rc::downgrade(self);
        let id = glib::timeout_add_local_once(Duration::from_millis(CURSOR_DEBOUNCE_MS), move || {
            if let Some(connect) = weak.upgrade() {
                connect.cursor_timer.borrow_mut().take();
                connect.push_local();
            }
        });
        *self.cursor_timer.borrow_mut() = Some(id);
    }

    /// 手元のキューとカーソルを ConnectClient に渡す（書き手なら中継に書く）。
    fn push_local(self: &Rc<Self>) {
        let writer = self.client.borrow().as_ref().is_some_and(ConnectClient::is_writer);
        let snapshot = if writer { self.snapshot() } else { None };
        let cursor = local_cursor(&self.shared.playback, now_ms(&self.shared));
        let tag = GtkEffects { shared: self.shared.clone() }.account_tag();
        self.with_client(|c| {
            if let Some(snapshot) = snapshot {
                c.set_local_queue(snapshot);
            }
            match cursor {
                Some(cursor) => c.set_local_cursor(cursor),
                None => c.end_local(tag),
            }
        });
    }

    fn load_local_from_player(self: &Rc<Self>) {
        let snapshot = self.snapshot();
        let cursor = local_cursor(&self.shared.playback, now_ms(&self.shared));
        self.with_client(|c| {
            if let Some(snapshot) = snapshot {
                c.set_local_queue(snapshot);
            }
            if let Some(cursor) = cursor {
                c.set_local_cursor(cursor);
            }
        });
    }

    fn claim_local(self: &Rc<Self>) {
        self.load_local_from_player();
        let claimed = self.with_client(ConnectClient::user_started_playback);
        eprintln!("connect-v2 user started claimed={claimed:?}");
        self.changed();
    }

    /// 本人が手元で曲を選んだ・再開した・止めた（D-9）。
    fn on_user_action(self: &Rc<Self>, action: UserAction) {
        let intent = self.play_intent.get() + 1;
        self.play_intent.set(intent);
        if action == UserAction::Paused {
            return;
        }
        let (retreated, in_household, settled, remote_playing) = {
            let client = self.client.borrow();
            let Some(c) = client.as_ref() else { return };
            (c.has_retreated(), c.household_id().is_some(), c.state_settled(), c.remote_playing_now())
        };
        let has_tag = GtkEffects { shared: self.shared.clone() }.account_tag().is_some();
        let has_local_queue = self.shared.playback.current_item().is_some();
        let decision = play_policy::on_play(action == UserAction::Selected, retreated, in_household, settled, has_tag, remote_playing, foreground(), has_local_queue);
        if decision == play_policy::Decision::Wait {
            self.shared.playback.set_playing(false);
            self.decide_after_wait(intent);
            return;
        }
        self.apply(decision);
    }

    fn apply(self: &Rc<Self>, decision: play_policy::Decision) {
        use play_policy::Decision as D;
        match decision {
            D::Claim => self.claim_local(),
            D::SyncAndClaim => {
                // 家で最後に鳴った再生（今ほかの端末で鳴っていれば、その位置）に合わせてから、この端末の再生にする
                self.stage_latest(true);
                self.claim_local();
            }
            D::ResumeAndClaim => {
                self.shared.playback.set_playing(true);
                self.claim_local();
            }
            D::TakeOver => {
                self.shared.playback.set_playing(false);
                if self.with_client(ConnectClient::request_transfer) == Some(true) {
                    eprintln!("connect-v2 play button became transfer");
                } else {
                    self.on_event(ClientEvent::ServerError { code: REMOTE_PLAYING.into() });
                }
            }
            D::BlockRemotePlaying => {
                self.shared.playback.set_playing(false);
                self.on_event(ClientEvent::ServerError { code: REMOTE_PLAYING.into() });
            }
            D::NotifyNotMember => self.on_event(ClientEvent::ServerError { code: NOT_MEMBER_PLAY.into() }),
            D::NotifyUnreachable => self.on_event(ClientEvent::ServerError { code: RELAY_UNREACHABLE.into() }),
            D::NotifyUnverified => self.on_event(ClientEvent::ServerError { code: ACCOUNT_UNVERIFIED_PLAY.into() }),
            D::Nothing | D::Wait => {}
        }
    }

    /// 中継の最新の状態とアカウントタグがそろうまで（最大 10 秒）待ってから、D-9 の判断をする。
    fn decide_after_wait(self: &Rc<Self>, intent: u64) {
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let started = Instant::now();
            let outcome = loop {
                let Some(connect) = weak.upgrade() else { return };
                let status = connect.status.get();
                let settled = connect.client.borrow().as_ref().is_some_and(ConnectClient::state_settled);
                let tagged = GtkEffects { shared: connect.shared.clone() }.account_tag().is_some();
                if let Some(outcome) = play_policy::wait_step(status == RelayStatus::NotMember, status == RelayStatus::TailnetUnreachable, settled && tagged) {
                    break outcome;
                }
                if started.elapsed() >= Duration::from_millis(SETTLE_WAIT_MS) {
                    break play_policy::timeout_outcome(settled);
                }
                drop(connect);
                glib::timeout_future(Duration::from_millis(100)).await;
            };
            let Some(connect) = weak.upgrade() else { return };
            let (retreated, remote_playing) = {
                let client = connect.client.borrow();
                let Some(c) = client.as_ref() else { return };
                (c.has_retreated(), c.remote_playing_now())
            };
            let decision = play_policy::after_wait(outcome, connect.play_intent.get() == intent, retreated, remote_playing, foreground());
            eprintln!("connect-v2 play button waited outcome={outcome:?} decision={decision:?}");
            connect.apply(decision);
        });
    }

    /// この端末が鳴らしていないとき、手元のキューと位置を、家で最後に鳴った端末の再生（同じアカウント）に合わせて鳴らす。
    fn stage_latest(self: &Rc<Self>, play: bool) {
        let staged = {
            let client = self.client.borrow();
            let Some(c) = client.as_ref().filter(|c| !c.is_writer()) else { return };
            let Some(tag) = self.shared.tag.borrow().as_ref().map(|t| t.tag.clone()) else { return };
            let Some(now) = c.remote_now_position().filter(|n| n.cursor.account_tag == tag) else { return };
            if c.latest_queue_hash() != Some(now.cursor.queue_ref.as_str()) {
                return;
            }
            let Some((_, queue)) = c.latest_queue_body() else { return };
            decode_queue(&queue.snapshot, &now.cursor.current_item_id).map(|q| (q, now.position_ms))
        };
        let Some(((items, index, source), position)) = staged else { return };
        // 同じ曲で位置も近ければそのまま
        if let Some(current) = self.shared.playback.current_item()
            && current.item_id == items[index].item_id
        {
            let local = self.shared.playback.player().position_ms().unwrap_or(0) as i64;
            if (local - position).abs() <= 3_000 {
                return;
            }
        }
        self.shared.playback.adopt(items, index, position.max(0) as u64, play, source);
        eprintln!("connect-v2 staged latest play={play}");
    }

    // --- 画面からの操作 ---

    /// 「この PC で再生」。
    pub fn transfer_here(self: &Rc<Self>) {
        let sent = self.with_client(ConnectClient::request_transfer);
        eprintln!("connect-v2 transfer requested={sent:?}");
        if sent != Some(true) {
            self.notify("引き継げませんでした");
        }
    }

    /// デバイスの一覧で、ほかの端末を選んだ（その端末に再生を移す）。
    pub fn play_on_device(self: &Rc<Self>, device_id: &str) {
        let sent = self.with_client(|c| c.request_remote_transfer(device_id));
        eprintln!("connect-v2 remote transfer requested={sent:?}");
        if sent != Some(true) {
            self.notify("その端末に移せませんでした");
        }
    }

    /// 家の wave API にログインを渡す（ADR-0015）。本人が確かめて押したときだけ呼ぶ。宛先の鍵にだけ包む。
    pub async fn grant_login(self: &Rc<Self>, device_id: &str) -> bool {
        let Some(credentials) = keyring::load().await else { return false };
        let sent = self.with_client(|c| {
            c.send_login_grant(device_id, credentials.reveal_cookie(), credentials.visitor_data.as_deref(), credentials.reveal_data_sync_id(), credentials.obtained_at_epoch_ms)
        }) == Some(true);
        eprintln!("connect-v2 login grant sent={sent}");
        sent
    }

    /// 映している端末へのシーク・音量。
    pub fn remote_seek(self: &Rc<Self>, position_ms: i64) {
        self.with_client(|c| c.send_control(ControlAction::Seek, Some(position_ms)));
    }

    /// シャッフル・繰り返しのボタン: ほかの端末を映していればその端末へ、そうでなければ手元で切り替える。
    pub fn toggle_shuffle(self: &Rc<Self>) {
        match self.mirror() {
            Some(mirror) => {
                let on = !mirror.shuffle.unwrap_or(false);
                self.with_client(|c| c.send_shuffle(on));
            }
            None => self.shared.playback.set_shuffle(!self.shared.playback.shuffled()),
        }
    }

    pub fn cycle_repeat(self: &Rc<Self>) {
        match self.mirror() {
            Some(mirror) => {
                let next = match mirror.repeat {
                    Repeat::Off => HandoffRepeat::All,
                    Repeat::All => HandoffRepeat::One,
                    Repeat::One => HandoffRepeat::Off,
                };
                self.with_client(|c| c.send_repeat(next));
            }
            None => self.shared.playback.cycle_repeat(),
        }
    }

    pub fn remote_volume(self: &Rc<Self>, percent: i32) {
        self.with_client(|c| c.send_volume(percent));
    }

    pub fn accept_store_reset(self: &Rc<Self>) {
        self.with_client(ConnectClient::accept_store_reset);
        self.changed();
    }

    /// 起動時の設定の鍵の端末で、家を作る（家が無いときだけ中継が受ける）。
    pub fn create_household(self: &Rc<Self>) {
        let Some(device) = self.device.clone() else { return };
        let ready = self.with_client(|c| {
            if c.household_id().is_some() {
                return false;
            }
            c.begin_create_household();
            true
        });
        if ready != Some(true) {
            return;
        }
        let Ok(genesis) = chain::genesis(&*device, &device.member_keys(Role::Player)) else { return };
        self.with_socket(RelaySocket::reconnect);
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            // つながるのを少し待つ（家が無い間は、設定の鍵の端末だけがつながれる）
            for _ in 0..50 {
                let Some(connect) = weak.upgrade() else { return };
                if connect.status.get() == RelayStatus::Connected {
                    break;
                }
                drop(connect);
                glib::timeout_future(Duration::from_millis(100)).await;
            }
            let Some(connect) = weak.upgrade() else { return };
            let sent = connect.send(RelayMessage::HouseholdCreate { genesis }) && connect.send(RelayMessage::ChainGet { from_seq: 0 });
            if !sent {
                connect.on_event(ClientEvent::ServerError { code: HOUSEHOLD_CREATE_UNSENT.into() });
            }
            eprintln!("connect-v2 household create sent={sent}");
        });
    }

    /// 家から外された端末で、本人が押したら、この端末の家の記録を消す。
    pub fn forget_household(self: &Rc<Self>) {
        self.with_client(ConnectClient::clear_local);
        let _ = std::fs::remove_file(data_path("connect-v2.json"));
        *self.shared.tag.borrow_mut() = None;
        self.unreadable.set(false);
        self.load_client();
        self.with_socket(RelaySocket::restart);
        eprintln!("connect-v2 household forgotten");
        self.changed();
    }

    /// 家から他の端末を外す。
    pub fn remove_member(self: &Rc<Self>, device_id: String) {
        let Some(device) = self.device.clone() else { return };
        let record = {
            let client = self.client.borrow();
            client.as_ref().and_then(|c| c.verified_chain()).filter(|chain| chain.members().contains_key(&device_id)).map(|chain| chain::remove(&*device, chain, &device_id))
        };
        let Some(record) = record else { return };
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let Some(connect) = weak.upgrade() else { return };
            let target = device_id.clone();
            let removed = connect.append_record(record, move |members| members.iter().all(|m| m.device_id != target)).await;
            eprintln!("connect-v2 remove member ok={removed}");
            connect.notify(if removed { "端末を外しました" } else { "外せませんでした" });
            connect.changed();
        });
    }

    async fn append_record(self: &Rc<Self>, record: wave_core::connect::models::ChainRecord, done: impl Fn(&[MemberKeys]) -> bool) -> bool {
        let (tx, rx) = tokio::sync::oneshot::channel();
        *self.chain_reply.borrow_mut() = Some(tx);
        if !self.send(RelayMessage::ChainAppend { record }) {
            return false;
        }
        match glib::future_with_timeout(Duration::from_secs(10), rx).await {
            Ok(Ok(RelayMessage::ChainOk { .. })) => return true,
            Ok(Ok(RelayMessage::Error { request_id: Some(_), .. })) | Err(_) | Ok(Err(_)) => {}
            Ok(Ok(_)) => return false,
        }
        // 返事が来なかった: 鎖を取り直して、今の端末一覧で済んでいるかを見る（やり直すと二重の記録になる）
        self.send(RelayMessage::ChainGet { from_seq: 0 });
        for _ in 0..25 {
            if self.client.borrow().as_ref().is_some_and(|c| done(&c.members())) {
                return true;
            }
            glib::timeout_future(Duration::from_millis(200)).await;
        }
        false
    }

    // --- 加入（招く側: 家の端末が文字列を出す、加わる側: 文字列を貼る） ---

    pub fn cancel_pairing(self: &Rc<Self>) {
        self.pairing_epoch.set(self.pairing_epoch.get() + 1);
        self.codes.borrow_mut().take();
        self.pair_hellos.borrow_mut().take();
        if self.pending_on_b.borrow_mut().take().is_some() || self.join_waiter.borrow_mut().take().is_some() {
            self.with_client(ConnectClient::cancel_join);
        }
        self.set_pairing(PairingView::Idle);
    }

    pub fn enter_code(&self, code: &str) {
        if let Some(tx) = self.codes.borrow().as_ref() {
            let _ = tx.send(code.trim().to_string());
        }
    }

    fn fail(&self, epoch: u64, message: &str) {
        if self.pairing_epoch.get() != epoch {
            return;
        }
        self.codes.borrow_mut().take();
        self.pending_on_b.borrow_mut().take();
        self.set_pairing(PairingView::Failed(message.to_string()));
        eprintln!("pairing-v2 failed");
    }

    fn still(&self, epoch: u64) -> bool {
        self.pairing_epoch.get() == epoch
    }

    /// 招く側: 家の端末として、加わる端末に文字列を出す。
    pub fn start_offer(self: &Rc<Self>) {
        self.cancel_pairing();
        let epoch = self.pairing_epoch.get();
        let this = self.clone();
        glib::spawn_future_local(async move { this.run_offer(epoch).await });
    }

    async fn run_offer(self: Rc<Self>, epoch: u64) {
        let Some(device) = self.device.clone() else { return };
        self.set_pairing(PairingView::Working("中継につないでいます…".into()));
        for _ in 0..150 {
            if matches!(self.status.get(), RelayStatus::Connected) || !self.still(epoch) {
                break;
            }
            glib::timeout_future(Duration::from_millis(100)).await;
        }
        if self.status.get() != RelayStatus::Connected {
            return self.fail(epoch, "中継につながりませんでした（Tailscale につながっているか確かめてください）");
        }
        let Some(join_info) = self.client.borrow().as_ref().and_then(ConnectClient::current_join_info) else {
            return self.fail(epoch, "この PC はまだ連携していません");
        };
        let repo = self.shared.repo.clone();
        let Some(account) = runtime::run(async move { repo.verified_account().await }).await else {
            return self.fail(epoch, "アカウントを確かめられませんでした");
        };
        if !self.still(epoch) {
            return;
        }
        let started = Instant::now();
        let identity: Arc<dyn DeviceSigner + Send + Sync> = device.clone();
        let mut responder =
            pairing::Responder::new(identity, &device_name(), &account.id, Box::new(move || started.elapsed().as_millis() as i64), Some(connect_json::encode(&join_info)));
        let token_hash = b64::encode(responder.token_hash());
        let (hello_tx, mut hello_rx) = mpsc::unbounded_channel();
        *self.pair_hellos.borrow_mut() = Some(hello_tx);
        if !self.send(RelayMessage::PairingOpen { token_hash: token_hash.clone() }) {
            return self.fail(epoch, "中継に送れませんでした");
        }
        self.set_pairing(PairingView::ShowingOffer(pairing::encode_offer(responder.offer())));
        let lifetime = Duration::from_millis(pairing::LIFETIME_MS as u64);
        let hello = loop {
            let remaining = lifetime.saturating_sub(started.elapsed());
            match glib::future_with_timeout(remaining, hello_rx.recv()).await {
                Ok(Some((hash, hello))) if hash == token_hash => break hello,
                Ok(Some(_)) => continue,
                _ => {
                    return self.fail(
                        epoch,
                        if self.status.get() != RelayStatus::Connected { "中継との接続が切れました" } else { "時間内に読み取られませんでした" },
                    );
                }
            }
        };
        self.pair_hellos.borrow_mut().take();
        if !self.still(epoch) {
            return;
        }
        let Some(decoded) = pairing::decode_hello(&hello) else {
            return self.fail(epoch, "相手から届いた内容を読めませんでした");
        };
        let confirm = match responder.on_hello(&decoded) {
            Ok(confirm) => confirm,
            Err(pairing::Failure::AccountMismatch) => return self.fail(epoch, "相手は別のアカウントでログインしています"),
            Err(_) => return self.fail(epoch, "相手を確かめられませんでした"),
        };
        if !self.send(RelayMessage::PairConfirm { token_hash, confirm: pairing::confirm_to_json(&confirm) }) {
            return self.fail(epoch, "中継に送れませんでした");
        }
        let (code_tx, mut code_rx) = mpsc::unbounded_channel();
        *self.codes.borrow_mut() = Some(code_tx);
        self.set_pairing(PairingView::EnterCode(None));
        loop {
            let remaining = lifetime.saturating_sub(started.elapsed());
            let code = match glib::future_with_timeout(remaining, code_rx.recv()).await {
                Ok(Some(code)) => code,
                _ => return self.fail(epoch, "時間切れになりました。もう一度やり直してください"),
            };
            if !self.still(epoch) {
                return;
            }
            let established = match responder.on_code_entered(&code) {
                Ok(established) => established,
                Err(pairing::Failure::WrongCode) => {
                    self.set_pairing(PairingView::EnterCode(Some("数字が違います".into())));
                    continue;
                }
                Err(_) => return self.fail(epoch, "連携できませんでした"),
            };
            if self.shared.repo.client().generation() != account.generation {
                return self.fail(epoch, "途中でログインが変わりました");
            }
            self.codes.borrow_mut().take();
            self.set_pairing(PairingView::Working("端末を加えています…".into()));
            let added = match &established.peer_extension {
                Some(member_keys) => self.add_member(member_keys, &established.peer_public_key).await,
                None => false,
            };
            if !added {
                return self.fail(epoch, "端末を加えられませんでした");
            }
            let peer_id = wave_core::connect::crypto::device_id(&established.peer_public_key);
            self.remember_label(&peer_id, &established.peer_device_name);
            eprintln!("pairing-v2 established role=A");
            self.set_pairing(PairingView::Done(established.peer_device_name.clone()));
            return;
        }
    }

    /// 招く側: 6桁の照合の後、加わる端末を家に加える。
    async fn add_member(self: &Rc<Self>, member_keys_json: &str, peer_public_key: &[u8]) -> bool {
        let Some(device) = self.device.clone() else { return false };
        let prepared = {
            let client = self.client.borrow();
            let Some(client) = client.as_ref() else { return false };
            let Some(chain) = client.verified_chain() else { return false };
            let Some(member) = connect_json::decode::<MemberKeys>(member_keys_json).filter(|m| m.role == Role::Player) else { return false };
            // 6桁で確かめた相手の身元鍵と同じであること
            if b64::decode(&member.identity_pub).as_deref() != Some(peer_public_key) {
                return false;
            }
            // 保存を作り直した端末が同じ鍵で加わり直す: 既にいるので追記しない
            if chain.members().get(&member.device_id).is_some_and(|existing| existing.keys.kx_pub == member.kx_pub) {
                return true;
            }
            let record = chain::add(&*device, chain, &member);
            // 積む前に、鎖として通ることを確かめる（相手の kx の署名が壊れていると、家の全端末がその鎖を拒んで止まる）
            let mut records = client.verified_records().map(<[_]>::to_vec).unwrap_or_default();
            records.push(record.clone());
            if !matches!(chain::verify(&records, None), ChainVerification::Ok(_)) {
                return false;
            }
            (record, member.device_id)
        };
        let (record, member_id) = prepared;
        self.append_record(record, move |members| members.iter().any(|m| m.device_id == member_id)).await
    }

    /// 加わる側: 家の端末が出した文字列（wave-pair:…）で加わる。
    pub fn accept(self: &Rc<Self>, offer_text: &str) {
        self.cancel_pairing();
        let epoch = self.pairing_epoch.get();
        let this = self.clone();
        let text = offer_text.trim().to_string();
        glib::spawn_future_local(async move { this.run_accept(epoch, text).await });
    }

    async fn run_accept(self: Rc<Self>, epoch: u64, offer_text: String) {
        let (Some(device), Some(socket)) = (self.device.clone(), self.socket.clone()) else { return };
        let Some(offer) = pairing::decode_offer(&offer_text) else {
            return self.fail(epoch, "wave の連携の文字列ではありません");
        };
        self.set_pairing(PairingView::Working("アカウントを確かめています…".into()));
        let repo = self.shared.repo.clone();
        let Some(account) = runtime::run(async move { repo.verified_account().await }).await else {
            return self.fail(epoch, "アカウントを確かめられませんでした（ログインしてください）");
        };
        if !self.still(epoch) {
            return;
        }
        self.set_pairing(PairingView::Working("相手に送っています…".into()));
        let identity: Arc<dyn DeviceSigner + Send + Sync> = device.clone();
        let extension = connect_json::encode(&device.member_keys(Role::Player));
        let Ok(initiator) = pairing::Initiator::new(&offer, identity, &device_name(), &account.id, Some(extension)) else {
            return self.fail(epoch, "wave の連携の文字列ではありません");
        };
        let hello = RelayMessage::PairHelloIn { token: offer.token.clone(), hello: pairing::hello_to_json(&initiator.hello()) }.encode();
        let reply = runtime::run(async move { socket.pair(hello).await }).await;
        if !self.still(epoch) {
            return;
        }
        let Some(RelayMessage::PairConfirmed { confirm }) = reply.as_deref().and_then(RelayMessage::decode) else {
            return self.fail(
                epoch,
                if reply.is_none() {
                    "中継につながりませんでした（Tailscale につながっているか確かめてください）"
                } else {
                    "相手が待っていません。相手でもう一度「端末を追加」を押してください"
                },
            );
        };
        let Some(confirm) = pairing::decode_confirm(&confirm) else {
            return self.fail(epoch, "相手から届いた内容を読めませんでした");
        };
        let confirmed = match initiator.on_confirm(&confirm) {
            Ok(confirmed) => confirmed,
            Err(pairing::Failure::AccountMismatch) => return self.fail(epoch, "相手は別のアカウントでログインしています"),
            Err(_) => return self.fail(epoch, "相手を確かめられませんでした"),
        };
        if self.shared.repo.client().generation() != account.generation {
            return self.fail(epoch, "途中でログインが変わりました");
        }
        let peer = confirmed.pair.peer_device_name.clone();
        *self.pending_on_b.borrow_mut() = Some(confirmed.pair);
        self.set_pairing(PairingView::ShowCode { code: confirmed.code, peer });
    }

    /// 加わる側: 相手に6桁を入力してもらった後の「続ける」。
    pub fn confirm_on_b(self: &Rc<Self>) {
        let Some(established) = self.pending_on_b.borrow_mut().take() else { return };
        let epoch = self.pairing_epoch.get() + 1;
        self.pairing_epoch.set(epoch);
        let Some(extension) = established.peer_extension.clone() else {
            return self.fail(epoch, "相手のアプリが古く、加われません");
        };
        let this = self.clone();
        glib::spawn_future_local(async move { this.run_join_on_b(epoch, established, extension).await });
    }

    async fn run_join_on_b(self: Rc<Self>, epoch: u64, established: pairing::Established, extension: String) {
        let Some(info) = connect_json::decode::<JoinInfo>(&extension) else {
            return self.fail(epoch, "相手から届いた内容を読めませんでした");
        };
        let inviter = wave_core::connect::crypto::device_id(&established.peer_public_key);
        let started = self.with_client(|c| {
            if c.household_id().is_some() {
                return false;
            }
            c.expect_join(info, inviter.clone());
            true
        });
        if started != Some(true) {
            return self.fail(epoch, "この PC は今は加われません");
        }
        self.set_pairing(PairingView::Working("相手が加えるのを待っています…".into()));
        let (tx, mut rx) = tokio::sync::oneshot::channel();
        *self.join_waiter.borrow_mut() = Some(tx);
        let deadline = Instant::now();
        let mut outcome = None;
        for wait in [2_000u64, 5_000, 10_000, 13_000] {
            if self.client.borrow().as_ref().is_some_and(|c| c.household_id().is_some()) {
                outcome = Some(ClientEvent::Joined);
                break;
            }
            // 家に入れてもらったら、つなぎ直して鎖を取る（認証は中継の鎖の今の端末で行う）
            self.with_socket(RelaySocket::reconnect);
            match glib::future_with_timeout(Duration::from_millis(wait), &mut rx).await {
                Ok(Ok(event)) => {
                    outcome = Some(event);
                    break;
                }
                Ok(Err(_)) => break,
                Err(_) => {}
            }
            if !self.still(epoch) || deadline.elapsed() > Duration::from_secs(30) {
                break;
            }
        }
        self.join_waiter.borrow_mut().take();
        if !self.still(epoch) {
            return;
        }
        if outcome == Some(ClientEvent::Joined) {
            self.remember_label(&inviter, &established.peer_device_name);
            eprintln!("pairing-v2 established role=B");
            self.set_pairing(PairingView::Done(established.peer_device_name));
        } else {
            self.with_client(ConnectClient::cancel_join);
            self.fail(epoch, if outcome.is_none() { "加われませんでした（時間切れ）" } else { "端末の一覧を確かめられませんでした" });
        }
    }

    fn remember_label(&self, device_id: &str, name: &str) {
        let mut names = self.names.borrow_mut();
        names.labels.insert(device_id.to_string(), name.chars().take(MAX_LABEL_CHARS).collect());
        write_atomically(&data_path("connect-v2-names.json"), &serde_json::to_string(&*names).expect("plain data"));
    }

    // --- キューの書き出し ---

    /// 今のキューの doc の文字列。中身が同じなら同じ文字列にするため、位置・履歴・時刻は入れない。
    fn snapshot(&self) -> Option<String> {
        if !self.shared.repo.client().is_logged_in() {
            return None;
        }
        let (queue, index) = self.shared.playback.queue_items();
        let current = queue.get(index?)?.item_id.clone();
        let items: Vec<QueueItem> = queue.into_iter().filter(|i| snapshot::is_item_video_id(&i.song.video_id) && !i.song.title.is_empty()).collect();
        let index = items.iter().position(|i| i.item_id == current)?;
        let (window, window_index, truncated) = snapshot::window(&items, index);
        let source = self.shared.playback.source();
        // 出どころは Android の handoffSnapshot と同じ形にする（AutoRadio.ForHandoff と同じ）
        let (radio_seed, radio_playlist_id, exhausted) = if source.radio {
            let seed = items.first().map(|i| i.song.video_id.clone());
            let playlist = source.radio_playlist_id.clone().or_else(|| seed.as_ref().map(|s| format!("RDAMVM{s}")));
            (seed, playlist, false)
        } else {
            match items.last().filter(|i| !i.song.is_live).map(|i| i.song.video_id.clone()) {
                None => (None, None, true),
                Some(seed) if source.playlist_id.is_none() => (Some(seed.clone()), Some(format!("RDAMVM{seed}")), false),
                Some(_) => (None, None, false),
            }
        };
        let handoff_source = match (&radio_seed, &source.playlist_id) {
            (Some(seed), _) => Some(HandoffSource::Radio { seed_video_id: seed.clone() }),
            (None, Some(id)) if valid_id(id, 128) => Some(HandoffSource::Playlist { playlist_id: id.clone() }),
            _ => None,
        };
        let snapshot = HandoffSnapshot {
            schema_version: snapshot::SCHEMA_VERSION,
            handoff_id: SNAPSHOT_ID.to_string(),
            items: window.iter().map(item_of).collect(),
            current_item_id: window[window_index].item_id.clone(),
            position_ms: 0,
            playing: false,
            repeat: HandoffRepeat::Off,
            source: handoff_source,
            continuation: if truncated || !source.radio { None } else { source.continuation.clone() },
            radio_playlist_id,
            exhausted: exhausted && !truncated,
            listened_ms: 0,
            history_sent: false,
            captured_at_epoch_ms: 0,
        };
        match snapshot::validate(snapshot, SNAPSHOT_ID) {
            HandoffDecodeResult::Ok(valid) => Some(snapshot::encode(&valid)),
            _ => None,
        }
    }
}

fn valid_id(id: &str, max: usize) -> bool {
    (2..=max).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

fn item_of(item: &QueueItem) -> HandoffItem {
    let song = &item.song;
    let browse = |id: &Option<String>| id.clone().filter(|i| valid_id(i, 64));
    HandoffItem {
        item_id: item.item_id.clone(),
        video_id: song.video_id.clone(),
        title: song.title.chars().take(500).collect(),
        artists: song.artists.iter().filter(|a| (1..=500).contains(&a.name.chars().count())).take(20).map(|a| HandoffRef { name: a.name.clone(), browse_id: browse(&a.browse_id) }).collect(),
        album: song.album.as_ref().filter(|a| (1..=500).contains(&a.title.chars().count())).map(|a| HandoffRef { name: a.title.clone(), browse_id: browse(&a.browse_id) }),
        duration_seconds: song.duration_seconds.filter(|d| *d <= 24 * 3600).map(|d| d as i32),
        thumbnail_url: song.thumbnail_url.clone(),
        origin: match item.origin {
            Origin::Source => HandoffOrigin::Source,
            Origin::Suggestion => HandoffOrigin::Suggestion,
            Origin::UserAdded => HandoffOrigin::UserAdded,
        },
        is_live: song.is_live,
    }
}

/// 受け渡しの項目を、キューの曲へ。
fn entry_of(item: &HandoffItem) -> QueueItem {
    let song = Song {
        video_id: item.video_id.clone(),
        title: item.title.clone(),
        artists: item.artists.iter().map(|a| ArtistRef { name: a.name.clone(), browse_id: a.browse_id.clone() }).collect(),
        album: item.album.as_ref().map(|a| AlbumRef { title: a.name.clone(), browse_id: a.browse_id.clone() }),
        duration_seconds: if item.is_live { None } else { item.duration_seconds.and_then(|d| u32::try_from(d).ok()) },
        thumbnail_url: item.thumbnail_url.clone(),
        kind: None,
        is_live: item.is_live,
    };
    QueueItem {
        song,
        item_id: item.item_id.clone(),
        origin: match item.origin {
            HandoffOrigin::Source => Origin::Source,
            HandoffOrigin::Suggestion => Origin::Suggestion,
            HandoffOrigin::UserAdded => Origin::UserAdded,
        },
    }
}

fn decode_snapshot(text: &str) -> Option<HandoffSnapshot> {
    let id = connect_json::decode::<HandoffSnapshot>(text)?.handoff_id;
    match snapshot::decode(text, &id) {
        HandoffDecodeResult::Ok(snapshot) => Some(snapshot),
        _ => None,
    }
}

/// キューの doc を、カーソルの今の曲から鳴らせる形にする（曲・今の位置・出どころ）。
fn decode_queue(text: &str, current_item_id: &str) -> Option<(Vec<QueueItem>, usize, QueueSource)> {
    let snapshot = decode_snapshot(text)?;
    let items: Vec<QueueItem> = snapshot.items.iter().map(entry_of).collect();
    let index = items.iter().position(|i| i.item_id == current_item_id)?;
    let source = QueueSource {
        playlist_id: match &snapshot.source {
            Some(HandoffSource::Playlist { playlist_id }) => Some(playlist_id.clone()),
            _ => None,
        },
        // 続きが無い（「次に流れる曲」を消去した・ラジオが尽きた）キューは、受け取ってからも埋め直さない
        radio: matches!(snapshot.source, Some(HandoffSource::Radio { .. })) && !snapshot.exhausted,
        radio_playlist_id: snapshot.radio_playlist_id.clone(),
        continuation: if snapshot.exhausted { None } else { snapshot.continuation.clone() },
    };
    Some((items, index, source))
}

fn handoff_repeat(repeat: Repeat) -> HandoffRepeat {
    match repeat {
        Repeat::Off => HandoffRepeat::Off,
        Repeat::All => HandoffRepeat::All,
        Repeat::One => HandoffRepeat::One,
    }
}

fn repeat_of(repeat: HandoffRepeat) -> Repeat {
    match repeat {
        HandoffRepeat::Off => Repeat::Off,
        HandoffRepeat::All => Repeat::All,
        HandoffRepeat::One => Repeat::One,
    }
}

/// 手元の再生の、カーソルに入れる値。
fn local_cursor(playback: &Playback, now: i64) -> Option<LocalCursor> {
    let item = playback.current_item()?;
    let player = playback.player();
    Some(LocalCursor {
        current_item_id: item.item_id,
        position_ms: player.position_ms().unwrap_or(0) as i64,
        playing: player.is_playing(),
        repeat: handoff_repeat(playback.repeat()),
        listened_ms: 0,
        history_sent: false,
        captured_at_ms: now,
        volume_percent: Some((player.volume() * 100.0).round() as i32),
        shuffle: Some(playback.shuffled()),
    })
}

fn now_ms(shared: &Shared) -> i64 {
    shared.started.elapsed().as_millis() as i64
}

fn epoch_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

/// 窓が前面にあるか（裏でメディアキーを押したときに、ほかの端末の再生を黙って引き取らない）。
fn foreground() -> bool {
    use gtk::prelude::*;
    gtk::gio::Application::default()
        .and_then(|app| app.downcast::<gtk::Application>().ok())
        .and_then(|app| app.active_window())
        .is_some_and(|w| w.is_active())
}

fn device_name() -> String {
    let host = glib::host_name().to_string();
    if host.is_empty() || host == "localhost" { "Linux PC".to_string() } else { host }
}

fn is_bidi_control(c: char) -> bool {
    matches!(c, '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}' | '\u{200E}' | '\u{200F}')
}

fn event_name(event: &ClientEvent) -> &'static str {
    match event {
        ClientEvent::Stopped { .. } => "Stopped",
        ClientEvent::RemoteChanged { .. } => "RemoteChanged",
        ClientEvent::ChainForked => "ChainForked",
        ClientEvent::ChainRolledBack => "ChainRolledBack",
        ClientEvent::ChainInvalid { .. } => "ChainInvalid",
        ClientEvent::StoreReset => "StoreReset",
        ClientEvent::StateEquivocation => "StateEquivocation",
        ClientEvent::TransferFailed { .. } => "TransferFailed",
        ClientEvent::TransferCompleted { .. } => "TransferCompleted",
        ClientEvent::ServerError { .. } => "ServerError",
        ClientEvent::Joined => "Joined",
        ClientEvent::Profile { .. } => "Profile",
    }
}

const REMOTE_PLAYING: &str = "remote_playing";
const RELAY_UNREACHABLE: &str = "relay_unreachable";
const NOT_MEMBER_PLAY: &str = "not_member_play";
const ACCOUNT_UNVERIFIED_PLAY: &str = "account_unverified_play";
const HOUSEHOLD_CREATE_UNSENT: &str = "household_create_unsent";

fn data_path(name: &str) -> std::path::PathBuf {
    glib::user_data_dir().join("wave").join(name)
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &std::path::Path) -> Option<T> {
    serde_json::from_str(&std::fs::read_to_string(path).ok()?).ok()
}

fn write_atomically(path: &std::path::Path, text: &str) {
    let temp = path.with_extension("tmp");
    let written = std::fs::create_dir_all(path.parent().expect("data path has a parent")).and_then(|_| std::fs::write(&temp, text)).and_then(|_| std::fs::rename(&temp, path));
    if let Err(e) = written {
        eprintln!("connect-v2 persist failed: {}", e.kind());
    }
}

/// 端末の鍵（キーリング）を読む。無ければ作って置く。読めない形なら (None, true)。
async fn load_or_create_device() -> (Option<Arc<SoftwareDevice>>, bool) {
    #[derive(Serialize, Deserialize)]
    struct Stored {
        identity: String,
        kx: String,
    }
    if let Some(text) = keyring::load_connect_keys().await {
        let device = serde_json::from_str::<Stored>(&text)
            .ok()
            .and_then(|s| Some((b64::decode(&s.identity)?, b64::decode(&s.kx)?)))
            .and_then(|(identity, kx)| SoftwareDevice::from_pkcs8(&identity, &kx).ok());
        eprintln!("connect-v2 keys state={}", if device.is_some() { "loaded" } else { "unreadable" });
        return match device {
            Some(device) => (Some(Arc::new(device)), false),
            None => (None, true),
        };
    }
    let device = SoftwareDevice::generate();
    let stored = Stored { identity: b64::encode(&device.identity_pkcs8()), kx: b64::encode(&device.kx_pkcs8()) };
    if !keyring::save_connect_keys(serde_json::to_string(&stored).expect("plain data")).await {
        eprintln!("connect-v2 keys state=unsaved");
        return (None, true);
    }
    // 新しい鍵（端末 ID が変わる）: 前の鍵で入っていた家の記録は使えないので消す（加入し直す）
    let _ = std::fs::remove_file(data_path("connect-v2.json"));
    eprintln!("connect-v2 keys state=created");
    (Some(Arc::new(device)), false)
}

/// 本人が再生を始めたときの判断（D-9、Windows・Android の PlayButtonPolicy と同じ）。
pub mod play_policy {
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Decision {
        /// 手元のキューで新しい再生として claim する。
        Claim,
        /// 家で最後に鳴った再生（キューと位置）に手元を合わせてから claim する。
        SyncAndClaim,
        /// 一時停止して、最新の状態とアカウントタグがそろうのを待つ。
        Wait,
        /// 他の端末で鳴っている: 引き取る（前面）。
        TakeOver,
        /// 他の端末で鳴っている: 鳴らさずに知らせる（裏）。
        BlockRemotePlaying,
        /// 待ちで止めた再生を戻して claim する。
        ResumeAndClaim,
        /// 何もしない（待っている間に本人が別の操作をした・もう退いた端末でない）。
        Nothing,
        NotifyNotMember,
        NotifyUnreachable,
        NotifyUnverified,
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum WaitOutcome {
        Ready,
        NotMember,
        Unreachable,
        Unverified,
    }

    #[allow(clippy::too_many_arguments)]
    pub fn on_play(new_selection: bool, retreated: bool, in_household: bool, state_settled: bool, has_account_tag: bool, remote_playing: bool, foreground: bool, has_local_queue: bool) -> Decision {
        // 曲を選ぶのは、いつでも新しい再生（L-3）
        if new_selection || !in_household {
            return Decision::Claim;
        }
        // 前面で再生ボタンを押したら、待たずにこの端末で鳴らす。キューは家で最後に鳴ったものに合わせる。状態がそろう前は手元のまま鳴らす
        if foreground && (has_local_queue || !retreated) {
            return if state_settled && has_account_tag { Decision::SyncAndClaim } else { Decision::Claim };
        }
        if !retreated {
            return Decision::Claim;
        }
        // 古い状態で「鳴っていない」と決めない
        if !state_settled || !has_account_tag {
            return Decision::Wait;
        }
        if !remote_playing {
            return Decision::Claim;
        }
        if foreground { Decision::TakeOver } else { Decision::BlockRemotePlaying }
    }

    /// 待ちの1回分。そろっていれば Ready、まだなら None。
    pub fn wait_step(not_member: bool, tailnet_unreachable: bool, settled_and_tagged: bool) -> Option<WaitOutcome> {
        if not_member {
            Some(WaitOutcome::NotMember)
        } else if tailnet_unreachable {
            Some(WaitOutcome::Unreachable)
        } else if settled_and_tagged {
            Some(WaitOutcome::Ready)
        } else {
            None
        }
    }

    /// 時間切れ: 状態はそろっているならアカウントの問題、そうでなければ中継の問題。
    pub fn timeout_outcome(settled: bool) -> WaitOutcome {
        if settled { WaitOutcome::Unverified } else { WaitOutcome::Unreachable }
    }

    pub fn after_wait(outcome: WaitOutcome, intent_unchanged: bool, still_retreated: bool, remote_playing: bool, foreground: bool) -> Decision {
        if !intent_unchanged || !still_retreated {
            return Decision::Nothing;
        }
        match outcome {
            WaitOutcome::Ready if !remote_playing => Decision::ResumeAndClaim,
            WaitOutcome::Ready if foreground => Decision::TakeOver,
            WaitOutcome::Ready => Decision::BlockRemotePlaying,
            WaitOutcome::NotMember => Decision::NotifyNotMember,
            WaitOutcome::Unreachable => Decision::NotifyUnreachable,
            WaitOutcome::Unverified => Decision::NotifyUnverified,
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn choosing_a_song_is_always_a_new_claim() {
            assert_eq!(on_play(true, true, true, false, false, true, false, true), Decision::Claim);
            assert_eq!(on_play(false, true, false, false, false, true, false, true), Decision::Claim);
        }

        #[test]
        fn play_button_in_front_syncs_once_settled() {
            assert_eq!(on_play(false, true, true, true, true, true, true, true), Decision::SyncAndClaim);
            assert_eq!(on_play(false, true, true, false, true, true, true, true), Decision::Claim);
        }

        #[test]
        fn media_key_in_the_background_waits_then_blocks_a_remote_player() {
            assert_eq!(on_play(false, true, true, false, true, false, false, true), Decision::Wait);
            assert_eq!(on_play(false, true, true, true, true, true, false, true), Decision::BlockRemotePlaying);
            assert_eq!(after_wait(WaitOutcome::Ready, true, true, false, false), Decision::ResumeAndClaim);
            assert_eq!(after_wait(WaitOutcome::Ready, false, true, false, false), Decision::Nothing);
        }
    }
}
