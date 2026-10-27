//! ライブラリ（RSS で登録した番組・聴いたところ）を、中継の家の端末の間で合わせる（ADR-0013。Kotlin・C# の LibrarySync と同じ動き）。
//! 中継の最新の文書を開いて手元と合わせ、手元にしか無い・手元のほうが新しいものがあれば、版の CAS で書く。
//! 書き込みは1本ずつ。食い違ったら（ほかの端末が先に書いた）、届いた最新と合わせて書き直す。
//! 呼ぶ側は接続の処理と同じ1本の流れから呼ぶ（スレッドをまたがない）。
//! 中継のメッセージ（RelayV2）には依らない。送るものは LibraryRequest、届いたものは LibraryReply に移して渡す。

use super::chain::{DeviceSigner, VerifiedChain};
use super::doc::{self, DocOpen, KxOpener};
use super::models::{DocKind, LibraryBody, LibraryState, SealedDoc, connect_json};

/// 中継へ送るもの（RelayV2 の library_get・library_put）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LibraryRequest {
    Get,
    Put { request_id: String, expect_version: i64, doc: Box<SealedDoc> },
}

/// 中継から届いたもの（RelayV2 の library・library_ok・library_conflict）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LibraryReply {
    /// library_get の返事と、ほかの端末が書いたときの配信。まだ無ければ stored は None。
    Library { stored: Option<LibraryState> },
    Ok { request_id: String, version: i64 },
    Conflict { request_id: String, latest: Option<LibraryState> },
}

/// LibrarySync が呼ぶ側に頼むこと（Windows 版ではコンストラクタの関数で受けていたもの）。
pub trait LibraryHost {
    fn signer(&self) -> &dyn DeviceSigner;
    fn opener(&self) -> &dyn KxOpener;
    /// 中継へ送る。送れたら true。
    fn send(&mut self, request: LibraryRequest) -> bool;
    /// 検証した今の一覧（無ければ書かない・開かない）。
    fn chain(&self) -> Option<&VerifiedChain>;
    /// 中継の保存の ID（受け入れたもの。作り直しの確認待ちなら None）。違う保存の文書は取り込まない。
    fn store_id(&self) -> Option<&str>;
    fn epoch_ms(&self) -> i64;
    fn new_id(&mut self) -> String;
    /// 中継から合わせた結果が届いた（手元の状態に取り込む）。
    fn on_remote(&mut self, body: LibraryBody);
}

#[derive(Debug, Default)]
pub struct LibrarySync {
    /// 中継の最新の版と中身（開けたもの）と、その保存の ID。
    remote_version: i64,
    remote_body: Option<LibraryBody>,
    remote_hash: Option<String>,
    remote_store: Option<String>,
    /// 手元の今の中身（書きたいもの）。
    local: Option<LibraryBody>,
    /// 返事を待っている書き込みと、その中身（返事が来たら中継の中身とする）。
    inflight: Option<String>,
    pending_body: Option<LibraryBody>,
    /// 接続して library_get の返事を受けたか（受けるまで書かない。中継の版を知らずに 0 で書くと食い違う）。
    synced: bool,
    /// 開けなかった文書（一覧が古い）。一覧が届いたら開き直す。
    deferred: Option<LibraryState>,
}

impl LibrarySync {
    pub fn new() -> Self {
        Self::default()
    }

    /// つながった（welcome の後）。最新を頼む。古い中継は知らない命令として error を返すだけ（synced のまま false なので書かない）。
    pub fn on_connected(&mut self, host: &mut dyn LibraryHost) {
        self.synced = false;
        self.inflight = None;
        host.send(LibraryRequest::Get);
    }

    pub fn on_disconnected(&mut self) {
        self.synced = false;
        self.inflight = None;
    }

    /// 一覧が新しくなった。開けずにいた文書を開き直す。
    pub fn on_chain_updated(&mut self, host: &mut dyn LibraryHost) {
        let Some(waiting) = self.deferred.take() else {
            return;
        };
        self.adopt(waiting, host);
        self.flush(host);
    }

