//! 中継 v2 への接続（Windows の RelayConnection と同じ規則）。/v2/connect に WebSocket でつなぎ、challenge に身元鍵で署名して答える。
//! 受けたメッセージは JSON の文字列のまま events に流す（呼ぶ側が1本のスレッドで処理する）。切れたら間を空けてつなぎ直す。
//! ログは状態と種類だけ（中継の場所・端末 ID・本文は出さない）。

use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;

use super::crypto::{b64, length_prefixed};

/// 署名の用途ラベル（中継の認証）。
pub const AUTH_LABEL: &str = "wave-relay-v2-auth";
/// 1メッセージの上限（文字数。中継と同じ）。
pub const MAX_FRAME_CHARS: usize = 1_200_000;

const PAIR_TIMEOUT: Duration = Duration::from_secs(120);
const KEEP_ALIVE: Duration = Duration::from_secs(15);

/// 見張りの答え。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WatchAction {
    None,
    /// 確かめを送る。
    Probe,
    /// このソケットは死んでいる。切ってつなぎ直す。
    Dead,
}

/// 中継の接続の見張り（Android・Windows・Mac の RelayWatch と同じ規則。docs/decisions.md 2026-11-14）。
/// 死んだソケットを「つながっている」と思い込んだまま、ほかの端末の再生が届かないのを防ぐ。
/// つなぎ始めてから 15 秒のうちに welcome が来なければ切る。つながっている間、20 秒何も届かなければ確かめを送り、
/// 8 秒のうちに何も届かなければ切る。ネットにつながり直したら（wake）、すぐ確かめを送る。時刻は単調時計のミリ秒。
#[derive(Debug, Default)]
pub struct RelayWatch {
    opened_at: u64,
    welcomed: bool,
    last_received_at: u64,
    probe_sent_at: Option<u64>,
}

impl RelayWatch {
    pub const WELCOME_TIMEOUT_MS: u64 = 15_000;
    pub const IDLE_MS: u64 = 20_000;
    pub const PROBE_TIMEOUT_MS: u64 = 8_000;
    pub const TICK_MS: u64 = 1_000;
    /// 確かめに送るもの。鎖のずっと先を頼む（中継は空の鎖を返すだけで、どの版の中継も答える）。
    pub const PROBE: &'static str = r#"{"op":"chain_get","fromSeq":2147483647}"#;

    /// 新しいソケットでつなぎ始めた。
    pub fn opened(&mut self, now_ms: u64) {
        *self = Self { opened_at: now_ms, welcomed: false, last_received_at: now_ms, probe_sent_at: None };
    }

    /// 中継から何か届いた。
    pub fn received(&mut self, now_ms: u64, welcome: bool) {
        self.welcomed |= welcome;
        self.last_received_at = now_ms;
        self.probe_sent_at = None;
    }

    /// ネットにつながり直した・前面に戻った。つながっていれば、すぐ確かめる。
    pub fn wake(&mut self, now_ms: u64) -> WatchAction {
        if !self.welcomed || self.probe_sent_at.is_some() {
            return WatchAction::None;
        }
        self.probe_sent_at = Some(now_ms);
        WatchAction::Probe
    }

    /// 1秒ごとに呼ぶ。
    pub fn tick(&mut self, now_ms: u64) -> WatchAction {
        if !self.welcomed {
            return if now_ms.saturating_sub(self.opened_at) >= Self::WELCOME_TIMEOUT_MS { WatchAction::Dead } else { WatchAction::None };
        }
        if let Some(sent) = self.probe_sent_at {
            return if now_ms.saturating_sub(sent) >= Self::PROBE_TIMEOUT_MS { WatchAction::Dead } else { WatchAction::None };
        }
        if now_ms.saturating_sub(self.last_received_at) >= Self::IDLE_MS {
            self.probe_sent_at = Some(now_ms);
            return WatchAction::Probe;
        }
        WatchAction::None
    }

    /// つなぎ直すまでの待ち。failed_attempts は welcome を受けてから続けて失敗した数（つながっていたソケットが切れた直後は 0）。
    /// 1秒、2秒、4秒、8秒、16秒、以後30秒。
    pub fn reconnect_delay(failed_attempts: u32) -> Duration {
        if failed_attempts == 0 {
            Duration::from_secs(1)
        } else {
            Duration::from_millis((2_000u64 << (failed_attempts - 1).min(4)).min(30_000))
        }
    }
}

