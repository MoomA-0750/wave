//! Connect v2 の端末側（Windows・Android の ConnectClient と同じ状態機械、D-2・D-6〜D-9）。
//! 受信・画面の操作・時間の経過（tick）を1本のスレッドから呼ぶこと。暗号と署名はここで行い、再生そのものは Effects に任せる。
//! 端末の時計は順序の判断に使わない（L-10）。Linux 版は RSS のライブラリを持たないので、ライブラリの同期（ADR-0013）は行わない。

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use super::arbiter::{self, Decision, Evidence};
use super::chain::{self, ChainVerification, DeviceIdentity, Head, VerifiedChain};
use super::device::SoftwareDevice;
use super::doc::{self, DocOpen};
use super::hpke;
pub use super::models::JoinInfo;
use super::models::{
    ChainRecord, ChainType, CommandBody, ControlAction, ControlCommand, CursorBody, DocHeader, DocKind, LoginGrantCommand, MemberKeys, ProfileCommand, QueueAddCommand, QueueBody,
    QueueEditCommand, Role, SealedDoc, YieldCommand, connect_json,
};
use super::snapshot::{HandoffItem, HandoffRepeat};
use super::relay::{RelayMessage, StateKind, StoredState};

pub const TRANSFER_WAIT_MS: i64 = 3_000;
pub const FLOOR_PERSIST_MS: i64 = 30_000;
pub const MAX_EXTRAPOLATION_MS: i64 = 9 * 60 * 1000;
const MAX_HANDLED_CLAIMS: usize = 64;
/// 遠隔操作の送り手が見ていた版と、受け手の最新の書き込みの版の差の上限（それより古い操作には従わない）。
const MAX_CONTROL_LAG: i64 = 20;
/// 遠隔操作の鮮度の上限（命令の見出しの時刻と受け手の時計の差）。
const MAX_CONTROL_AGE_MS: i64 = 5 * 60 * 1000;
/// キューへの追加の命令1件の平文の上限（詰め物の1ブロックに収める。Android・Windows と同じ）。
pub const MAX_QUEUE_ADD_PLAINTEXT: usize = 3800;
const HAND_OFF_WAIT_MS: i64 = 10_000;
/// ログインを渡す命令の平文の上限（詰め物の2ブロック、中継の 16KB に収まる。Android・Windows と同じ）。
pub const MAX_LOGIN_GRANT_PLAINTEXT: usize = 8000;