    /// 手元が変わった。中継のものと合わせて、違えば書く。
    pub fn publish(&mut self, body: LibraryBody, host: &mut dyn LibraryHost) {
        self.local = Some(body);
        self.flush(host);
    }

    pub fn on_message(&mut self, message: LibraryReply, host: &mut dyn LibraryHost) {
        match message {
            LibraryReply::Library { stored } => {
                self.synced = true;
                match stored {
                    Some(state) => self.adopt(state, host),
                    None => self.forget_remote(host),
                }
                self.flush(host);
            }
            LibraryReply::Ok { request_id, version } => {
                if self.inflight.as_deref() != Some(request_id.as_str()) {
                    return;
                }
                self.inflight = None;
                self.remote_version = version;
                self.remote_body = self.pending_body.take();
                self.remote_hash = None;
                self.remote_store = host.store_id().map(String::from);
                self.flush(host);
            }
            LibraryReply::Conflict { request_id, latest } => {
                if self.inflight.as_deref() != Some(request_id.as_str()) {
                    return;
                }
                self.inflight = None;
                self.pending_body = None;
                match latest {
                    Some(state) => self.adopt(state, host),
                    None => self.forget_remote(host),
                }
                self.flush(host);
            }
        }
    }

    /// 中継にまだ文書が無い（作り直された中継など）。覚えていた版を捨てて 0 から書く
    /// （覚えた版のまま書くと、食い違いが返り続けて書き込みが回り続ける）。
    fn forget_remote(&mut self, host: &dyn LibraryHost) {
        if host.store_id().is_none() {
            return;
        }
        self.remote_version = 0;
        self.remote_body = None;
        self.remote_hash = None;
        self.remote_store = None;
    }

    /// 中継の文書を開いて取り込む。古い版・違う保存・開けないものは捨てる。
    fn adopt(&mut self, state: LibraryState, host: &mut dyn LibraryHost) {
        if host.store_id() != Some(state.store_id.as_str()) {
            return;
        }
        // 覚えている版が別の保存のもの（中継が作り直された）なら、版の大小では捨てない
        let same_store = self.remote_store.as_deref() == Some(state.store_id.as_str());
        if same_store && state.version < self.remote_version {
            return;
        }
        let hash = doc::hash(&state.doc);
        if same_store && state.version == self.remote_version && self.remote_hash.as_deref() == Some(hash.as_str()) {
            return;
        }
        let Some(chain) = host.chain() else {
            self.deferred = Some(state);
            return;
        };
        let (header, plaintext) = match doc::open(&state.doc, chain, host.opener()) {
            DocOpen::Ok { header, plaintext, .. } => (header, plaintext),
            DocOpen::NeedChain { .. } => {
                self.deferred = Some(state);
                return;
            }
            _ => return,
        };
        if header.kind != DocKind::Library || header.version != state.version {
            return;
        }
        let Some(body) = connect_json::decode_utf8::<LibraryBody>(&plaintext) else {
            return;
        };
        self.remote_version = state.version;
        self.remote_hash = Some(hash);
        self.remote_store = Some(state.store_id);
        // 手元と合わせたものを取り込んでもらう（手元のほうが新しい分は、次の flush で書く）
        let merged = match &self.local {
            Some(local) => local.merge(&body),
            None => body.clone(),
        };
        self.remote_body = Some(body);
        self.local = Some(merged.clone());
        host.on_remote(merged);
    }