/// 見張りの単調時計（ミリ秒）。
fn now_ms() -> u64 {
    static START: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();
    START.get_or_init(std::time::Instant::now).elapsed().as_millis() as u64
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RelayStatus {
    Disconnected,
    Connecting,
    Connected,
    /// 名前を解決できない（Tailscale につながっていない）。
    TailnetUnreachable,
    /// 中継に届くが、この端末は家に入っていない（認証で断られた・外された）。
    NotMember,
    Failed,
}

/// 接続から呼ぶ側へ届くもの。
#[derive(Debug)]
pub enum SocketEvent {
    Status(RelayStatus),
    /// 中継からのメッセージ（JSON の文字列）。
    Text(String),
    /// つながっていた接続が切れた（返事を待っていた書き込みを積み直す）。
    Lost,
}

/// 身元鍵での用途ラベル付きの署名（DER）。
pub type Signer = Arc<dyn Fn(&str, &[u8]) -> Vec<u8> + Send + Sync>;

/// 中継 v2 の場所。URL は wss://ホスト:ポート、署名の対象に入るホストは URL の「ホスト:ポート」。
#[derive(Clone)]
pub struct RelayEndpoint {
    base: String,
    host: String,
}

impl std::fmt::Debug for RelayEndpoint {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // 場所はログに出さない
        f.write_str("RelayEndpoint(..)")
    }
}

impl RelayEndpoint {
    /// wss://（試すときは ws://localhost も）で、パス・問い合わせの無いものだけ。
    pub fn parse(text: &str) -> Option<Self> {
        let url = url::Url::parse(text.trim()).ok()?;
        let host = url.host_str()?;
        let local = matches!(host, "localhost" | "127.0.0.1" | "[::1]");
        if !(url.scheme() == "wss" || (url.scheme() == "ws" && local)) {
            return None;
        }
        if !matches!(url.path(), "" | "/") || url.query().is_some() || url.fragment().is_some() || !url.username().is_empty() {
            return None;
        }
        let authority = match url.port() {
            Some(port) => format!("{host}:{port}"),
            None => host.to_string(),
        };
        Some(Self { base: format!("{}://{authority}", url.scheme()), host: authority })
    }

    pub fn host(&self) -> &str {
        &self.host
    }
}

struct Inner {
    outbox: Option<mpsc::UnboundedSender<String>>,
    status: RelayStatus,
    /// つなぎたいか（stop で false）。
    wanted: bool,
    /// 接続の世代（古い接続の後始末で新しい接続を壊さない）。
    epoch: u64,
    /// 待っているつなぎ直しを起こす。
    wake: Option<tokio::sync::oneshot::Sender<()>>,
    /// 今のソケットの見張り。
    watch: RelayWatch,
    /// 今のソケットへ確かめを送る口（つなぎ始めから。outbox は welcome の後だけ）。
    probe: Option<mpsc::UnboundedSender<String>>,
}

/// 中継への接続。tokio の上で動く（呼ぶのは tokio のランタイムの中から）。
#[derive(Clone)]
pub struct RelaySocket {
    endpoint: RelayEndpoint,
    identity_pub: Vec<u8>,
    sign: Signer,
    events: mpsc::UnboundedSender<SocketEvent>,
    inner: Arc<Mutex<Inner>>,
}

impl RelaySocket {
    pub fn new(endpoint: RelayEndpoint, identity_pub: Vec<u8>, sign: Signer, events: mpsc::UnboundedSender<SocketEvent>) -> Self {
        let inner = Inner { outbox: None, status: RelayStatus::Disconnected, wanted: false, epoch: 0, wake: None, watch: RelayWatch::default(), probe: None };
        Self { endpoint, identity_pub, sign, events, inner: Arc::new(Mutex::new(inner)) }
    }

    pub fn status(&self) -> RelayStatus {
        self.inner.lock().unwrap().status
    }

    /// つなぐ（つながっていれば何もしない）。切れたら自分でつなぎ直す。
    pub fn start(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.wanted = true;
        if inner.outbox.is_none() && inner.status != RelayStatus::Connecting {
            self.open(&mut inner, 0);
        }
    }

    pub fn stop(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.wanted = false;
        inner.epoch += 1;
        inner.outbox = None;
        inner.wake = None;
        self.set_status(&mut inner, RelayStatus::Disconnected);
    }

    /// 断られた（家に入っていなかった）後に、つなぎ直す（加入の後）。
    pub fn reconnect(&self) {
        let mut inner = self.inner.lock().unwrap();
        if !inner.wanted || inner.outbox.is_some() || inner.status == RelayStatus::Connecting {
            return;
        }
        match inner.wake.take() {
            Some(wake) => {
                let _ = wake.send(());
            }
            None => self.open(&mut inner, 0),
        }
    }