/// 端末が保存しておくもの（D-2 の頭、D-4 の storeId と版の床、D-9 の退いた印、処理した claimId）。
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ClientPersisted {
    pub chain: Vec<ChainRecord>,
    pub store_id: Option<String>,
    pub cursor_floor: i64,
    pub queue_floor: i64,
    pub retreated: bool,
    pub handled_claim_ids: Vec<String>,
    /// 加入を待っている（Confirm の床と招いた端末）。プロセスが落ちても、無条件に最初の鎖を信じない。
    pub expected_join: Option<JoinInfo>,
    pub expected_inviter: Option<String>,
    /// 家を作ると押した（最初の鎖が自分の genesis なら受け入れる）。
    pub creating_household: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub enum ClientEvent {
    /// ほかの端末の再生で、この端末が止まった。
    Stopped { by: String },
    /// ほかの端末の状態が変わった（表示）。account_matches が false なら、別のアカウントの端末（表示だけ）。
    RemoteChanged { writer: String, account_matches: bool },
    /// 中継が、検証した一覧と同じ seq で別の記録を見せた。
    ChainForked,
    ChainRolledBack,
    /// 受けた記録が検証を通らなかった（改ざん・不正な追記）。
    ChainInvalid { reason: String },
    /// 中継の状態が作り直された。accept_store_reset を呼ぶまで、その状態で止まらない・取り込まない（L-12）。
    StoreReset,
    /// 同じ書き手・同じ版で中身が違う状態を見た（中継の不正か不具合）。
    StateEquivocation,
    TransferFailed { reason: String },
    TransferCompleted { from: String },
    ServerError { code: String },
    /// 家に入った（加入の鎖を受け入れた、または自分の genesis の鎖を受け入れた）。
    Joined,
    /// 家の端末が表示名を知らせてきた（表示だけ）。
    Profile { device_id: String, name: String, kind: Option<String> },
}

/// 手元の再生の、カーソルに入れる値。captured_at_ms は position_ms を取った時刻（now_ms と同じ単調時計）。
#[derive(Clone, Debug, PartialEq)]
pub struct LocalCursor {
    pub current_item_id: String,
    pub position_ms: i64,
    pub playing: bool,
    pub repeat: HandoffRepeat,
    pub listened_ms: i64,
    pub history_sent: bool,
    pub captured_at_ms: i64,
    pub volume_percent: Option<i32>,
    /// シャッフル中か。シャッフルを持たない端末は None（ほかの端末はシャッフル・繰り返しの操作を出さない）。
    pub shuffle: Option<bool>,
}

/// 表示用: ほかの端末の最新のカーソルと、今の時刻に合わせた位置。
#[derive(Clone, Debug, PartialEq)]
pub struct RemoteNow {
    pub writer: String,
    pub cursor: CursorBody,
    pub position_ms: i64,
    pub version: i64,
}

/// 再生そのもの・保存・知らせ。ConnectClient の中から呼ぶので、ここから ConnectClient を同期で呼び返さない（後で呼ぶ）。
pub trait Effects {
    /// 一時停止し、止めた時点の手元の再生を返す。
    fn pause(&self) -> Option<LocalCursor>;
    /// 取り込んで position_ms から再生を始める。受け付けなければ false。
    fn adopt(&self, snapshot: &str, cursor: &CursorBody, position_ms: i64) -> bool;
    fn event(&self, event: ClientEvent);
    fn persist(&self, state: &ClientPersisted);
    /// 今のログインのアカウントタグ（確かめていなければ None）。
    fn account_tag(&self) -> Option<String>;
    /// 遠隔操作に従う（この端末が鳴らしている）。
    fn control(&self, _action: ControlAction, _position_ms: Option<i64>) -> bool {
        false
    }
    fn queue_add(&self, _items: &[HandoffItem], _next: bool, _after_item_id: Option<&str>) -> bool {
        false
    }
    fn queue_move(&self, _item_id: &str, _after_item_id: Option<&str>) -> bool {
        false
    }
    fn queue_remove(&self, _item_ids: &[String]) -> bool {
        false
    }
    fn queue_clear_flow(&self, _current_item_id: &str) -> bool {
        false
    }
    fn volume(&self, _volume_percent: i32) -> bool {
        false
    }
    fn shuffle(&self, _on: bool) -> bool {
        false
    }
    fn repeat(&self, _mode: HandoffRepeat) -> bool {
        false
    }
}

struct Opened<T> {
    stored: StoredState,
    body: T,
    hash: String,
    received_at_local_ms: i64,
}

impl<T> Opened<T> {
    fn writer(&self) -> &str {
        &self.stored.doc.header.writer_id
    }
    fn version(&self) -> i64 {
        self.stored.version
    }
}

struct Inflight {
    kind: StateKind,
    doc: SealedDoc,
    hash: String,
    cursor: Option<CursorBody>,
    queue: Option<QueueBody>,
    /// 送ったときのセッション（前のセッションの返事で、新しいセッションの startVersion を決めない）。
    session_id: u64,
}

struct TransferState {
    claim_id: String,
    previous_writer: String,
    started_at_ms: i64,
    retried: bool,
    adopting: bool,
}

struct Session {
    id: u64,
    arbiter: arbiter::Session,
    claim_id: Option<String>,
    claim_order: Option<i64>,
}

pub struct Clock {
    /// 単調時計（ミリ秒）。transfer の3秒と外挿の足し算にだけ使う。
    pub now_ms: Box<dyn Fn() -> i64>,
    /// 表示・命令の鮮度に使う時刻。
    pub epoch_ms: Box<dyn Fn() -> i64>,
    pub new_id: Box<dyn Fn() -> String>,
}

pub struct ConnectClient {
    device: Arc<SoftwareDevice>,
    role: Role,
    send: Box<dyn Fn(RelayMessage) -> bool>,
    effects: Box<dyn Effects>,
    clock: Clock,

    records: Vec<ChainRecord>,
    chain: Option<VerifiedChain>,
    store_id: Option<String>,
    pending_store_id: Option<String>,
    cursor_floor: i64,
    queue_floor: i64,
    retreated: bool,
    handled_claims: VecDeque<String>,
    expected_join: Option<(JoinInfo, String)>,
    creating_household: bool,
    /// タグが無い間に本人が再生を始めた。タグができたら claim する。
    start_waiting_for_tag: bool,

    latest_cursor: Option<Opened<CursorBody>>,
    latest_queue: Option<Opened<QueueBody>>,
    deferred: Vec<StoredState>,
    server_active: Option<String>,
    pending_owner: Option<String>,
    claim_orders: HashMap<String, i64>,
    /// 中継につながっている家の端末（中継の申告。表示と D-9 の判断だけに使い、止まる根拠にしない）。
    online: HashSet<String>,
    /// 今の接続で welcome を処理した。
    welcomed: bool,
    /// 同時に始めた相手の証拠で、claimOrder を待って保留しているもの（D-8）。
    deferred_evidence: Vec<Evidence>,
    /// 開いた遠隔操作の doc のハッシュ（同じ doc の再送を開く前に捨てる）。
    seen_control_docs: VecDeque<String>,

    local_queue: Option<String>,
    local_queue_ref: Option<String>,
    local: Option<LocalCursor>,
    session: Option<Session>,
    session_counter: u64,
    device_seq: i64,
    last_own_cursor_hash: Option<String>,
    inflight: HashMap<String, Inflight>,
    dirty_cursor: bool,
    dirty_queue: bool,
    transfer: Option<TransferState>,
    hand_off: Option<(String, i64)>,
    /// つながり直した後、最新の状態と鎖をそろえてから書き込みを再開する。
    resume_pending: bool,
    floor_dirty: bool,
    last_persist_ms: Option<i64>,
}

impl ConnectClient {
    pub fn new(device: Arc<SoftwareDevice>, role: Role, send: Box<dyn Fn(RelayMessage) -> bool>, effects: Box<dyn Effects>, clock: Clock, initial: ClientPersisted) -> Self {
        let chain = if initial.chain.is_empty() {
            None
        } else {
            match chain::verify(&initial.chain, None) {
                ChainVerification::Ok(c) => Some(c),
                _ => None,
            }
        };
        let expected_join = match (initial.expected_join, initial.expected_inviter) {
            (Some(info), Some(inviter)) => Some((info, inviter)),
            _ => None,
        };
        Self {
            device,
            role,
            send,
            effects,
            clock,
            records: initial.chain,
            chain,
            store_id: initial.store_id,
            pending_store_id: None,
            cursor_floor: initial.cursor_floor,
            queue_floor: initial.queue_floor,
            retreated: initial.retreated,
            handled_claims: initial.handled_claim_ids.into(),
            expected_join,
            creating_household: initial.creating_household,
            start_waiting_for_tag: false,
            latest_cursor: None,
            latest_queue: None,
            deferred: Vec::new(),
            server_active: None,
            pending_owner: None,
            claim_orders: HashMap::new(),
            online: HashSet::new(),
            welcomed: false,
            deferred_evidence: Vec::new(),
            seen_control_docs: VecDeque::new(),
            local_queue: None,
            local_queue_ref: None,
            local: None,
            session: None,
            session_counter: 0,
            device_seq: 0,
            last_own_cursor_hash: None,
            inflight: HashMap::new(),
            dirty_cursor: false,
            dirty_queue: false,
            transfer: None,
            hand_off: None,
            resume_pending: false,
            floor_dirty: false,
            last_persist_ms: None,
        }
    }

    fn now(&self) -> i64 {
        (self.clock.now_ms)()
    }
    fn epoch(&self) -> i64 {
        (self.clock.epoch_ms)()
    }
    fn new_id(&self) -> String {
        (self.clock.new_id)()
    }
    fn send(&self, message: RelayMessage) -> bool {
        (self.send)(message)
    }
    fn my_id(&self) -> &str {
        self.device.device_id()
    }

    pub fn is_writer(&self) -> bool {
        self.session.is_some()
    }
    pub fn active_device_id(&self) -> Option<&str> {
        self.server_active.as_deref()
    }
    pub fn has_retreated(&self) -> bool {
        self.retreated
    }
    pub fn online(&self) -> &HashSet<String> {
        &self.online
    }
    /// 検証した家の ID（アカウントタグの計算に使う）。家に入っていなければ None。
    pub fn household_id(&self) -> Option<&str> {
        self.chain.as_ref().map(|c| c.household_id())
    }
    /// 検証した端末一覧（表示）。
    pub fn members(&self) -> Vec<MemberKeys> {
        let mut members: Vec<MemberKeys> = self.chain.as_ref().map(|c| c.members().values().map(|m| m.keys.clone()).collect()).unwrap_or_default();
        members.sort_by(|a, b| a.device_id.cmp(&b.device_id));
        members
    }
    /// 中継の状態が作り直されたと気づき、本人の確認を待っている（L-12）。
    pub fn store_reset_pending(&self) -> bool {
        self.pending_store_id.is_some()
    }
    pub fn verified_chain(&self) -> Option<&VerifiedChain> {
        self.chain.as_ref()
    }
    /// 検証した鎖の記録（追記する前に、追記後の鎖が通るかを確かめるのに使う）。
    pub fn verified_records(&self) -> Option<&[ChainRecord]> {
        self.chain.as_ref().map(|_| self.records.as_slice())
    }
    /// 表示用の最新のカーソル（別アカウントのものを含む）。
    pub fn latest_cursor_body(&self) -> Option<(&str, &CursorBody)> {
        self.latest_cursor.as_ref().map(|c| (c.writer(), &c.body))
    }
    /// 表示用の最新のキュー。snapshot は受け渡しの JSON。
    pub fn latest_queue_body(&self) -> Option<(&str, &QueueBody)> {
        self.latest_queue.as_ref().map(|q| (q.writer(), &q.body))
    }
    pub fn latest_queue_hash(&self) -> Option<&str> {
        self.latest_queue.as_ref().map(|q| q.hash.as_str())
    }
    /// 今の接続で最新の状態をそろえた。D-9 の判断はこれが true のときだけ行う。
    pub fn state_settled(&self) -> bool {
        self.welcomed && self.deferred.is_empty()
    }

    /// 同じアカウントの他の端末が今鳴っているか（D-9）。最後のカーソルが再生中で、その端末が中継につながっているとき。
    pub fn remote_playing_now(&self) -> bool {
        let (Some(cursor), Some(tag)) = (&self.latest_cursor, self.effects.account_tag()) else { return false };
        cursor.writer() != self.my_id() && cursor.body.playing && cursor.body.account_tag == tag && self.online.contains(cursor.writer())
    }

    /// 招く側: Confirm に入れる床（家に入っていなければ None）。
    pub fn current_join_info(&self) -> Option<JoinInfo> {
        let chain = self.chain.as_ref()?;
        let store = self.store_id.clone()?;
        Some(JoinInfo {
            household_id: chain.household_id().to_string(),
            head_seq: chain.head_seq(),
            head_hash: chain.head_hash().to_string(),
            store_id: store,
            cursor_floor: self.cursor_floor,
            queue_floor: self.queue_floor,
        })
    }

    /// 加わる側: 加入を待つ。次に受ける鎖が、この家の ID・頭を含み、その後に inviter の署名で自分を加える記録があるときだけ受け入れる。
    pub fn expect_join(&mut self, info: JoinInfo, inviter_device_id: String) {
        if self.chain.is_some() {
            return;
        }
        self.store_id = Some(info.store_id.clone());
        self.cursor_floor = info.cursor_floor;
        self.queue_floor = info.queue_floor;
        self.expected_join = Some((info, inviter_device_id));
        self.creating_household = false;
        self.persist();
    }

    // --- 受信 ---

    pub fn on_message(&mut self, message: RelayMessage) {
        match message {
            RelayMessage::Welcome { store_id, head_seq, head_hash, active_device_id, online, states, claim_orders, .. } => {
                self.on_welcome(store_id, head_seq, head_hash, active_device_id, online, states, claim_orders)
            }
            RelayMessage::Chain { records } => self.on_chain(records),
            // 自分の追記の返事（chain_ok）は、他の端末への chain_changed を受けないので、同じく取り直す
            RelayMessage::ChainChanged { head_seq, head_hash } | RelayMessage::ChainOk { head_seq, head_hash } => self.on_head_announced(head_seq, Some(&head_hash)),
            RelayMessage::State { state } => self.on_state(state),
            RelayMessage::StateOk { request_id, kind, version } => self.on_state_ok(&request_id, kind, version),
            RelayMessage::StateConflict { request_id, latest, .. } => self.on_state_conflict(&request_id, latest),
            RelayMessage::Error { code, request_id } => self.on_error(&code, request_id),
            RelayMessage::Yield { claim_id, yield_doc } => self.on_yield(&claim_id, &yield_doc),
            RelayMessage::Control { doc } => self.on_control(&doc),
            RelayMessage::ActiveChanged { active_device_id, claim_id, claim_order, pending_owner } => self.on_active_changed(active_device_id, claim_id, claim_order, pending_owner),
            RelayMessage::PendingExpired { claim_id } => self.on_pending_expired(&claim_id),
            RelayMessage::Presence { online } => self.online = online.into_iter().collect(),
            _ => {}
        }
    }

    /// 接続が切れた。返事を待っていた書き込みは、つながり直した後に最新の版へ積み直す。
    pub fn on_disconnected(&mut self) {
        self.reset_connection();
        // 取り込む前に切れた引き取りは捨てる（つながり直した後に、古い状態から突然取り込まない）
        if self.transfer.as_ref().is_some_and(|t| !t.adopting) {
            self.fail_transfer("disconnected", None);
        }
    }

    fn reset_connection(&mut self) {
        let kinds: Vec<StateKind> = self.inflight.values().map(|i| i.kind).collect();
        for kind in kinds {
            self.mark_unsent(kind);
        }
        self.inflight.clear();
        self.server_active = None;
        self.pending_owner = None;
        self.online.clear();
        self.welcomed = false;
    }

    /// 家を作ると押した。次に受ける最初の鎖が自分の genesis なら受け入れる。
    pub fn begin_create_household(&mut self) {
        if self.chain.is_some() {
            return;
        }
        self.creating_household = true;
        // 途中でやめた加入の待ちを残さない
        self.expected_join = None;
        self.persist();
    }

    /// 加入の待ちをやめた（時間切れ・取り下げ）。後でつながったときに、黙って家に入らない。
    pub fn cancel_join(&mut self) {
        if self.chain.is_some() || self.expected_join.is_none() {
            return;
        }
        self.expected_join = None;
        self.persist();
    }

    /// ログアウト・別のログインに変わった: 手元のキュー・再生・セッションを捨てる。
    pub fn clear_local(&mut self) {
        self.session = None;
        self.local_queue = None;
        self.local_queue_ref = None;
        self.local = None;
        self.last_own_cursor_hash = None;
        self.dirty_cursor = false;
        self.dirty_queue = false;
        self.inflight.clear();
        self.deferred_evidence.clear();
        self.start_waiting_for_tag = false;
        self.resume_pending = false;
        if let Some(waiting) = self.transfer.take() {
            self.send(RelayMessage::Release { claim_id: waiting.claim_id });
        }
    }

    /// 手元の再生が無くなった（ログインが変わった・キューが空になった）。書き手なら、final_tag で一時停止のカーソルを1回だけ送ってから手元を捨てる。
    pub fn end_local(&mut self, final_tag: Option<String>) {
        let ready = self.chain.is_some() && self.session.is_some() && self.local.is_some() && self.local_queue_ref.is_some() && self.pending_store_id.is_none();
        if let (true, Some(tag)) = (ready, final_tag)
            && let Some(mut body) = self.cursor_body_for(false, Some(tag))
        {
            body.playing = false;
            let expect = self.latest_cursor.as_ref().map_or(0, |c| c.version()).max(self.cursor_floor);
            let chain = self.chain.as_ref().expect("checked above");
            if let Some(doc) = seal_doc(&self.device, chain, DocKind::Cursor, expect + 1, self.last_own_cursor_hash.as_deref(), self.epoch(), &to_json(&body)) {
                let request_id = self.new_id();
                self.send(RelayMessage::StatePut { request_id, kind: StateKind::Cursor, expect_version: expect, doc });
            }
        }
        self.clear_local();
    }

    /// アカウントタグができた。タグが無い間に始めた再生が続いていれば claim する。
    pub fn on_account_tag_available(&mut self) {
        if !self.start_waiting_for_tag {
            return;
        }
        self.start_waiting_for_tag = false;
        if self.local.as_ref().is_some_and(|l| l.playing) && self.session.is_none() {
            self.user_started_playback();
        }
    }

    fn on_head_announced(&mut self, head_seq: i64, head_hash: Option<&str>) {
        match &self.chain {
            Some(current) if head_seq <= current.head_seq() => {
                if current.hash_at(head_seq) != head_hash {
                    self.effects.event(ClientEvent::ChainForked);
                }
            }
            _ => {
                self.send(RelayMessage::ChainGet { from_seq: self.records.len() as i64 });
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn on_welcome(
        &mut self,
        store_id: String,
        head_seq: Option<i64>,
        head_hash: Option<String>,
        active: Option<String>,
        online: Vec<String>,
        states: Vec<StoredState>,
        claim_orders: HashMap<String, i64>,
    ) {
        // 前の接続の書き込みは届いていないかもしれない（返事を待っていたものは積み直す）。transfer は捨てない
        self.reset_connection();
        self.welcomed = true;
        self.server_active = active;
        self.online = online.into_iter().collect();
        self.claim_orders.extend(claim_orders);
        match &self.store_id {
            None => {
                self.store_id = Some(store_id);
                self.persist();
            }
            Some(known) if *known != store_id => {
                self.pending_store_id = Some(store_id);
                self.effects.event(ClientEvent::StoreReset);
            }
            _ => {}
        }
        self.resume_pending = self.is_writer();
        if let Some(seq) = head_seq {
            self.on_head_announced(seq, head_hash.as_deref());
        }
        // 最新の状態を先に見る（通信を失ったまま鳴っていた端末は、ここで止まる）
        for state in states {
            self.on_state(state);
        }
        self.maybe_resume();
    }

    /// 本人が「この状態から始める」を押した（L-12）。床を捨てて新しい storeId を受け入れる。
    pub fn accept_store_reset(&mut self) {
        let Some(next) = self.pending_store_id.take() else { return };
        self.store_id = Some(next);
        self.cursor_floor = 0;
        self.queue_floor = 0;
        self.latest_cursor = None;
        self.latest_queue = None;
        self.persist();
        // 書き手なら、新しい保存に最初から書き直す
        if self.session.is_some() {
            if let Some(mine) = self.session.as_mut() {
                mine.arbiter = arbiter::Session { base: 0, start_version: None };
            }
            self.send_claim();
            self.mark_dirty();
        }
        self.send(RelayMessage::StateGet {});
        self.flush();
    }

    fn on_chain(&mut self, new_records: Vec<ChainRecord>) {
        // 取得が重なって同じ記録が2回届いても、分岐にしない（seq で並べ、既に持っている分はハッシュを比べる）
        let mut fresh: Vec<ChainRecord> = Vec::new();
        for record in new_records {
            if record.seq < 0 {
                self.effects.event(ClientEvent::ChainInvalid { reason: "seq".into() });
                return;
            }
            let index = record.seq as usize;
            if index < self.records.len() {
                if chain::hash(&record) != chain::hash(&self.records[index]) {
                    self.effects.event(ClientEvent::ChainForked);
                    return;
                }
            } else if index == self.records.len() + fresh.len() {
                fresh.push(record);
            } else {
                self.effects.event(ClientEvent::ChainInvalid { reason: "gap".into() });
                return;
            }
        }
        if fresh.is_empty() {
            self.flush();
            return;
        }
        let candidate: Vec<ChainRecord> = self.records.iter().cloned().chain(fresh).collect();
        let trusted = match (&self.chain, &self.expected_join) {
            (Some(c), _) => Some(Head { seq: c.head_seq(), hash: c.head_hash().to_string() }),
            (None, Some((info, _))) => Some(Head { seq: info.head_seq, hash: info.head_hash.clone() }),
            _ => None,
        };
        match chain::verify(&candidate, trusted.as_ref()) {
            ChainVerification::Ok(result) => {
                let joining = self.chain.is_none();
                if joining {
                    if let Some((info, inviter)) = self.expected_join.clone() {
                        // 加入: 家の ID が Confirm と同じで、頭の後に inviter が自分を加えた記録があること
                        if result.household_id() != info.household_id {
                            self.effects.event(ClientEvent::ChainForked);
                            return;
                        }
                        let me = self.my_id().to_string();
                        let added = candidate.iter().skip((info.head_seq + 1).max(0) as usize).any(|r| {
                            r.kind == ChainType::Add && r.target.as_ref().is_some_and(|t| t.device_id == me) && r.signer_id == inviter
                        }) || result.members().get(&me).is_some_and(|existing| {
                            existing.added_seq <= info.head_seq && hpke::serialize_public_key(&existing.kx) == hpke::serialize_public_key(self.device.kx_public())
                        });
                        // まだ加えられていない（招いた端末の追記の前に取った）: 受け入れずに次の chain_changed を待つ
                        if !added {
                            return;
                        }
                        self.expected_join = None;
                    } else if self.creating_household
                        && candidate[0].kind == ChainType::Genesis
                        && candidate[0].target.as_ref().is_some_and(|t| t.device_id == self.my_id())
                    {
                        // 家を作ると押した端末: 最初の記録が自分の genesis であること
                        self.creating_household = false;
                    } else {
                        // 加入も家を作るのも待っていない: 中継が勝手に作った家を信じない（D-12）
                        self.effects.event(ClientEvent::ChainInvalid { reason: "unexpected_household".into() });
                        return;
                    }
                }
                let head_changed = self.chain.as_ref().map(|c| c.head_hash()) != Some(result.head_hash());
                self.records = candidate;
                self.chain = Some(result);
                self.persist();
                // 書き手は、外した端末を宛先から外すために、次の書き込みを待たずに書き直す（D-2）
                if head_changed && self.is_writer() {
                    self.mark_dirty();
                }
                if joining {
                    self.effects.event(ClientEvent::Joined);
                }
                let pending = std::mem::take(&mut self.deferred);
                for state in pending {
                    self.on_state(state);
                }
                self.maybe_resume();
                self.flush();
            }
            ChainVerification::Fork => self.effects.event(ClientEvent::ChainForked),
            ChainVerification::Rollback => self.effects.event(ClientEvent::ChainRolledBack),
            ChainVerification::Invalid { reason, .. } => self.effects.event(ClientEvent::ChainInvalid { reason: reason.to_string() }),
        }
    }

    fn on_state(&mut self, stored: StoredState) {
        if self.pending_store_id.as_deref() == Some(stored.store_id.as_str()) {
            return;
        }
        if self.store_id.as_deref() != Some(stored.store_id.as_str()) {
            return;
        }
        let Some(current) = &self.chain else {
            self.deferred.push(stored);
            return;
        };
        // 開く前に、開いても捨てるものを除く（鍵交換の回数を減らす）
        if self.already_have(&stored) {
            return;
        }
        let opened = match doc::open(&stored.doc, current, &*self.device) {
            DocOpen::Ok { header, plaintext, .. } => (header, plaintext),
            DocOpen::NeedChain { .. } => {
                self.deferred.push(stored);
                self.send(RelayMessage::ChainGet { from_seq: self.records.len() as i64 });
                return;
            }
            DocOpen::Fork => {
                self.effects.event(ClientEvent::ChainForked);
                return;
            }
            _ => return,
        };
        let (header, plaintext) = opened;
        if header.version != stored.version || header.kind != stored.kind.doc_kind() {
            return;
        }
        let hash = doc::hash(&stored.doc);
        let now = self.now();
        match stored.kind {
            StateKind::Cursor => {
                if stored.version < self.cursor_floor {
                    return;
                }
                if let Some(existing) = &self.latest_cursor {
                    if existing.version() == stored.version {
                        if existing.hash != hash && existing.writer() == stored.doc.header.writer_id {
                            self.effects.event(ClientEvent::StateEquivocation);
                        }
                        return;
                    }
                    if existing.version() > stored.version {
                        return;
                    }
                }
                let Some(body) = from_json::<CursorBody>(&plaintext) else { return };
                self.cursor_floor = stored.version;
                self.latest_cursor = Some(Opened { stored, body, hash, received_at_local_ms: now });
                self.persist_floor();
                self.on_cursor();
            }
            StateKind::Queue => {
                if stored.version < self.queue_floor {
                    return;
                }
                if self.latest_queue.as_ref().map_or(-1, |q| q.version()) >= stored.version {
                    return;
                }
                let Some(body) = from_json::<QueueBody>(&plaintext) else { return };
                self.queue_floor = stored.version;
                self.latest_queue = Some(Opened { stored, body, hash, received_at_local_ms: now });
                self.persist_floor();
            }
        }
    }

    /// 開いても捨てるだけの状態か（同じ版で中身が違うものは、署名を確かめて食い違いを知らせるために開く）。
    fn already_have(&self, stored: &StoredState) -> bool {
        match stored.kind {
            StateKind::Cursor => {
                stored.version < self.cursor_floor
                    || self.latest_cursor.as_ref().is_some_and(|e| e.version() > stored.version || (e.version() == stored.version && e.hash == doc::hash(&stored.doc)))
            }
            StateKind::Queue => stored.version < self.queue_floor || self.latest_queue.as_ref().map_or(-1, |q| q.version()) >= stored.version,
        }
    }

    fn on_cursor(&mut self) {
        let Some(cursor) = &self.latest_cursor else { return };
        if cursor.writer() == self.my_id() {
            return;
        }
        let writer = cursor.writer().to_string();
        let account_matches = Some(&cursor.body.account_tag) == self.effects.account_tag().as_ref();
        let playing = cursor.body.playing;
        let evidence = Evidence::PlayingCursor { from: writer.clone(), session_base: cursor.body.session_base, session_start_version: cursor.body.session_start_version };
        self.effects.event(ClientEvent::RemoteChanged { writer: writer.clone(), account_matches });
        if self.transfer.as_ref().is_some_and(|t| !t.adopting && t.previous_writer == writer) && !playing {
            // 前の再生中の端末が最後のカーソル（一時停止）を置いた → すぐ取り込む
            self.complete_transfer();
            return;
        }
        if !account_matches || !playing {
            return;
        }
        self.decide(evidence);
    }

    fn on_yield(&mut self, claim_id: &str, yield_doc: &SealedDoc) {
        let Some(current) = &self.chain else { return };
        let DocOpen::Ok { header, plaintext, .. } = doc::open(yield_doc, current, &*self.device) else { return };
        let Some(CommandBody::Yield(YieldCommand { claim_id: command_claim, from, to, session_base, account_tag })) = from_json::<CommandBody>(&plaintext) else { return };
        if to != self.my_id() || from != header.writer_id || command_claim != claim_id {
            return;
        }
        // yield を出せるのは player だけ
        if !current.members().get(&from).is_some_and(|m| m.keys.role == Role::Player) {
            return;
        }
        if self.handled_claims.contains(&command_claim) {
            return;
        }
        self.remember(command_claim.clone());
        // 別のアカウントの端末の再生では止まらない（L-11）
        if Some(&account_tag) != self.effects.account_tag().as_ref() {
            return;
        }
        let was_writer = self.is_writer();
        let stopped = self.decide(Evidence::Yield { from: from.clone(), session_base });
        // 引き取り（transfer）なら、最後のカーソルを置く（相手はそれを待って取り込む）
        if stopped && was_writer && self.pending_owner.as_deref() == Some(from.as_str()) {
            self.send_yielded(command_claim);
        }
    }

    fn on_active_changed(&mut self, active: Option<String>, claim_id: Option<String>, claim_order: i64, pending_owner: Option<String>) {
        self.server_active = active.clone();
        self.pending_owner = pending_owner.clone();
        let claimer = pending_owner.clone().or(active.clone());
        if let (Some(claim_id), Some(claimer)) = (&claim_id, claimer) {
            self.claim_orders.insert(claimer, claim_order);
            if let Some(mine) = self.session.as_mut()
                && mine.claim_id.as_deref() == Some(claim_id.as_str())
            {
                mine.claim_order = Some(claim_order);
            }
        }
        // 順番が分かったので、保留していた証拠で決め直す
        if !self.deferred_evidence.is_empty() {
            let pending = std::mem::take(&mut self.deferred_evidence);
            for evidence in pending {
                if self.decide(evidence) {
                    break;
                }
            }
        }
        // 自分が再生中になったら、断られていた書き込みを送る
        let me = self.my_id().to_string();
        if active.as_deref() == Some(me.as_str()) || pending_owner.as_deref() == Some(me.as_str()) {
            self.flush();
        }
    }

    fn on_pending_expired(&mut self, claim_id: &str) {
        if self.transfer.as_ref().is_none_or(|t| t.claim_id != claim_id) {
            return;
        }
        let waiting = self.transfer.take().expect("checked above");
        if waiting.adopting && self.is_writer() {
            // 取り込んで鳴っているのに、最初のカーソルが期限に間に合わなかった: 新しく始めたものとして claim し直す
            self.restart_session();
        } else {
            self.effects.event(ClientEvent::TransferFailed { reason: "expired".into() });
        }
    }

    /// 書き手でなくなったか。
    fn decide(&mut self, evidence: Evidence) -> bool {
        let Some(mine) = &self.session else { return false };
        let other_order = self.claim_orders.get(evidence.from()).copied();
        let result = arbiter::decide(self.my_id(), mine.arbiter, &evidence, mine.claim_order, other_order);
        match result.decision {
            Decision::Keep => return false,
            Decision::Defer => {
                self.deferred_evidence.retain(|e| e.from() != evidence.from());
                self.deferred_evidence.push(evidence);
                return false;
            }
            Decision::Stop => {}
        }
        let was_playing = self.local.as_ref().is_some_and(|l| l.playing);
        self.end_session();
        // 止めた時点の位置を取る（引き取られるときの最後のカーソルを、最後の出来事の位置にしない）
        let now = self.now();
        self.local = self.effects.pause().or_else(|| {
            self.local.clone().map(|l| LocalCursor { playing: false, position_ms: current_position(&l, now), captured_at_ms: now, ..l })
        });
        // 一時停止中なら鳴らしていないので、書き手でなくなるだけ（知らせない）
        if was_playing {
            self.effects.event(ClientEvent::Stopped { by: evidence.from().to_string() });
        }
        true
    }

    fn end_session(&mut self) {
        self.session = None;
        self.retreated = true;
        self.deferred_evidence.clear();
        self.dirty_cursor = false;
        self.dirty_queue = false;
        self.persist();
    }

    fn send_yielded(&mut self, claim_id: String) {
        let Some(body) = self.cursor_body_for(false, None) else { return };
        let Some(chain) = &self.chain else { return };
        let expect = self.latest_cursor.as_ref().map_or(0, |c| c.version()).max(self.cursor_floor);
        let Some(doc) = seal_doc(&self.device, chain, DocKind::Cursor, expect + 1, self.last_own_cursor_hash.as_deref(), self.epoch(), &to_json(&body)) else { return };
        self.send(RelayMessage::Yielded { claim_id, expect_version: expect, doc });
    }

    fn on_state_ok(&mut self, request_id: &str, kind: StateKind, version: i64) {
        let Some(sent) = self.inflight.remove(request_id) else { return };
        let stored = StoredState {
            kind: sent.kind,
            version,
            doc: sent.doc,
            store_id: self.store_id.clone().unwrap_or_default(),
            received_at_server_ms: 0,
            server_now_ms: 0,
            active_device_id: self.server_active.clone(),
        };
        let now = self.now();
        match kind {
            StateKind::Cursor => {
                if let Some(body) = sent.cursor {
                    self.latest_cursor = Some(Opened { stored, body, hash: sent.hash, received_at_local_ms: now });
                }
                self.cursor_floor = self.cursor_floor.max(version);
                if let Some(mine) = self.session.as_mut()
                    && mine.id == sent.session_id
                    && mine.arbiter.start_version.is_none()
                {
                    mine.arbiter.start_version = Some(version);
                }
                if self.transfer.as_ref().is_some_and(|t| t.adopting) {
                    let adopted = self.transfer.take().expect("checked above");
                    self.effects.event(ClientEvent::TransferCompleted { from: adopted.previous_writer });
                }
            }
            StateKind::Queue => {
                if let Some(body) = sent.queue {
                    self.latest_queue = Some(Opened { stored, body, hash: sent.hash, received_at_local_ms: now });
                }
                self.queue_floor = self.queue_floor.max(version);
            }
        }
        self.persist();
        self.flush();
        self.maybe_hand_off();
    }

    fn on_state_conflict(&mut self, request_id: &str, latest: Option<StoredState>) {
        if self.transfer.as_ref().is_some_and(|t| t.claim_id == request_id) {
            let waiting = self.transfer.take().expect("checked above");
            if let Some(latest) = latest {
                self.on_state(latest);
            }
            if !waiting.retried {
                self.request_transfer_with(true);
            } else {
                self.effects.event(ClientEvent::TransferFailed { reason: "conflict".into() });
            }
            return;
        }
        let Some(sent) = self.inflight.get(request_id) else { return };
        // 返事が落ちて送り直した自分の書き込みと同じものが中継にあるなら、成功とみなす
        if let Some(l) = &latest
            && l.doc.header.writer_id == self.my_id()
            && doc::hash(&l.doc) == sent.hash
        {
            let (kind, version) = (sent.kind, l.version);
            self.on_state_ok(request_id, kind, version);
            return;
        }
        let kind = sent.kind;
        self.inflight.remove(request_id);
        if let Some(latest) = latest {
            self.on_state(latest);
        }
        // 止まっていなければ、最新の版に積み直す
        if self.is_writer() {
            self.mark_unsent(kind);
        }
        self.flush();
    }

    fn on_error(&mut self, code: &str, request_id: Option<String>) {
        let sent = request_id.as_ref().and_then(|id| self.inflight.remove(id));
        match code {
            // 書き込みを断られた: 持っている最新の証拠で止まる理由が無く、自分の claim が一番後（または誰も再生中でない）なら、すぐ claim し直す
            "not_active" => {
                if let Some(sent) = sent
                    && self.is_writer()
                {
                    self.mark_unsent(sent.kind);
                    let tag = self.effects.account_tag();
                    let evidence = self.latest_cursor.as_ref().filter(|l| l.writer() != self.my_id() && l.body.playing && Some(&l.body.account_tag) == tag.as_ref()).map(|l| {
                        Evidence::PlayingCursor { from: l.writer().to_string(), session_base: l.body.session_base, session_start_version: l.body.session_start_version }
                    });
                    let stopped = evidence.is_some_and(|e| self.decide(e));
                    let my_order = self.session.as_ref().and_then(|s| s.claim_order);
                    let me = self.my_id().to_string();
                    let latest_other = self.claim_orders.iter().filter(|(k, _)| **k != me).map(|(_, v)| *v).max();
                    let mine_is_latest = my_order.is_some_and(|m| latest_other.is_none_or(|o| m > o));
                    if !stopped && self.is_writer() && (self.server_active.is_none() || mine_is_latest) && my_order.is_some() {
                        self.restart_session();
                    }
                }
            }
            "stale_head" => {
                if let Some(sent) = sent
                    && self.is_writer()
                {
                    self.mark_unsent(sent.kind);
                }
                self.send(RelayMessage::ChainGet { from_seq: self.records.len() as i64 });
            }
            _ => {
                if request_id.is_some() && request_id.as_deref() == self.transfer.as_ref().map(|t| t.claim_id.as_str()) {
                    self.fail_transfer(code, None);
                } else {
                    self.effects.event(ClientEvent::ServerError { code: code.to_string() });
                }
            }
        }
        // 断られた書き込みは、ここで送り直さない（次の状態・active_changed・鎖の取得で送る）
    }

    // --- 手元の操作 ---

    /// 手元のキューが変わった（受け渡しの JSON）。書き手なら置く。
    pub fn set_local_queue(&mut self, snapshot: String) {
        if self.local_queue.as_deref() == Some(snapshot.as_str()) {
            return;
        }
        self.local_queue = Some(snapshot);
        if self.is_writer() {
            self.dirty_queue = true;
            // キューを置き直すので、カーソルも新しいキューを指し直す
            self.dirty_cursor = true;
            self.flush();
        }
    }

    /// 手元の再生の位置・再生中か等が変わった。書き手なら置く（間引きは呼ぶ側）。
    pub fn set_local_cursor(&mut self, cursor: LocalCursor) {
        self.local = Some(cursor);
        if self.is_writer() {
            self.dirty_cursor = true;
            self.flush();
        }
    }

    /// 本人が手元で新しく再生を始めた・再開した（L-3）。新しいセッションとして claim し、キューとカーソルを置く。
    /// claim を送ったか（家に入っていない・アカウントを確かめていない・家の再生が別のアカウントなら false。呼ぶ側は手元だけで再生する）。
    pub fn user_started_playback(&mut self) -> bool {
        if self.role != Role::Player || self.chain.is_none() || self.store_id.is_none() || self.pending_store_id.is_some() {
            return false;
        }
        let Some(tag) = self.effects.account_tag() else {
            self.start_waiting_for_tag = true;
            return false;
        };
        // 家の最新の再生が別のアカウントなら、claim しない
        if let Some(latest) = &self.latest_cursor
            && latest.writer() != self.my_id()
            && latest.body.account_tag != tag
        {
            return false;
        }
        self.retreated = false;
        self.restart_session();
        true
    }

    /// 新しいセッションを始めて claim する（手元で始めた・書き込みを断られた・取り込みが期限に遅れた）。
    fn restart_session(&mut self) {
        self.session_counter += 1;
        let base = self.latest_cursor.as_ref().map_or(0, |c| c.version()).max(self.cursor_floor);
        self.session = Some(Session { id: self.session_counter, arbiter: arbiter::Session { base, start_version: None }, claim_id: None, claim_order: None });
        self.deferred_evidence.clear();
        // 前のセッションの書き込みの返事は待たない（新しいセッションで積み直す）
        self.inflight.clear();
        self.persist();
        self.send_claim();
        self.mark_dirty();
        self.flush();
    }

    fn send_claim(&mut self) {
        let Some(chain) = &self.chain else { return };
        let Some(tag) = self.effects.account_tag() else { return };
        let Some(base) = self.session.as_ref().map(|s| s.arbiter.base) else { return };
        let claim_id = self.new_id();
        let to = self.latest_cursor.as_ref().map(|c| c.writer().to_string()).filter(|w| w != self.my_id()).unwrap_or_default();
        let command = CommandBody::Yield(YieldCommand { claim_id: claim_id.clone(), from: self.my_id().to_string(), to, session_base: base, account_tag: tag });
        let Some(yield_doc) = self.seal_command(chain, &command) else { return };
        self.remember(claim_id.clone());
        if let Some(mine) = self.session.as_mut() {
            mine.claim_id = Some(claim_id.clone());
            mine.claim_order = None;
        }
        self.send(RelayMessage::Claim { claim_id, yield_doc });
    }

    /// つながり直した後: 最新の状態と鎖がそろっていて、まだ書き手なら、claim し直して積み直す。
    fn maybe_resume(&mut self) {
        if !self.resume_pending || !self.deferred.is_empty() || self.chain.is_none() {
            return;
        }
        self.resume_pending = false;
        if self.session.is_none() {
            return;
        }
        if self.server_active.as_deref() != Some(self.my_id()) {
            // 切れている間に始めた再生（claim が届いていない）や、切れている間に再生中でなくなった場合
            self.send_claim();
        }
        self.mark_dirty();
        self.flush();
    }

    fn mark_unsent(&mut self, kind: StateKind) {
        match kind {
            StateKind::Cursor => self.dirty_cursor = true,
            StateKind::Queue => self.dirty_queue = true,
        }
    }

    fn mark_dirty(&mut self) {
        self.dirty_cursor = self.local.is_some();
        self.dirty_queue = self.local_queue.is_some();
    }

    /// 「ここで再生」（D-7）。最新のカーソルとキューを開けていて、アカウントが一致するときだけ送る。
    pub fn request_transfer(&mut self) -> bool {
        self.request_transfer_with(false)
    }

    /// 家の端末 target（wave API、ADR-0015）にログインを渡す。本人が押したときだけ呼ぶ。target の受け取りの鍵にだけ包む。
    /// 送ったか（target が家の player でない・自分・大きすぎるなら false）。
    pub fn send_login_grant(&mut self, target: &str, cookie: &str, visitor_data: Option<&str>, data_sync_id: Option<&str>, obtained_at_epoch_ms: i64) -> bool {
        if self.pending_store_id.is_some() || target == self.my_id() {
            return false;
        }
        let Some(chain) = &self.chain else { return false };
        if !chain.members().contains_key(self.my_id()) || !chain.members().get(target).is_some_and(|m| m.keys.role == Role::Player) {
            return false;
        }
        let grant = CommandBody::LoginGrant(LoginGrantCommand {
            command_id: self.new_id(),
            from: self.my_id().to_string(),
            to: target.to_string(),
            cookie: cookie.to_string(),
            visitor_data: visitor_data.map(String::from),
            data_sync_id: data_sync_id.map(String::from),
            obtained_at_epoch_ms,
        });
        let plaintext = to_json(&grant);
        if plaintext.len() > MAX_LOGIN_GRANT_PLAINTEXT {
            return false;
        }
        let only: HashSet<String> = [target.to_string()].into();
        let Ok(doc) = doc::seal(&*self.device, chain, DocKind::Command, 1, None, self.epoch(), &plaintext, Some(&only)) else { return false };
        self.send(RelayMessage::Control { doc })
    }

    /// 家の端末 target に「ここで再生（引き取り）」を頼む（デバイスの一覧で端末を選んだ）。
    pub fn request_remote_transfer(&mut self, target: &str) -> bool {
        if self.pending_store_id.is_some() || target == self.my_id() {
            return false;
        }
        let Some(chain) = &self.chain else { return false };
        if !chain.members().get(target).is_some_and(|m| m.keys.role == Role::Player) {
            return false;
        }
        let Some(tag) = self.effects.account_tag() else { return false };
        let Some(cursor) = self.latest_cursor.as_ref().filter(|c| c.body.account_tag == tag) else { return false };
        let command = CommandBody::Control(ControlCommand {
            command_id: self.new_id(),
            from: self.my_id().to_string(),
            to: target.to_string(),
            action: ControlAction::Transfer,
            position_ms: None,
            based_on_version: cursor.version(),
            account_tag: tag,
            on_item_id: None,
            volume_percent: None,
            shuffle: None,
            repeat: None,
        });
        let Some(doc) = self.seal_command(chain, &command) else { return false };
        self.send(RelayMessage::Control { doc })
    }

    /// 手元のキューで家の再生を引き受け（音は出さない）、それを書き終えたら target に引き取ってもらう。
    /// 呼ぶ前に set_local_queue・set_local_cursor（再生中でない）を置く。
    pub fn play_on(&mut self, target: &str) -> bool {
        if target == self.my_id() || !self.user_started_playback() {
            return false;
        }
        self.hand_off = Some((target.to_string(), self.now()));
        true
    }

    fn maybe_hand_off(&mut self) {
        let Some((target, _)) = &self.hand_off else { return };
        if self.session.is_none() {
            return;
        }
        let (Some(cursor), Some(queue)) = (&self.latest_cursor, &self.latest_queue) else { return };
        if cursor.writer() != self.my_id() || queue.writer() != self.my_id() || cursor.body.queue_ref != queue.hash {
            return;
        }
        let target = target.clone();
        self.hand_off = None;
        self.request_remote_transfer(&target);
    }

    /// この端末の表示名を家のほかの端末に知らせる。
    pub fn send_profile(&mut self, name: &str, kind: Option<&str>) -> bool {
        let Some(chain) = &self.chain else { return false };
        if !chain.members().contains_key(self.my_id()) {
            return false;
        }
        let name: String = name.chars().take(64).collect();
        let command = CommandBody::Profile(ProfileCommand { from: self.my_id().to_string(), name, kind: kind.map(String::from) });
        let Some(doc) = self.seal_command(chain, &command) else { return false };
        self.send(RelayMessage::Control { doc })
    }

    /// 遠隔操作の宛先（鳴っていない端末から、今鳴らしている同じアカウントの端末へ）。送れないなら None。
    fn control_target(&self) -> Option<(String, i64, String, String)> {
        if self.is_writer() || self.pending_store_id.is_some() || self.chain.is_none() {
            return None;
        }
        let tag = self.effects.account_tag()?;
        let cursor = self.latest_cursor.as_ref()?;
        if cursor.writer() == self.my_id() || cursor.body.account_tag != tag {
            return None;
        }
        Some((cursor.writer().to_string(), cursor.version(), tag, cursor.body.current_item_id.clone()))
    }

    /// 遠隔操作を送る（鳴っていない端末から、今鳴らしている同じアカウントの端末へ）。
    pub fn send_control(&mut self, action: ControlAction, position_ms: Option<i64>) -> bool {
        let Some((to, version, tag, current_item)) = self.control_target() else { return false };
        let on_item_id = matches!(action, ControlAction::Next | ControlAction::Previous | ControlAction::Seek).then_some(current_item);
        let command = CommandBody::Control(ControlCommand {
            command_id: self.new_id(),
            from: self.my_id().to_string(),
            to,
            action,
            position_ms: position_ms.map(|p| p.max(0)),
            based_on_version: version,
            account_tag: tag,
            on_item_id,
            volume_percent: None,
            shuffle: None,
            repeat: None,
        });
        let Some(doc) = self.seal_command(self.chain.as_ref().expect("checked in control_target"), &command) else { return false };
        self.send(RelayMessage::Control { doc })
    }

    /// 遠隔の音量を送る（その端末のアプリの中の音量を 0〜100 にする）。
    pub fn send_volume(&mut self, volume_percent: i32) -> bool {
        let Some((to, version, tag, _)) = self.control_target() else { return false };
        let command = CommandBody::Control(ControlCommand {
            command_id: self.new_id(),
            from: self.my_id().to_string(),
            to,
            action: ControlAction::Volume,
            position_ms: None,
            based_on_version: version,
            account_tag: tag,
            on_item_id: None,
            volume_percent: Some(volume_percent.clamp(0, 100)),
            shuffle: None,
            repeat: None,
        });
        let Some(doc) = self.seal_command(self.chain.as_ref().expect("checked in control_target"), &command) else { return false };
        self.send(RelayMessage::Control { doc })
    }

    /// 遠隔のキューへの追加を送る。1件の平文が詰め物の1ブロックに収まるよう分けて送り、2件目からは前の件の最後の曲の後ろに入れてもらう。
    pub fn send_queue_add(&mut self, items: &[HandoffItem], next: bool) -> bool {
        if items.is_empty() {
            return false;
        }
        let Some((to, version, tag, _)) = self.control_target() else { return false };
        let from = self.my_id().to_string();
        let command = |id: String, list: Vec<HandoffItem>, after: Option<String>| {
            CommandBody::QueueAdd(QueueAddCommand {
                command_id: id,
                from: from.clone(),
                to: to.clone(),
                items: list,
                next,
                after_item_id: after,
                based_on_version: version,
                account_tag: tag.clone(),
            })
        };
        // 大きさは、ID の欄を UUID の長さで埋めた形で量る（実際の ID は送るときに振る）
        const PLACEHOLDER: &str = "00000000-0000-0000-0000-000000000000";
        let size = |list: &[HandoffItem]| to_json(&command(PLACEHOLDER.into(), list.to_vec(), Some(PLACEHOLDER.into()))).len();
        let mut chunks: Vec<Vec<HandoffItem>> = Vec::new();
        let mut chunk: Vec<HandoffItem> = Vec::new();
        for item in items {
            if size(std::slice::from_ref(item)) > MAX_QUEUE_ADD_PLAINTEXT {
                return false;
            }
            let mut with = chunk.clone();
            with.push(item.clone());
            if !chunk.is_empty() && size(&with) > MAX_QUEUE_ADD_PLAINTEXT {
                chunks.push(std::mem::take(&mut chunk));
            }
            chunk.push(item.clone());
        }
        chunks.push(chunk);
        let mut after: Option<String> = None;
        for part in chunks {
            let last = part.last().map(|i| i.item_id.clone());
            let body = command(self.new_id(), part, after.clone());
            let Some(doc) = self.seal_command(self.chain.as_ref().expect("checked in control_target"), &body) else { return false };
            if !self.send(RelayMessage::Control { doc }) {
                return false;
            }
            after = last;
        }
        true
    }

    /// 遠隔のキューの並べ替えを送る（item_id の曲を after_item_id の曲の直後へ。無ければ今の曲の直後へ）。
    pub fn send_queue_move(&mut self, item_id: &str, after_item_id: Option<&str>) -> bool {
        self.send_queue_edit(QueueEditCommand::MOVE, vec![item_id.to_string()], after_item_id.map(String::from))
    }

    /// 遠隔のキューから外すのを送る。
    pub fn send_queue_remove(&mut self, item_ids: Vec<String>) -> bool {
        self.send_queue_edit(QueueEditCommand::REMOVE, item_ids, None)
    }

    fn send_queue_edit(&mut self, op: &str, item_ids: Vec<String>, after_item_id: Option<String>) -> bool {
        if item_ids.is_empty() {
            return false;
        }
        let Some((to, version, tag, _)) = self.control_target() else { return false };
        let command = CommandBody::QueueEdit(QueueEditCommand { command_id: self.new_id(), from: self.my_id().to_string(), to, op: op.to_string(), item_ids, after_item_id, based_on_version: version, account_tag: tag });
        if to_json(&command).len() > MAX_QUEUE_ADD_PLAINTEXT {
            return false;
        }
        let Some(doc) = self.seal_command(self.chain.as_ref().expect("checked in control_target"), &command) else { return false };
        self.send(RelayMessage::Control { doc })
    }

    /// 表示用: ほかの端末の最新のカーソル（別アカウントのものを含む）と、今の時刻に合わせた位置。
    pub fn remote_now_position(&self) -> Option<RemoteNow> {
        let cursor = self.latest_cursor.as_ref()?;
        if cursor.writer() == self.my_id() {
            return None;
        }
        Some(RemoteNow { writer: cursor.writer().to_string(), cursor: cursor.body.clone(), position_ms: self.extrapolated_position(cursor), version: cursor.version() })
    }

    /// 遠隔操作を受けた。自分宛て・送り主が署名した端末・処理していない ID・この端末が書き手・同じアカウント・
    /// 送り手が見ていた版がこの端末の最新の書き込みに近いときだけ従う。
    fn on_control(&mut self, doc_in: &SealedDoc) {
        let Some(current) = &self.chain else { return };
        if doc_in.header.kind != DocKind::Command {
            return;
        }
        // 中継が同じ命令を流し直しても、開く（鍵交換をする）前に捨てる
        let doc_hash = doc::hash(doc_in);
        if self.seen_control_docs.contains(&doc_hash) {
            return;
        }
        self.seen_control_docs.push_back(doc_hash);
        while self.seen_control_docs.len() > MAX_HANDLED_CLAIMS {
            self.seen_control_docs.pop_front();
        }
        let DocOpen::Ok { header, plaintext, .. } = doc::open(doc_in, current, &*self.device) else { return };
        let Some(body) = from_json::<CommandBody>(&plaintext) else { return };
        let me = self.my_id().to_string();
        match body {
            CommandBody::Profile(ProfileCommand { from, name, kind }) => {
                if from == header.writer_id && from != me && current.members().contains_key(&from) {
                    self.effects.event(ClientEvent::Profile { device_id: from, name, kind });
                }
            }
            CommandBody::QueueAdd(QueueAddCommand { command_id, from, to, items, next, after_item_id, based_on_version, account_tag }) => {
                if self.accepts_command(&header, &command_id, &from, &to, based_on_version, &account_tag) && !items.is_empty() {
                    self.effects.queue_add(&items, next, after_item_id.as_deref());
                }
            }
            CommandBody::QueueEdit(QueueEditCommand { command_id, from, to, op, item_ids, after_item_id, based_on_version, account_tag }) => {
                if self.accepts_command(&header, &command_id, &from, &to, based_on_version, &account_tag) && !item_ids.is_empty() {
                    match op.as_str() {
                        QueueEditCommand::MOVE if item_ids.len() == 1 => {
                            self.effects.queue_move(&item_ids[0], after_item_id.as_deref());
                        }
                        QueueEditCommand::REMOVE => {
                            self.effects.queue_remove(&item_ids);
                        }
                        QueueEditCommand::CLEAR_FLOW if item_ids.len() == 1 => {
                            self.effects.queue_clear_flow(&item_ids[0]);
                        }
                        _ => {}
                    }
                }
            }
            CommandBody::Control(ControlCommand { command_id, from, to, action, position_ms, based_on_version, account_tag, on_item_id, volume_percent, shuffle, repeat }) => {
                if to != me || from != header.writer_id || from == me {
                    return;
                }
                // 出してから5分を超えた命令には従わない
                if (self.epoch() - header.issued_at_epoch_ms).abs() > MAX_CONTROL_AGE_MS {
                    return;
                }
                if !current.members().get(&from).is_some_and(|m| m.keys.role == Role::Player) {
                    return;
                }
                if self.handled_claims.contains(&command_id) {
                    return;
                }
                self.remember(command_id);
                if Some(&account_tag) != self.effects.account_tag().as_ref() {
                    return;
                }
                if action == ControlAction::Transfer {
                    // 「ここで再生」を頼まれた: 書き手でなく、送り手が見ていた家の最新のカーソルが自分の見ているものに近いときだけ
                    let Some(latest) = &self.latest_cursor else { return };
                    if self.is_writer() || based_on_version > latest.version() || latest.version() - based_on_version > MAX_CONTROL_LAG {
                        return;
                    }
                    if self.effects.control(ControlAction::Transfer, None) {
                        self.request_transfer();
                    }
                    return;
                }
                if !self.within_session(based_on_version) {
                    return;
                }
                match action {
                    ControlAction::Seek if position_ms.is_none() => {}
                    ControlAction::Volume => {
                        if let Some(percent) = volume_percent.filter(|p| (0..=100).contains(p)) {
                            self.effects.volume(percent);
                        }
                    }
                    ControlAction::Shuffle => {
                        if let Some(on) = shuffle {
                            self.effects.shuffle(on);
                        }
                    }
                    ControlAction::Repeat => {
                        if let Some(mode) = repeat {
                            self.effects.repeat(mode);
                        }
                    }
                    _ => {
                        // 送り手が見ていた曲から、もう次の曲に変わっていたら従わない（遅れて届いた「次へ」で2曲飛ばさない）
                        let own_item = self.local.as_ref().map(|l| l.current_item_id.clone()).or_else(|| self.latest_cursor.as_ref().map(|c| c.body.current_item_id.clone()));
                        if on_item_id.is_some() && on_item_id != own_item {
                            return;
                        }
                        self.effects.control(action, position_ms);
                    }
                }
            }
            _ => {}
        }
    }

    /// 遠隔のキューの操作に従うか（遠隔操作と同じ条件）。
    fn accepts_command(&mut self, header: &DocHeader, command_id: &str, from: &str, to: &str, based_on_version: i64, account_tag: &str) -> bool {
        let me = self.my_id().to_string();
        if to != me || from != header.writer_id || from == me {
            return false;
        }
        if (self.epoch() - header.issued_at_epoch_ms).abs() > MAX_CONTROL_AGE_MS {
            return false;
        }
        let Some(current) = &self.chain else { return false };
        if !current.members().get(from).is_some_and(|m| m.keys.role == Role::Player) {
            return false;
        }
        if self.handled_claims.iter().any(|c| c == command_id) {
            return false;
        }
        self.remember(command_id.to_string());
        if Some(account_tag) != self.effects.account_tag().as_deref() {
            return false;
        }
        self.within_session(based_on_version)
    }

    /// 自分がセッションを持ち、送り手が見ていた版が今のセッションの最初の書き込み以上で、最新の書き込みから 20 版以内か。
    fn within_session(&self, based_on_version: i64) -> bool {
        let Some(mine) = &self.session else { return false };
        let Some(own) = self.latest_cursor.as_ref().filter(|c| c.writer() == self.my_id()) else { return false };
        if based_on_version > own.version() || own.version() - based_on_version > MAX_CONTROL_LAG {
            return false;
        }
        mine.arbiter.start_version.is_some_and(|start| based_on_version >= start)
    }

    fn request_transfer_with(&mut self, retried: bool) -> bool {
        if self.role != Role::Player || self.transfer.is_some() || self.pending_store_id.is_some() {
            return false;
        }
        let Some(chain) = &self.chain else { return false };
        let Some(tag) = self.effects.account_tag() else { return false };
        let (Some(cursor), Some(queue)) = (&self.latest_cursor, &self.latest_queue) else { return false };
        if cursor.writer() == self.my_id() || cursor.body.account_tag != tag || queue.body.account_tag != tag || cursor.body.queue_ref != queue.hash {
            return false;
        }
        let claim_id = self.new_id();
        let previous_writer = cursor.writer().to_string();
        let version = cursor.version();
        let command = CommandBody::Yield(YieldCommand { claim_id: claim_id.clone(), from: self.my_id().to_string(), to: previous_writer.clone(), session_base: version, account_tag: tag });
        let Some(yield_doc) = self.seal_command(chain, &command) else { return false };
        self.remember(claim_id.clone());
        self.transfer = Some(TransferState { claim_id: claim_id.clone(), previous_writer, started_at_ms: self.now(), retried, adopting: false });
        if !self.send(RelayMessage::Transfer { claim_id, based_on_version: version, yield_doc }) {
            self.transfer = None;
            return false;
        }
        true
    }

    /// 時間の経過。transfer で前の端末から返事が無ければ、最後の状態から取り込む。
    pub fn tick(&mut self) {
        let now = self.now();
        if self.floor_dirty && self.last_persist_ms.is_none_or(|last| now - last >= FLOOR_PERSIST_MS) {
            self.persist();
        }
        // 引き受けたものを書けないまま10秒たったら、頼むのをやめる
        if self.hand_off.as_ref().is_some_and(|(_, at)| now - at > HAND_OFF_WAIT_MS) {
            self.hand_off = None;
        }
        if self.transfer.as_ref().is_some_and(|t| !t.adopting && now - t.started_at_ms >= TRANSFER_WAIT_MS) {
            self.complete_transfer();
        }
    }

    fn complete_transfer(&mut self) {
        let Some(claim_id) = self.transfer.as_ref().map(|t| t.claim_id.clone()) else { return };
        let ready = match (&self.latest_cursor, &self.latest_queue) {
            (Some(c), Some(q)) => c.body.queue_ref == q.hash,
            _ => false,
        };
        if !ready {
            self.fail_transfer("no_state", Some(claim_id));
            return;
        }
        let cursor = self.latest_cursor.as_ref().expect("checked above");
        let queue = self.latest_queue.as_ref().expect("checked above");
        let position = self.extrapolated_position(cursor);
        let (snapshot, queue_hash, body, version) = (queue.body.snapshot.clone(), queue.hash.clone(), cursor.body.clone(), cursor.version());
        if let Some(t) = self.transfer.as_mut() {
            t.adopting = true;
        }
        if !self.effects.adopt(&snapshot, &body, position) {
            self.fail_transfer("not_adopted", Some(claim_id));
            return;
        }
        self.local_queue = Some(snapshot);
        self.local_queue_ref = Some(queue_hash);
        self.session_counter += 1;
        let order = self.claim_orders.get(self.my_id()).copied();
        self.session = Some(Session { id: self.session_counter, arbiter: arbiter::Session { base: version, start_version: None }, claim_id: Some(claim_id), claim_order: order });
        self.deferred_evidence.clear();
        self.retreated = false;
        let now = self.now();
        self.local = Some(LocalCursor {
            current_item_id: body.current_item_id.clone(),
            position_ms: position,
            playing: true,
            repeat: body.repeat,
            listened_ms: body.listened_ms,
            history_sent: body.history_sent,
            captured_at_ms: now,
            volume_percent: None,
            shuffle: Some(false),
        });
        self.persist();
        // 引き取った端末の最初のカーソルで再生中が確定する（D-7）。キューは前の端末の doc をそのまま指す
        self.dirty_cursor = true;
        self.flush();
    }

    /// 引き取りをやめる（取り下げるときは中継に Release を送る）。
    fn fail_transfer(&mut self, reason: &str, release: Option<String>) {
        self.transfer = None;
        if let Some(claim_id) = release {
            self.send(RelayMessage::Release { claim_id });
        }
        self.effects.event(ClientEvent::TransferFailed { reason: reason.to_string() });
    }

    /// 経過は中継の時計で測り、手元で受けてからの時間を足す。9分を超える経過は一時停止として扱う（D-5）。
    fn extrapolated_position(&self, cursor: &Opened<CursorBody>) -> i64 {
        if !cursor.body.playing {
            return cursor.body.position_ms;
        }
        let elapsed = (cursor.stored.server_now_ms - cursor.stored.received_at_server_ms) + (self.now() - cursor.received_at_local_ms);
        if !(0..=MAX_EXTRAPOLATION_MS).contains(&elapsed) {
            return cursor.body.position_ms;
        }
        cursor.body.position_ms + elapsed
    }

    // --- 書き込み ---

    fn flush(&mut self) {
        if self.chain.is_none() || self.session.is_none() || self.resume_pending {
            return;
        }
        let session_id = self.session.as_ref().map_or(0, |s| s.id);
        if self.dirty_queue
            && !self.inflight.values().any(|i| i.kind == StateKind::Queue)
            && let (Some(snapshot), Some(tag)) = (self.local_queue.clone(), self.effects.account_tag())
        {
            let body = QueueBody { account_tag: tag, snapshot };
            let expect = self.latest_queue.as_ref().map_or(0, |q| q.version()).max(self.queue_floor);
            let Some(doc) = seal_doc(&self.device, self.chain.as_ref().expect("checked above"), DocKind::Queue, expect + 1, None, self.epoch(), &to_json(&body)) else { return };
            let request_id = self.new_id();
            let hash = doc::hash(&doc);
            if self.send(RelayMessage::StatePut { request_id: request_id.clone(), kind: StateKind::Queue, expect_version: expect, doc: doc.clone() }) {
                self.dirty_queue = false;
                self.local_queue_ref = Some(hash.clone());
                self.inflight.insert(request_id, Inflight { kind: StateKind::Queue, doc, hash, cursor: None, queue: Some(body), session_id });
            }
        }
        // カーソルは、指すキューが置けてから書く（キューの送信中は待つ）
        if self.dirty_cursor && self.inflight.is_empty() && !self.dirty_queue {
            let Some(body) = self.cursor_body_for(true, None) else { return };
            let expect = self.latest_cursor.as_ref().map_or(0, |c| c.version()).max(self.cursor_floor);
            let Some(doc) = seal_doc(
                &self.device,
                self.chain.as_ref().expect("checked above"),
                DocKind::Cursor,
                expect + 1,
                self.last_own_cursor_hash.as_deref(),
                self.epoch(),
                &to_json(&body),
            ) else {
                return;
            };
            let hash = doc::hash(&doc);
            let request_id = self.new_id();
            if self.send(RelayMessage::StatePut { request_id: request_id.clone(), kind: StateKind::Cursor, expect_version: expect, doc: doc.clone() }) {
                self.dirty_cursor = false;
                self.last_own_cursor_hash = Some(hash.clone());
                self.inflight.insert(request_id, Inflight { kind: StateKind::Cursor, doc, hash, cursor: Some(body), queue: None, session_id });
            }
        }
    }

    /// with_session: 今のセッションの値を入れる（無ければ一時停止・セッション無しの値）。
    fn cursor_body_for(&mut self, with_session: bool, tag_override: Option<String>) -> Option<CursorBody> {
        let cursor = self.local.clone()?;
        let tag = tag_override.or_else(|| self.effects.account_tag())?;
        let queue_ref = self.local_queue_ref.clone()?;
        let mine = if with_session { self.session.as_ref() } else { None };
        self.device_seq += 1;
        Some(CursorBody {
            queue_ref,
            current_item_id: cursor.current_item_id.clone(),
            position_ms: current_position(&cursor, self.now()),
            playing: cursor.playing && mine.is_some(),
            repeat: cursor.repeat,
            session_base: mine.map_or(0, |m| m.arbiter.base),
            session_start_version: mine.and_then(|m| m.arbiter.start_version),
            device_seq: self.device_seq,
            listened_ms: cursor.listened_ms,
            history_sent: cursor.history_sent,
            account_tag: tag,
            started_at_epoch_ms: self.epoch(),
            volume_percent: cursor.volume_percent.map(|v| v.clamp(0, 100)),
            shuffle: cursor.shuffle,
        })
    }

    fn seal_command(&self, chain: &VerifiedChain, command: &CommandBody) -> Option<SealedDoc> {
        seal_doc(&self.device, chain, DocKind::Command, 1, None, self.epoch(), &to_json(command))
    }

    fn remember(&mut self, claim_id: String) {
        self.handled_claims.push_back(claim_id);
        while self.handled_claims.len() > MAX_HANDLED_CLAIMS {
            self.handled_claims.pop_front();
        }
    }

    fn persist_floor(&mut self) {
        if self.last_persist_ms.is_some_and(|last| self.now() - last < FLOOR_PERSIST_MS) {
            self.floor_dirty = true;
            return;
        }
        self.persist();
    }

    fn persist(&mut self) {
        self.floor_dirty = false;
        self.last_persist_ms = Some(self.now());
        let state = ClientPersisted {
            chain: self.records.clone(),
            store_id: self.store_id.clone(),
            cursor_floor: self.cursor_floor,
            queue_floor: self.queue_floor,
            retreated: self.retreated,
            handled_claim_ids: self.handled_claims.iter().cloned().collect(),
            expected_join: self.expected_join.as_ref().map(|(info, _)| info.clone()),
            expected_inviter: self.expected_join.as_ref().map(|(_, inviter)| inviter.clone()),
            creating_household: self.creating_household,
        };
        self.effects.persist(&state);
    }
}

/// 封じる。封じられなければ（この端末が一覧から外された・大きすぎる）None で、書かない。
fn seal_doc(device: &SoftwareDevice, chain: &VerifiedChain, kind: DocKind, version: i64, prev_hash: Option<&str>, issued_at: i64, plaintext: &[u8]) -> Option<SealedDoc> {
    doc::seal(device, chain, kind, version, prev_hash, issued_at, plaintext, None).ok()
}

/// 再生中なら、位置を取ってからの経過を足す（書くのが遅れても位置を戻さない）。
fn current_position(cursor: &LocalCursor, now: i64) -> i64 {
    if cursor.playing { cursor.position_ms + (now - cursor.captured_at_ms).clamp(0, MAX_EXTRAPOLATION_MS) } else { cursor.position_ms }
}

fn to_json<T: Serialize>(value: &T) -> Vec<u8> {
    connect_json::encode_to_utf8(value)
}

fn from_json<T: for<'de> Deserialize<'de>>(bytes: &[u8]) -> Option<T> {
    serde_json::from_slice(bytes).ok()
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::rc::Rc;

    use super::*;

    #[derive(Default)]
    struct Recorder {
        events: RefCell<Vec<ClientEvent>>,
    }

    struct TestEffects(Rc<Recorder>);

    impl Effects for TestEffects {
        fn pause(&self) -> Option<LocalCursor> {
            None
        }
        fn adopt(&self, _: &str, _: &CursorBody, _: i64) -> bool {
            false
        }
        fn event(&self, event: ClientEvent) {
            self.0.events.borrow_mut().push(event);
        }
        fn persist(&self, _: &ClientPersisted) {}
        fn account_tag(&self) -> Option<String> {
            Some("tag".into())
        }
    }

    fn client(device: Arc<SoftwareDevice>, initial: ClientPersisted, sent: Rc<RefCell<Vec<RelayMessage>>>, recorder: Rc<Recorder>) -> ConnectClient {
        let clock = Clock { now_ms: Box::new(|| 1_000), epoch_ms: Box::new(|| 1_700_000_000_000), new_id: Box::new(|| "id".into()) };
        ConnectClient::new(device, Role::Player, Box::new(move |m| {
            sent.borrow_mut().push(m);
            true
        }), Box::new(TestEffects(recorder)), clock, initial)
    }

    /// A が家を作り、B と C を加えた鎖。
    fn household() -> (Arc<SoftwareDevice>, Arc<SoftwareDevice>, Arc<SoftwareDevice>, Vec<ChainRecord>) {
        let (a, b, c) = (Arc::new(SoftwareDevice::generate()), Arc::new(SoftwareDevice::generate()), Arc::new(SoftwareDevice::generate()));
        let genesis = chain::genesis(&*a, &a.member_keys(Role::Player)).unwrap();
        let mut records = vec![genesis];
        for member in [&b, &c] {
            let ChainVerification::Ok(verified) = chain::verify(&records, None) else { panic!("chain") };
            records.push(chain::add(&*a, &verified, &member.member_keys(Role::Player)));
        }
        (a, b, c, records)
    }

    #[test]
    fn login_grant_is_wrapped_only_for_the_target() {
        let (a, b, c, records) = household();
        let sent = Rc::new(RefCell::new(Vec::new()));
        let initial = ClientPersisted { chain: records.clone(), store_id: Some("store".into()), ..Default::default() };
        let mut client = client(a.clone(), initial, sent.clone(), Rc::default());
        assert!(client.send_login_grant(b.device_id(), "SID=secret", None, Some("123"), 5));
        let Some(RelayMessage::Control { doc: sealed }) = sent.borrow().last().cloned() else { panic!("no control") };
        let ChainVerification::Ok(verified) = chain::verify(&records, None) else { panic!("chain") };
        let DocOpen::Ok { plaintext, .. } = doc::open(&sealed, &verified, &*b) else { panic!("target cannot open") };
        assert!(matches!(from_json::<CommandBody>(&plaintext), Some(CommandBody::LoginGrant(g)) if g.cookie == "SID=secret" && g.to == b.device_id()));
        assert!(!matches!(doc::open(&sealed, &verified, &*c), DocOpen::Ok { .. }), "others must not open it");
        // 自分宛てには送らない
        assert!(!client.send_login_grant(a.device_id(), "SID=secret", None, None, 5));
    }

    #[test]
    fn joining_accepts_only_a_chain_where_the_inviter_added_this_device() {
        let (a, b, _c, records) = household();
        let recorder = Rc::new(Recorder::default());
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut joiner = client(b.clone(), ClientPersisted::default(), sent, recorder.clone());
        // 加入を待っていない端末は、中継が見せた家を受け入れない
        joiner.on_message(RelayMessage::Chain { records: records.clone() });
        assert_eq!(joiner.household_id(), None);
        assert!(recorder.events.borrow().iter().any(|e| matches!(e, ClientEvent::ChainInvalid { .. })));
        // Confirm で受けた頭（genesis だけの時点）と、招いた端末 A
        let ChainVerification::Ok(head) = chain::verify(&records[..1], None) else { panic!("chain") };
        let info = JoinInfo {
            household_id: head.household_id().to_string(),
            head_seq: head.head_seq(),
            head_hash: head.head_hash().to_string(),
            store_id: "store".into(),
            cursor_floor: 0,
            queue_floor: 0,
        };
        joiner.expect_join(info, a.device_id().to_string());
        joiner.on_message(RelayMessage::Chain { records });
        assert_eq!(joiner.household_id(), Some(head.household_id()));
        assert!(recorder.events.borrow().contains(&ClientEvent::Joined));
    }
}