    /// 手元に中継より新しいものがあれば書く。
    fn flush(&mut self, host: &mut dyn LibraryHost) {
        if !self.synced || self.inflight.is_some() || host.store_id().is_none() {
            return;
        }
        let Some(mine) = &self.local else {
            return;
        };
        // 同じ時刻なら中継の側を残す
        let merged = match &self.remote_body {
            Some(remote) => mine.merge(remote),
            None => mine.clone(),
        };
        match &self.remote_body {
            Some(remote) if merged.same_as(remote) => return,
            None if merged.feeds.is_empty() && merged.positions.is_empty() => return,
            _ => {}
        }
        let plaintext = connect_json::encode_to_utf8(&merged);
        if plaintext.len() > doc::MAX_PLAINTEXT {
            return;
        }
        let Some(chain) = host.chain() else {
            return;
        };
        if !chain.members().contains_key(host.signer().device_id()) {
            return;
        }
        let sealed = doc::seal(host.signer(), chain, DocKind::Library, self.remote_version + 1, None, host.epoch_ms(), &plaintext, None);
        let Ok(sealed) = sealed else {
            return;
        };
        let request_id = host.new_id();
        if host.send(LibraryRequest::Put { request_id: request_id.clone(), expect_version: self.remote_version, doc: Box::new(sealed) }) {
            self.inflight = Some(request_id);
            self.pending_body = Some(merged.clone());
            self.local = Some(merged);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::collections::VecDeque;
    use std::rc::Rc;

    use super::super::chain;
    use super::super::models::{LibraryFeed, Role};
    use super::super::test_support::{TestDevice, verified};
    use super::*;

    /// 版の CAS だけをする中継（library のところだけ）。
    #[derive(Default)]
    struct Relay {
        stored: Option<LibraryState>,
        /// 端末ごとの受け箱。
        inboxes: Vec<VecDeque<LibraryReply>>,
        puts: usize,
    }

    impl Relay {
        fn handle(&mut self, from: usize, request: LibraryRequest) {
            match request {
                LibraryRequest::Get => {
                    let stored = self.stored.clone();
                    self.inboxes[from].push_back(LibraryReply::Library { stored });
                }
                LibraryRequest::Put { request_id, expect_version, doc } => {
                    self.puts += 1;
                    let current = self.stored.as_ref().map_or(0, |s| s.version);
                    if current != expect_version {
                        let latest = self.stored.clone();
                        self.inboxes[from].push_back(LibraryReply::Conflict { request_id, latest });
                        return;
                    }
                    let state = LibraryState { version: current + 1, doc: *doc, store_id: "store-1".into() };
                    self.stored = Some(state.clone());
                    self.inboxes[from].push_back(LibraryReply::Ok { request_id, version: current + 1 });
                    for (i, inbox) in self.inboxes.iter_mut().enumerate() {
                        if i != from {
                            inbox.push_back(LibraryReply::Library { stored: Some(state.clone()) });
                        }
                    }
                }
            }
        }
    }

    struct Host {
        index: usize,
        device: TestDevice,
        chain: VerifiedChain,
        relay: Rc<RefCell<Relay>>,
        /// 送ったもの（中継へは run で渡す。送った順に届く）。
        outbox: Vec<LibraryRequest>,
        received: Vec<LibraryBody>,
        ids: usize,
    }

    impl LibraryHost for Host {
        fn signer(&self) -> &dyn DeviceSigner {
            &self.device
        }

        fn opener(&self) -> &dyn KxOpener {
            &self.device
        }

        fn send(&mut self, request: LibraryRequest) -> bool {
            self.outbox.push(request);
            true
        }

        fn chain(&self) -> Option<&VerifiedChain> {
            Some(&self.chain)
        }

        fn store_id(&self) -> Option<&str> {
            Some("store-1")
        }

        fn epoch_ms(&self) -> i64 {
            1
        }

        fn new_id(&mut self) -> String {
            self.ids += 1;
            format!("req-{}-{}", self.index, self.ids)
        }

        fn on_remote(&mut self, body: LibraryBody) {
            self.received.push(body);
        }
    }

    /// 送ったもの・届いたものが無くなるまで回す。
    fn run(syncs: &mut [LibrarySync], hosts: &mut [Host]) {
        loop {
            let mut moved = false;
            for i in 0..hosts.len() {
                for request in std::mem::take(&mut hosts[i].outbox) {
                    hosts[i].relay.borrow_mut().handle(i, request);
                    moved = true;
                }
                let reply = hosts[i].relay.borrow_mut().inboxes[i].pop_front();
                if let Some(reply) = reply {
                    syncs[i].on_message(reply, &mut hosts[i]);
                    moved = true;
                }
            }
            if !moved {
                return;
            }
        }
    }

    fn feed(title: &str, at: i64) -> LibraryFeed {
        LibraryFeed { title: title.into(), author: None, thumbnail_url: None, added_at_ms: at, removed: false, updated_at_ms: at }
    }

    fn body(entries: &[(&str, LibraryFeed)]) -> LibraryBody {
        LibraryBody { feeds: entries.iter().map(|(k, v)| (k.to_string(), v.clone())).collect(), ..Default::default() }
    }

    fn setup() -> (Vec<LibrarySync>, Vec<Host>, Rc<RefCell<Relay>>) {
        let a = TestDevice::new(Role::Player);
        let pc = TestDevice::new(Role::Controller);
        let genesis = chain::genesis(&a, &a.keys).unwrap();
        let add = chain::add(&a, &verified(std::slice::from_ref(&genesis)), &pc.keys);
        let chain = verified(&[genesis, add]);
        let relay = Rc::new(RefCell::new(Relay { inboxes: vec![VecDeque::new(), VecDeque::new()], ..Default::default() }));
        let host = |index, device| Host { index, device, chain: chain.clone(), relay: relay.clone(), outbox: vec![], received: vec![], ids: 0 };
        let mut hosts = vec![host(0, a), host(1, pc)];
        let mut syncs = vec![LibrarySync::new(), LibrarySync::new()];
        for (sync, host) in syncs.iter_mut().zip(hosts.iter_mut()) {
            sync.on_connected(host);
        }
        run(&mut syncs, &mut hosts);
        (syncs, hosts, relay)
    }

    #[test]
    fn subscription_reaches_the_other_device_and_controllers_can_write() {
        let (mut syncs, mut hosts, relay) = setup();
        // 操作だけの端末（PC）も書ける
        syncs[1].publish(body(&[("https://feeds.example/a", feed("A", 10))]), &mut hosts[1]);
        run(&mut syncs, &mut hosts);
        assert_eq!(Some("A"), hosts[0].received.last().and_then(|b| b.feeds.get("https://feeds.example/a")).map(|f| f.title.as_str()));
        assert_eq!(1, relay.borrow().stored.as_ref().unwrap().version);
        // 同じ中身は書き直さない
        let puts = relay.borrow().puts;
        syncs[0].publish(hosts[0].received.last().unwrap().clone(), &mut hosts[0]);
        run(&mut syncs, &mut hosts);
        assert_eq!(puts, relay.borrow().puts);
    }

    #[test]
    fn concurrent_writes_are_merged_after_conflict() {
        let (mut syncs, mut hosts, relay) = setup();
        // 互いの書き込みを見ずに書く（届く前に両方が送る）
        syncs[0].publish(body(&[("https://feeds.example/a", feed("A", 10))]), &mut hosts[0]);
        syncs[1].publish(body(&[("https://feeds.example/b", feed("B", 20))]), &mut hosts[1]);
        run(&mut syncs, &mut hosts);
        let stored = relay.borrow().stored.clone().unwrap();
        let DocOpen::Ok { plaintext, .. } = doc::open(&stored.doc, &hosts[0].chain, &hosts[0].device) else { panic!("ok") };
        let merged: LibraryBody = connect_json::decode_utf8(&plaintext).unwrap();
        assert_eq!(2, merged.feeds.len());
        assert_eq!(2, stored.version);
        assert!(hosts[0].received.last().unwrap().same_as(&merged));
        assert!(hosts[1].received.last().unwrap().same_as(&merged));
    }

    #[test]
    fn nothing_is_written_before_the_relay_answers() {
        let mut sync = LibrarySync::new();
        let a = TestDevice::new(Role::Player);
        let chain = verified(&[chain::genesis(&a, &a.keys).unwrap()]);
        let relay = Rc::new(RefCell::new(Relay { inboxes: vec![VecDeque::new()], ..Default::default() }));
        let mut host = Host { index: 0, device: a, chain, relay, outbox: vec![], received: vec![], ids: 0 };
        sync.publish(body(&[("https://feeds.example/a", feed("A", 10))]), &mut host);
        assert!(host.outbox.is_empty());
        sync.on_connected(&mut host);
        assert_eq!(vec![LibraryRequest::Get], host.outbox);
    }
}