    /// ネットにつながり直した・窓が前に出た: 待っているつなぎ直しをすぐ行い、つながっていれば生きているかをすぐ確かめる
    /// （スリープの間に死んだソケットや、伸びたつなぎ直しの待ちで、ほかの端末の再生が届かないままにしない）。
    pub fn wake(&self) {
        let mut inner = self.inner.lock().unwrap();
        if !inner.wanted || inner.status == RelayStatus::Connecting {
            return;
        }
        if inner.outbox.is_none() {
            // 待っているつなぎ直しを、1回目の待ちから数え直してすぐ行う
            inner.epoch += 1;
            inner.wake = None;
            self.open(&mut inner, 0);
            return;
        }
        if inner.status == RelayStatus::Connected && inner.watch.wake(now_ms()) == WatchAction::Probe {
            if let Some(probe) = inner.probe.as_ref() {
                let _ = probe.send(RelayWatch::PROBE.to_string());
            }
        }
    }

    /// つないでいてもつなぎ直す（家の記録を消した後に、新しい家の welcome を受け取る）。
    pub fn restart(&self) {
        let mut inner = self.inner.lock().unwrap();
        if !inner.wanted {
            return;
        }
        inner.epoch += 1;
        let was_open = inner.outbox.take().is_some();
        inner.wake = None;
        if was_open {
            let _ = self.events.send(SocketEvent::Lost);
        }
        self.open(&mut inner, 0);
    }

    /// つながっていれば送る（呼んだ順に送る）。送れなければ false。
    pub fn send(&self, json: String) -> bool {
        let inner = self.inner.lock().unwrap();
        inner.status == RelayStatus::Connected && inner.outbox.as_ref().is_some_and(|tx| tx.send(json).is_ok())
    }

    /// 家に入っていない端末から、加入の第1手を送って返事を待つ（/v2/pair、認証なし）。届かなければ None。
    pub async fn pair(&self, pair_hello_in: String) -> Option<String> {
        let url = format!("{}/v2/pair", self.endpoint.base);
        let attempt = async {
            let (mut ws, _) = tokio_tungstenite::connect_async(url.as_str()).await.ok()?;
            ws.send(Message::text(pair_hello_in)).await.ok()?;
            while let Some(message) = ws.next().await {
                match message.ok()? {
                    Message::Text(text) if text.len() <= 64 * 1024 => {
                        let _ = ws.close(None).await;
                        return Some(text.to_string());
                    }
                    Message::Close(_) => return None,
                    _ => {}
                }
            }
            None
        };
        match tokio::time::timeout(PAIR_TIMEOUT, attempt).await {
            Ok(reply) => {
                if reply.is_none() {
                    eprintln!("relay-v2 pair failed");
                }
                reply
            }
            Err(_) => {
                eprintln!("relay-v2 pair timed out");
                None
            }
        }
    }

    fn set_status(&self, inner: &mut Inner, next: RelayStatus) {
        if inner.status != next {
            inner.status = next;
            let _ = self.events.send(SocketEvent::Status(next));
        }
    }

    fn open(&self, inner: &mut Inner, failed_attempts: u32) {
        inner.epoch += 1;
        let epoch = inner.epoch;
        inner.watch.opened(now_ms());
        inner.probe = None;
        self.set_status(inner, RelayStatus::Connecting);
        let this = self.clone();
        tokio::spawn(async move {
            let next = this.run(epoch).await;
            this.on_closed(epoch, next, failed_attempts);
        });
    }

    async fn run(&self, epoch: u64) -> RelayStatus {
        let url = format!("{}/v2/connect", self.endpoint.base);
        // つながらない宛先で TCP の時間切れ（2分ほど）まで待たない
        let connecting = tokio::time::timeout(Duration::from_millis(RelayWatch::WELCOME_TIMEOUT_MS), tokio_tungstenite::connect_async(url.as_str()));
        let Ok(connected) = connecting.await else {
            eprintln!("relay-v2 connect timed out");
            return RelayStatus::Failed;
        };
        let mut ws = match connected {
            Ok((ws, _)) => ws,
            Err(e) => {
                let unreachable = matches!(&e, tokio_tungstenite::tungstenite::Error::Io(io) if io.kind() == std::io::ErrorKind::NotFound)
                    || e.to_string().contains("failed to lookup address");
                eprintln!("relay-v2 connect failed unreachable={unreachable}");
                return if unreachable { RelayStatus::TailnetUnreachable } else { RelayStatus::Failed };
            }
        };
        let (tx, mut outbox) = mpsc::unbounded_channel::<String>();
        {
            let mut inner = self.inner.lock().unwrap();
            if inner.epoch == epoch {
                inner.probe = Some(tx.clone());
            }
        }
        let mut keep_alive = tokio::time::interval(KEEP_ALIVE);
        keep_alive.tick().await;
        let mut watch_tick = tokio::time::interval(Duration::from_millis(RelayWatch::TICK_MS));
        watch_tick.tick().await;
        let mut connected = false;
        loop {
            if self.inner.lock().unwrap().epoch != epoch {
                let _ = ws.close(None).await;
                return RelayStatus::Disconnected;
            }
            tokio::select! {
                incoming = ws.next() => {
                    let message = match incoming {
                        Some(Ok(message)) => message,
                        Some(Err(_)) | None => return RelayStatus::Failed,
                    };
                    if !matches!(message, Message::Text(_)) {
                        // pong なども生きている証（welcome かどうかは on_text で見る）
                        let mut inner = self.inner.lock().unwrap();
                        if inner.epoch == epoch {
                            inner.watch.received(now_ms(), false);
                        }
                    }
                    match message {
                        Message::Text(text) => {
                            if text.len() > MAX_FRAME_CHARS * 4 {
                                let _ = ws.close(None).await;
                                return RelayStatus::Failed;
                            }
                            if let Some(reply) = self.on_text(epoch, &text, &tx, &mut connected)
                                && ws.send(Message::text(reply)).await.is_err()
                            {
                                return RelayStatus::Failed;
                            }
                        }
                        Message::Close(frame) => {
                            let reason = frame.as_ref().map(|f| f.reason.to_string()).unwrap_or_default();
                            let refused = (!connected && reason == "auth") || reason == "removed";
                            return if refused { RelayStatus::NotMember } else { RelayStatus::Disconnected };
                        }
                        _ => {}
                    }
                }
                Some(text) = outbox.recv() => {
                    if ws.send(Message::text(text)).await.is_err() {
                        return RelayStatus::Failed;
                    }
                }
                _ = keep_alive.tick() => {
                    if ws.send(Message::Ping(Vec::new().into())).await.is_err() {
                        return RelayStatus::Failed;
                    }
                }
                _ = watch_tick.tick() => {
                    let action = {
                        let mut inner = self.inner.lock().unwrap();
                        if inner.epoch != epoch { WatchAction::None } else { inner.watch.tick(now_ms()) }
                    };
                    match action {
                        WatchAction::None => {}
                        WatchAction::Probe => {
                            if ws.send(Message::text(RelayWatch::PROBE)).await.is_err() {
                                return RelayStatus::Failed;
                            }
                        }
                        WatchAction::Dead => {
                            eprintln!("relay-v2 unresponsive");
                            return RelayStatus::Failed;
                        }
                    }
                }
            }
        }
    }

    /// 受けた1メッセージ。challenge なら返す auth を返す（それ以外は呼ぶ側へ流す）。
    fn on_text(&self, epoch: u64, text: &str, tx: &mpsc::UnboundedSender<String>, connected: &mut bool) -> Option<String> {
        let node: serde_json::Value = serde_json::from_str(text).ok()?;
        let op = node.get("op").and_then(|v| v.as_str());
        if op == Some("challenge") {
            let nonce = b64::decode(node.get("nonce")?.as_str()?)?;
            // 中継が名乗るホストではなく、設定のホストで署名する
            let signature = (self.sign)(AUTH_LABEL, &length_prefixed(&[self.endpoint.host.as_bytes(), &nonce]));
            let auth = serde_json::json!({ "op": "auth", "publicKey": b64::encode(&self.identity_pub), "signature": b64::encode(&signature) });
            return Some(auth.to_string());
        }
        let mut inner = self.inner.lock().unwrap();
        if inner.epoch != epoch {
            return None;
        }
        inner.watch.received(now_ms(), op == Some("welcome"));
        if op == Some("welcome") && !*connected {
            *connected = true;
            inner.outbox = Some(tx.clone());
            self.set_status(&mut inner, RelayStatus::Connected);
            eprintln!("relay-v2 connected");
        }
        let _ = self.events.send(SocketEvent::Text(text.to_string()));
        None
    }

    fn on_closed(&self, epoch: u64, next: RelayStatus, failed_attempts: u32) {
        let mut inner = self.inner.lock().unwrap();
        if inner.epoch != epoch {
            return;
        }
        let was_open = inner.outbox.take().is_some();
        inner.probe = None;
        self.set_status(&mut inner, next);
        if was_open {
            let _ = self.events.send(SocketEvent::Lost);
        }
        if !inner.wanted || next == RelayStatus::NotMember {
            return;
        }
        // つながっていたなら、すぐ（1秒後）から数え直す
        let attempts = if was_open { 0 } else { failed_attempts };
        let wait = RelayWatch::reconnect_delay(attempts);
        let (wake, woken) = tokio::sync::oneshot::channel();
        inner.wake = Some(wake);
        let this = self.clone();
        tokio::spawn(async move {
            let _ = tokio::time::timeout(wait, woken).await;
            let mut inner = this.inner.lock().unwrap();
            if inner.epoch != epoch || !inner.wanted || inner.outbox.is_some() {
                return;
            }
            inner.wake = None;
            this.open(&mut inner, attempts + 1);
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_socket_without_welcome_is_given_up_after_fifteen_seconds() {
        let mut watch = RelayWatch::default();
        watch.opened(0);
        assert_eq!(WatchAction::None, watch.tick(14_999));
        // challenge だけ届いても welcome でなければ待ち続けない
        watch.received(5_000, false);
        assert_eq!(WatchAction::Dead, watch.tick(15_000));
    }

    #[test]
    fn an_idle_socket_is_probed_and_dropped_when_nothing_comes_back() {
        let mut watch = RelayWatch::default();
        watch.opened(0);
        watch.received(1_000, true);
        assert_eq!(WatchAction::None, watch.tick(20_999));
        assert_eq!(WatchAction::Probe, watch.tick(21_000));
        assert_eq!(WatchAction::None, watch.tick(28_999));
        assert_eq!(WatchAction::Dead, watch.tick(29_000));
    }

    #[test]
    fn any_reply_keeps_the_socket() {
        let mut watch = RelayWatch::default();
        watch.opened(0);
        watch.received(0, true);
        assert_eq!(WatchAction::Probe, watch.tick(20_000));
        watch.received(20_300, false);
        assert_eq!(WatchAction::None, watch.tick(29_000));
        assert_eq!(WatchAction::Probe, watch.tick(40_300));
    }

    #[test]
    fn waking_probes_at_once_only_when_connected() {
        let mut watch = RelayWatch::default();
        watch.opened(0);
        assert_eq!(WatchAction::None, watch.wake(100));
        watch.received(200, true);
        assert_eq!(WatchAction::Probe, watch.wake(300));
        assert_eq!(WatchAction::None, watch.wake(400));
        assert_eq!(WatchAction::Dead, watch.tick(8_300));
    }

    #[test]
    fn reconnect_waits_start_at_one_second_and_stop_at_thirty() {
        let waits: Vec<u64> = (0..7).map(|i| RelayWatch::reconnect_delay(i).as_millis() as u64).collect();
        assert_eq!(vec![1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000], waits);
    }

    #[test]
    fn the_probe_is_a_chain_request_the_relay_answers_with_nothing() {
        let probe: serde_json::Value = serde_json::from_str(RelayWatch::PROBE).unwrap();
        assert_eq!(Some("chain_get"), probe["op"].as_str());
        assert_eq!(Some(2_147_483_647), probe["fromSeq"].as_i64());
    }

    #[test]
    fn endpoint_takes_host_and_port_from_the_url() {
        let e = RelayEndpoint::parse("wss://relay.example.ts.net:8443").unwrap();
        assert_eq!(e.host(), "relay.example.ts.net:8443");
        assert_eq!(e.base, "wss://relay.example.ts.net:8443");
        assert_eq!(RelayEndpoint::parse("wss://relay.example.ts.net/").unwrap().host(), "relay.example.ts.net");
    }

    #[test]
    fn endpoint_rejects_plain_text_paths_and_credentials() {
        assert!(RelayEndpoint::parse("ws://relay.example.ts.net:8443").is_none());
        assert!(RelayEndpoint::parse("wss://relay.example.ts.net/v2").is_none());
        assert!(RelayEndpoint::parse("wss://a:b@relay.example.ts.net").is_none());
        assert!(RelayEndpoint::parse("https://relay.example.ts.net").is_none());
        assert!(RelayEndpoint::parse("ws://localhost:18080").is_some());
    }
}
