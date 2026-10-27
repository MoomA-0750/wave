//! ペアリングの手順（Android 版・Windows 版の Pairing と同じ、ADR-0008）。通信に依らない純粋なロジック。A = QR を出す端末、B = QR を読む端末。
//! 1. A は使い捨ての鍵 eA を作り、QR `{v, pkA, eA, token}` を出す
//! 2. B は使い捨ての鍵 eB を作り、ECDH(eB, eA) から導いた鍵で `{pkB, sigB(transcript), 端末名, アカウントの HMAC}` を封じて、eB と一緒に A へ送る
//! 3. A は開いて sigB を確かめ、`{sigA(transcript), 端末名, アカウントの HMAC}` を封じて返す
//! 4. B は sigA を確かめ、確認用の6桁を表示する。A は本人がその6桁を入力し、一致したときだけペアを確定する
//!
//! JSON は Kotlin の Pairing の Json（ignoreUnknownKeys のみ。既定値の項目は出さない）と同じ形。

use std::sync::Arc;

use p256::SecretKey;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use super::chain::DeviceSigner;
use super::crypto::{self, b64, hkdf};
use super::snapshot::is_iso_control;

pub const VERSION: i32 = 1;
pub const TOKEN_BYTES: usize = 16;
pub const LIFETIME_MS: i64 = 2 * 60 * 1000;
pub const MAX_ATTEMPTS: u32 = 3;
const MAX_EXTENSION_CHARS: usize = 2048;
/// ログインをまだ持たない加わる側（wave-api、ADR-0015）が、アカウントの HMAC に使う値（Android の Pairing.ACCOUNTLESS_ID と同じ）。
/// 誰でも計算できる印で、「アカウントを照らし合わせられない」ことを A に伝えるだけ（照合は誤操作の防止で防御ではない。守りは6桁の照合）。
pub const ACCOUNTLESS_ID: &str = "wave-accountless-v1";

const LABEL: &str = "wave-pair-v1";
const OFFER_PREFIX: &str = "wave-pair:";

/// QR の中身。token はペアリングの間の秘密なので Debug に出さない。
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Offer {
    pub v: i32,
    #[serde(rename = "pkA")]
    pub pk_a: String,
    #[serde(rename = "eA")]
    pub e_a: String,
    pub token: String,
}

impl std::fmt::Debug for Offer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Offer {{ v: {}, pk_a: {}, e_a: {} }}", self.v, self.pk_a, self.e_a)
    }
}

/// B → A（中継の /pair 経由）。eB は平文（A が鍵を導くのに要る）、残りは封じる。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Hello {
    pub v: i32,
    #[serde(rename = "eB")]
    pub e_b: String,
    pub nonce: String,
    pub ciphertext: String,
}

/// A → B。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Confirm {
    pub nonce: String,
    pub ciphertext: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HelloBody {
    #[serde(rename = "pkB")]
    pk_b: String,
    #[serde(rename = "sigB")]
    sig_b: String,
    device_name: String,
    account_hmac: String,
    /// Connect v2: B の家の鍵の記述（MemberKeys の JSON）。sigB の対象に入る。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    extension: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ConfirmBody {
    #[serde(rename = "sigA")]
    sig_a: String,
    device_name: String,
    account_hmac: String,
    /// Connect v2: 家の ID・一覧の頭・storeId・版の床（秘密を入れない）。sigA の対象に入る。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    extension: Option<String>,
}

/// 確定したペア。鍵は Debug に出さない。
/// - `peer_extension`: 相手の拡張（A から見た B の Hello、B から見た A の Confirm）。署名で確かめた値
/// - `peer_accountless`: A から見て、B がログインをまだ持たない（ACCOUNTLESS_ID で照合した）。加えた後にログインを渡す相手
#[derive(Clone, PartialEq, Eq)]
pub struct Established {
    pub peer_public_key: Vec<u8>,
    pub peer_device_name: String,
    pub send_key: Vec<u8>,
    pub receive_key: Vec<u8>,
    pub account_key: Vec<u8>,
    pub peer_extension: Option<String>,
    pub peer_accountless: bool,
}

impl std::fmt::Debug for Established {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "Established {{ peer: {}, peer_device_name: {:?}, peer_extension: {}, peer_accountless: {} }}",
            crypto::device_id(&self.peer_public_key),
            self.peer_device_name,
            self.peer_extension.is_some(),
            self.peer_accountless
        )
    }
}

/// B 側の結果: 表示する6桁と、確定したときに保存するペア。
#[derive(Clone, PartialEq, Eq)]
pub struct Confirmed {
    pub code: String,
    pub pair: Established,
}

impl std::fmt::Debug for Confirmed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Confirmed {{ code: ******, pair: {:?} }}", self.pair)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Failure {
    Malformed,
    BadSignature,
    Expired,
    WrongCode,
    TooManyAttempts,
    /// 別のアカウントでログインした端末。
    AccountMismatch,
    AlreadyUsed,
}

pub fn encode_offer(offer: &Offer) -> String {
    OFFER_PREFIX.to_string() + &b64::encode(&serde_json::to_vec(offer).expect("Offer は常に JSON にできる"))
}

pub fn decode_offer(text: &str) -> Option<Offer> {
    if !text.starts_with(OFFER_PREFIX) || text.encode_utf16().count() > 1024 {
        return None;
    }
    let bytes = b64::decode(&text[OFFER_PREFIX.len()..])?;
    let offer: Offer = parse(&bytes)?;
    if offer.v != VERSION {
        return None;
    }
    crypto::decode_public_key(&b64::decode(&offer.pk_a)?)?;
    crypto::decode_public_key(&b64::decode(&offer.e_a)?)?;
    if b64::decode(&offer.token)?.len() != TOKEN_BYTES {
        return None;
    }
    Some(offer)
}

/// 中継のメッセージに入っていた Hello を読む（知らない項目は読み飛ばす）。
pub fn decode_hello(value: &serde_json::Value) -> Option<Hello> {
    Hello::deserialize(value).ok()
}

pub fn decode_confirm(value: &serde_json::Value) -> Option<Confirm> {
    Confirm::deserialize(value).ok()
}

pub fn hello_to_json(hello: &Hello) -> serde_json::Value {
    serde_json::to_value(hello).expect("Hello は常に JSON にできる")
}

pub fn confirm_to_json(confirm: &Confirm) -> serde_json::Value {
    serde_json::to_value(confirm).expect("Confirm は常に JSON にできる")
}

/// A 側。QR を出してから、1回の B の Hello と、6桁の入力を扱う。スレッドを跨ぐなら呼ぶ側が Mutex に入れる。
pub struct Responder {
    identity: Arc<dyn DeviceSigner + Send + Sync>,
    device_name: String,
    account_id: String,
    now_ms: Box<dyn Fn() -> i64 + Send + Sync>,
    confirm_account_id: String,
    confirm_extension: Option<String>,
    pk_a: Vec<u8>,
    ephemeral: SecretKey,
    e_a: Vec<u8>,
    token: Vec<u8>,
    offer: Offer,
    token_hash: Vec<u8>,
    created_at_ms: i64,
    used: bool,
    attempts: u32,
    pending: Option<(String, Established)>,
}

/// 秘密（使い捨ての鍵・token・確定前のペア）は出さない。
impl std::fmt::Debug for Responder {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Responder {{ device: {}, used: {}, attempts: {} }}", self.identity.device_id(), self.used, self.attempts)
    }
}

impl Responder {
    /// - `identity`: 身元の署名鍵
    /// - `account_id`: アカウントの識別子（正規化した DATASYNC_ID）。HMAC にだけ使う
    /// - `confirm_extension`: Connect v2: Confirm に入れる拡張（秘密を入れない。JoinInfo の JSON）
    pub fn new(
        identity: Arc<dyn DeviceSigner + Send + Sync>,
        device_name: &str,
        account_id: &str,
        now_ms: Box<dyn Fn() -> i64 + Send + Sync>,
        confirm_extension: Option<String>,
    ) -> Self {
        Self::with_ephemeral(identity, device_name, account_id, now_ms, account_id, confirm_extension, crypto::generate_key(), crypto::random_bytes(TOKEN_BYTES))
    }

    /// 使い捨ての鍵と token を外から与える・Confirm に入れるアカウントを変える（テスト・相互運用の確かめ用）。
    #[allow(clippy::too_many_arguments)]
    pub fn with_ephemeral(
        identity: Arc<dyn DeviceSigner + Send + Sync>,
        device_name: &str,
        account_id: &str,
        now_ms: Box<dyn Fn() -> i64 + Send + Sync>,
        confirm_account_id: &str,
        confirm_extension: Option<String>,
        ephemeral: SecretKey,
        token: Vec<u8>,
    ) -> Self {
        let pk_a = identity.identity_pub().to_vec();
        let e_a = crypto::encode_public_key(&ephemeral);
        let created_at_ms = now_ms();
        let offer = Offer { v: VERSION, pk_a: b64::encode(&pk_a), e_a: b64::encode(&e_a), token: b64::encode(&token) };
        let token_hash = crypto::sha256(&[&token]);
        Self {
            identity,
            device_name: device_name.to_string(),
            account_id: account_id.to_string(),
            now_ms,
            confirm_account_id: confirm_account_id.to_string(),
            confirm_extension,
            pk_a,
            ephemeral,
            e_a,
            token,
            offer,
            token_hash,
            created_at_ms,
            used: false,
            attempts: 0,
            pending: None,
        }
    }

    pub fn token(&self) -> &[u8] {
        &self.token
    }

    pub fn offer(&self) -> &Offer {
        &self.offer
    }

    /// 中継に預ける値（token そのものは渡さない）。
    pub fn token_hash(&self) -> &[u8] {
        &self.token_hash
    }

    /// B の Hello を受けて Confirm を返す。token は最初の正しい Hello で消費する。
    pub fn on_hello(&mut self, hello: &Hello) -> Result<Confirm, Failure> {
        if self.expired() {
            return Err(Failure::Expired);
        }
        if self.used {
            return Err(Failure::AlreadyUsed);
        }
        if hello.v != VERSION {
            return Err(Failure::Malformed);
        }
        let e_b_bytes = b64::decode(&hello.e_b).ok_or(Failure::Malformed)?;
        let e_b = crypto::decode_public_key(&e_b_bytes).ok_or(Failure::Malformed)?;
        let shared = crypto::ecdh(&self.ephemeral, &e_b);
        let transcript0 = crypto::length_prefixed(&[LABEL.as_bytes(), &self.pk_a, &self.e_a, &self.token, &e_b_bytes]);
        let prk = hkdf::extract(&self.token, &shared);
        let nonce = b64::decode(&hello.nonce).ok_or(Failure::Malformed)?;
        let ciphertext = b64::decode(&hello.ciphertext).ok_or(Failure::Malformed)?;
        let plain = crypto::open(&handshake_key(&prk, "b->a", &transcript0), &nonce, &transcript0, &ciphertext).ok_or(Failure::Malformed)?;
        let body: HelloBody = parse(&plain).ok_or(Failure::Malformed)?;
        let pk_b_bytes = b64::decode(&body.pk_b).ok_or(Failure::Malformed)?;
        let pk_b = crypto::decode_public_key(&pk_b_bytes).ok_or(Failure::Malformed)?;
        // 自分自身とのペアは受けない（方向の鍵が崩れる）
        if pk_b_bytes == self.pk_a {
            return Err(Failure::Malformed);
        }
        let transcript = crypto::length_prefixed(&[&transcript0, &pk_b_bytes]);
        if body.extension.as_deref().is_some_and(|e| e.encode_utf16().count() > MAX_EXTENSION_CHARS) {
            return Err(Failure::Malformed);
        }
        let sig_b = b64::decode(&body.sig_b).ok_or(Failure::Malformed)?;
        if !crypto::verify(&pk_b, &format!("{LABEL}-b"), &signed_transcript(&transcript, body.extension.as_deref()), &sig_b) {
            return Err(Failure::BadSignature);
        }
        let keys = schedule(&prk, &transcript, &self.pk_a, &pk_b_bytes);
        let peer_hmac = b64::decode(&body.account_hmac).unwrap_or_default();
        let accountless = if crypto::constant_time_equals(&account_hmac(&keys.account_key, &self.account_id), &peer_hmac) {
            false
        } else if crypto::constant_time_equals(&account_hmac(&keys.account_key, ACCOUNTLESS_ID), &peer_hmac) {
            // ログインをまだ持たない B（wave-api）は印で照合する（ADR-0015）
            true
        } else {
            return Err(Failure::AccountMismatch);
        };
        self.used = true;
        let confirm = ConfirmBody {
            sig_a: b64::encode(&self.identity.sign(&format!("{LABEL}-a"), &signed_transcript(&transcript, self.confirm_extension.as_deref()))),
            device_name: self.device_name.clone(),
            account_hmac: b64::encode(&account_hmac(&keys.account_key, &self.confirm_account_id)),
            extension: self.confirm_extension.clone(),
        };
        let established = Established {
            peer_public_key: pk_b_bytes,
            peer_device_name: sanitize_name(&body.device_name),
            send_key: keys.send_key,
            receive_key: keys.receive_key,
            account_key: keys.account_key,
            peer_extension: body.extension,
            peer_accountless: accountless,
        };
        self.pending = Some((keys.sas, established));
        let (seal_nonce, sealed) =
            crypto::seal(&handshake_key(&prk, "a->b", &transcript0), &transcript0, &serde_json::to_vec(&confirm).expect("ConfirmBody は常に JSON にできる"));
        Ok(Confirm { nonce: b64::encode(&seal_nonce), ciphertext: b64::encode(&sealed) })
    }

    /// 本人が入力した6桁（B の画面の数字）。一致したらペアを確定する。3回まで。
    pub fn on_code_entered(&mut self, code: &str) -> Result<Established, Failure> {
        if self.expired() {
            return Err(Failure::Expired);
        }
        if self.attempts >= MAX_ATTEMPTS {
            return Err(Failure::TooManyAttempts);
        }
        let Some((sas, _)) = &self.pending else {
            return Err(Failure::Malformed);
        };
        self.attempts += 1;
        if !crypto::constant_time_equals(code.trim().as_bytes(), sas.as_bytes()) {
            if self.attempts >= MAX_ATTEMPTS {
                self.pending = None;
                return Err(Failure::TooManyAttempts);
            }
            return Err(Failure::WrongCode);
        }
        let (_, established) = self.pending.take().expect("上で確かめた");
        Ok(established)
    }

    fn expired(&self) -> bool {
        (self.now_ms)() - self.created_at_ms > LIFETIME_MS
    }
}

/// B 側。QR を読んで Hello を作り、A の Confirm で6桁とペアを得る。
pub struct Initiator {
    identity: Arc<dyn DeviceSigner + Send + Sync>,
    device_name: String,
    account_id: String,
    hello_extension: Option<String>,
    pk_b: Vec<u8>,
    e_b: Vec<u8>,
    pk_a: Vec<u8>,
    transcript0: Vec<u8>,
    transcript: Vec<u8>,
    prk: Vec<u8>,
    keys: Keys,
}

/// 秘密（導いた鍵）は出さない。
impl std::fmt::Debug for Initiator {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Initiator {{ device: {}, peer: {} }}", self.identity.device_id(), crypto::device_id(&self.pk_a))
    }
}

impl Initiator {
    /// - `offer`: decode_offer を通したもの（通していなければ Err(Malformed)）
    /// - `hello_extension`: Connect v2: Hello に入れる拡張（B の家の鍵の記述、MemberKeys の JSON）
    pub fn new(
        offer: &Offer,
        identity: Arc<dyn DeviceSigner + Send + Sync>,
        device_name: &str,
        account_id: &str,
        hello_extension: Option<String>,
    ) -> Result<Self, Failure> {
        Self::with_ephemeral(offer, identity, device_name, account_id, hello_extension, crypto::generate_key())
    }

    /// 使い捨ての鍵を外から与える（テスト・相互運用の確かめ用）。
    pub fn with_ephemeral(
        offer: &Offer,
        identity: Arc<dyn DeviceSigner + Send + Sync>,
        device_name: &str,
        account_id: &str,
        hello_extension: Option<String>,
        ephemeral: SecretKey,
    ) -> Result<Self, Failure> {
        let pk_b = identity.identity_pub().to_vec();
        let e_b = crypto::encode_public_key(&ephemeral);
        let pk_a = b64::decode(&offer.pk_a).filter(|a| crypto::decode_public_key(a).is_some()).ok_or(Failure::Malformed)?;
        let e_a_bytes = b64::decode(&offer.e_a).ok_or(Failure::Malformed)?;
        let e_a_key = crypto::decode_public_key(&e_a_bytes).ok_or(Failure::Malformed)?;
        let token = b64::decode(&offer.token).filter(|t| t.len() == TOKEN_BYTES).ok_or(Failure::Malformed)?;
        let transcript0 = crypto::length_prefixed(&[LABEL.as_bytes(), &pk_a, &e_a_bytes, &token, &e_b]);
        let transcript = crypto::length_prefixed(&[&transcript0, &pk_b]);
        let prk = hkdf::extract(&token, &crypto::ecdh(&ephemeral, &e_a_key));
        let keys = schedule(&prk, &transcript, &pk_b, &pk_a);
        Ok(Self {
            identity,
            device_name: device_name.to_string(),
            account_id: account_id.to_string(),
            hello_extension,
            pk_b,
            e_b,
            pk_a,
            transcript0,
            transcript,
            prk,
            keys,
        })
    }

    pub fn hello(&self) -> Hello {
        let body = HelloBody {
            pk_b: b64::encode(&self.pk_b),
            sig_b: b64::encode(&self.identity.sign(&format!("{LABEL}-b"), &signed_transcript(&self.transcript, self.hello_extension.as_deref()))),
            device_name: self.device_name.clone(),
            account_hmac: b64::encode(&account_hmac(&self.keys.account_key, &self.account_id)),
            extension: self.hello_extension.clone(),
        };
        let (nonce, ciphertext) = crypto::seal(
            &handshake_key(&self.prk, "b->a", &self.transcript0),
            &self.transcript0,
            &serde_json::to_vec(&body).expect("HelloBody は常に JSON にできる"),
        );
        Hello { v: VERSION, e_b: b64::encode(&self.e_b), nonce: b64::encode(&nonce), ciphertext: b64::encode(&ciphertext) }
    }

    /// A の Confirm を確かめ、（表示する6桁, 確定したときに保存するペア）を返す。
    pub fn on_confirm(&self, confirm: &Confirm) -> Result<Confirmed, Failure> {
        let nonce = b64::decode(&confirm.nonce).ok_or(Failure::Malformed)?;
        let ciphertext = b64::decode(&confirm.ciphertext).ok_or(Failure::Malformed)?;
        let plain = crypto::open(&handshake_key(&self.prk, "a->b", &self.transcript0), &nonce, &self.transcript0, &ciphertext).ok_or(Failure::Malformed)?;
        let body: ConfirmBody = parse(&plain).ok_or(Failure::Malformed)?;
        let pk_a_key = crypto::decode_public_key(&self.pk_a).ok_or(Failure::Malformed)?;
        if body.extension.as_deref().is_some_and(|e| e.encode_utf16().count() > MAX_EXTENSION_CHARS) {
            return Err(Failure::Malformed);
        }
        let sig_a = b64::decode(&body.sig_a).ok_or(Failure::Malformed)?;
        if !crypto::verify(&pk_a_key, &format!("{LABEL}-a"), &signed_transcript(&self.transcript, body.extension.as_deref()), &sig_a) {
            return Err(Failure::BadSignature);
        }
        let peer_hmac = b64::decode(&body.account_hmac).unwrap_or_default();
        if !crypto::constant_time_equals(&account_hmac(&self.keys.account_key, &self.account_id), &peer_hmac) {
            return Err(Failure::AccountMismatch);
        }
        Ok(Confirmed {
            code: self.keys.sas.clone(),
            pair: Established {
                peer_public_key: self.pk_a.clone(),
                peer_device_name: sanitize_name(&body.device_name),
                send_key: self.keys.send_key.clone(),
                receive_key: self.keys.receive_key.clone(),
                account_key: self.keys.account_key.clone(),
                peer_extension: body.extension,
                peer_accountless: false,
            },
        })
    }
}

struct Keys {
    sas: String,
    send_key: Vec<u8>,
    receive_key: Vec<u8>,
    account_key: Vec<u8>,
}

/// 拡張があれば、署名の対象に拡張のハッシュを足す（無ければ v1 と同じ）。
fn signed_transcript(transcript: &[u8], extension: Option<&str>) -> Vec<u8> {
    match extension {
        None => transcript.to_vec(),
        Some(extension) => crypto::length_prefixed(&[transcript, &crypto::sha256(&[extension.as_bytes()])]),
    }
}

fn handshake_key(prk: &[u8], direction: &str, transcript0: &[u8]) -> Vec<u8> {
    let info = [format!("{LABEL} handshake {direction}").as_bytes(), &crypto::sha256(&[transcript0])].concat();
    hkdf::expand(prk, &info, 32)
}

/// 確定したペアの鍵。方向は端末 ID（公開鍵の SHA-256）の大小（符号なし）で決める。
fn schedule(prk: &[u8], transcript: &[u8], own: &[u8], peer: &[u8]) -> Keys {
    let hash = crypto::sha256(&[transcript]);
    let expand = |label: &str, length: usize| hkdf::expand(prk, &[format!("{LABEL} {label}").as_bytes(), &hash].concat(), length);
    let sas_bytes = expand("sas", 8);
    // Long.remainderUnsigned と同じ（8バイトを符号なしとして読む）
    let sas_number = u64::from_be_bytes(sas_bytes.try_into().expect("8 バイト")) % 1_000_000;
    let low_to_high = expand("key low->high", 32);
    let high_to_low = expand("key high->low", 32);
    let account_key = expand("account", 32);
    let own_is_low = crypto::fingerprint(own) < crypto::fingerprint(peer);
    let (send_key, receive_key) = if own_is_low { (low_to_high, high_to_low) } else { (high_to_low, low_to_high) };
    Keys { sas: format!("{sas_number:06}"), send_key, receive_key, account_key }
}

/// 同じアカウントかを確かめる HMAC（誤操作の防止で、防御ではない）。
pub fn account_hmac(account_key: &[u8], account_id: &str) -> Vec<u8> {
    crypto::hmac_sha256(account_key, &[b"wave-account-v1", account_id.as_bytes()])
}

/// 端末名から制御文字・書字方向の上書き文字を落とし、64文字（UTF-16 の数）までにする。
/// 64 の境目でサロゲートの組が割れるときは、その1文字を落とす（Kotlin・C# は半分を残す）。
pub fn sanitize_name(name: &str) -> String {
    let filtered: String = name
        .chars()
        .filter(|&c| !(is_iso_control(c) || ('\u{202a}'..='\u{202e}').contains(&c) || ('\u{2066}'..='\u{2069}').contains(&c) || c == '\u{200e}' || c == '\u{200f}'))
        .collect();
    let trimmed = filtered.trim();
    let mut out = String::new();
    let mut units = 0;
    for c in trimmed.chars() {
        units += c.len_utf16();
        if units > 64 {
            break;
        }
        out.push(c);
    }
    if out.is_empty() { "端末".to_string() } else { out }
}

fn parse<T: DeserializeOwned>(utf8: &[u8]) -> Option<T> {
    serde_json::from_slice(utf8).ok()
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicI64, Ordering};

    use super::super::chain::DeviceIdentity;
    use super::super::device::SoftwareDevice;
    use super::*;

    struct Clock(Arc<AtomicI64>);

    impl Clock {
        fn new() -> Self {
            Self(Arc::new(AtomicI64::new(0)))
        }

        fn func(&self) -> Box<dyn Fn() -> i64 + Send + Sync> {
            let now = self.0.clone();
            Box::new(move || now.load(Ordering::SeqCst))
        }

        fn advance(&self, ms: i64) {
            self.0.fetch_add(ms, Ordering::SeqCst);
        }
    }

    struct Fixture {
        clock: Clock,
        id_a: Arc<SoftwareDevice>,
        id_b: Arc<SoftwareDevice>,
    }

    impl Fixture {
        fn new() -> Self {
            Self { clock: Clock::new(), id_a: Arc::new(SoftwareDevice::generate()), id_b: Arc::new(SoftwareDevice::generate()) }
        }

        fn responder(&self, account: &str, extension: Option<&str>) -> Responder {
            Responder::new(self.id_a.clone(), "Pixel 3", account, self.clock.func(), extension.map(String::from))
        }
    }

    fn initiator(offer: &Offer, identity: Arc<dyn DeviceSigner + Send + Sync>, account: &str, extension: Option<&str>) -> Initiator {
        Initiator::new(offer, identity, "Windows", account, extension.map(String::from)).unwrap()
    }

    #[test]
    fn honest_pairing_agrees_on_the_code_and_directional_keys() {
        let f = Fixture::new();
        let mut a = f.responder("account-1", Some(r#"{"householdId":"h"}"#));
        let offer = decode_offer(&encode_offer(a.offer())).unwrap();
        let b = initiator(&offer, f.id_b.clone(), "account-1", Some(r#"{"deviceId":"d"}"#));
        let confirmed = b.on_confirm(&a.on_hello(&b.hello()).unwrap()).unwrap();
        assert!(confirmed.code.len() == 6 && confirmed.code.bytes().all(|c| c.is_ascii_digit()));
        let on_a = a.on_code_entered(&confirmed.code).unwrap();
        assert_eq!(on_a.send_key, confirmed.pair.receive_key);
        assert_eq!(on_a.receive_key, confirmed.pair.send_key);
        assert_eq!(on_a.account_key, confirmed.pair.account_key);
        assert_eq!(f.id_b.identity_pub(), on_a.peer_public_key.as_slice());
        assert_eq!(f.id_a.identity_pub(), confirmed.pair.peer_public_key.as_slice());
        assert_eq!(Some(r#"{"deviceId":"d"}"#), on_a.peer_extension.as_deref());
        assert_eq!(Some(r#"{"householdId":"h"}"#), confirmed.pair.peer_extension.as_deref());
        // 鍵は Debug に出ない
        let text = format!("{on_a:?} {confirmed:?}");
        assert!(!text.contains(&confirmed.code));
        assert!(!text.contains(&format!("{:?}", on_a.send_key)));
    }

    #[test]
    fn rejects_wrong_codes_accounts_expiry_and_reuse() {
        let f = Fixture::new();
        let mut a = f.responder("account-1", None);
        a.on_hello(&initiator(a.offer(), f.id_b.clone(), "account-1", None).hello()).unwrap();
        assert_eq!(Err(Failure::AlreadyUsed), a.on_hello(&initiator(a.offer(), f.id_b.clone(), "account-1", None).hello()));
        assert_eq!(Failure::WrongCode, a.on_code_entered("x").unwrap_err());
        assert_eq!(Failure::WrongCode, a.on_code_entered("x").unwrap_err());
        assert_eq!(Failure::TooManyAttempts, a.on_code_entered("x").unwrap_err());

        let mut other = f.responder("account-1", None);
        assert_eq!(Err(Failure::AccountMismatch), other.on_hello(&initiator(other.offer(), f.id_b.clone(), "account-2", None).hello()));

        let mut late = f.responder("account-1", None);
        f.clock.advance(LIFETIME_MS + 1);
        assert_eq!(Err(Failure::Expired), late.on_hello(&initiator(late.offer(), f.id_b.clone(), "account-1", None).hello()));

        let mut own = f.responder("account-1", None);
        assert_eq!(Err(Failure::Malformed), own.on_hello(&initiator(own.offer(), f.id_a.clone(), "account-1", None).hello()));
    }

    /// pkB に対応しない鍵で署名する端末。
    struct ForgedSigner {
        claimed_pub: Vec<u8>,
        claimed_id: String,
        real: SoftwareDevice,
    }

    impl DeviceIdentity for ForgedSigner {
        fn device_id(&self) -> &str {
            &self.claimed_id
        }
    }

    impl DeviceSigner for ForgedSigner {
        fn identity_pub(&self) -> &[u8] {
            &self.claimed_pub
        }

        fn sign(&self, label: &str, message: &[u8]) -> Vec<u8> {
            self.real.sign(label, message)
        }
    }

    #[test]
    fn relay_cannot_swap_keys_or_forge_signatures() {
        let f = Fixture::new();
        let mut a = f.responder("account-1", None);
        let hello = initiator(a.offer(), f.id_b.clone(), "account-1", None).hello();
        let swap = crypto::generate_key();
        let swapped = Hello { e_b: b64::encode(&crypto::encode_public_key(&swap)), ..hello };
        assert_eq!(Err(Failure::Malformed), a.on_hello(&swapped));
        let forger = Arc::new(ForgedSigner {
            claimed_pub: f.id_b.identity_pub().to_vec(),
            claimed_id: crypto::device_id(f.id_b.identity_pub()),
            real: SoftwareDevice::generate(),
        });
        assert_eq!(Err(Failure::BadSignature), a.on_hello(&initiator(a.offer(), forger, "account-1", None).hello()));
    }

    #[test]
    fn offers_and_names_are_validated() {
        let f = Fixture::new();
        assert_eq!(None, decode_offer("https://example.com"));
        assert_eq!(None, decode_offer("wave-pair:!!!"));
        let a = f.responder("account-1", None);
        assert_eq!(None, decode_offer(&encode_offer(&Offer { token: "AAAA".into(), ..a.offer().clone() })));
        assert_eq!(None, decode_offer(&encode_offer(&Offer { v: 2, ..a.offer().clone() })));
        // QR の形は Kotlin と同じ JSON（{"v":1,"pkA":...,"eA":...,"token":...}）
        let json = b64::decode(&encode_offer(a.offer())["wave-pair:".len()..]).unwrap();
        let text = String::from_utf8(json).unwrap();
        assert!(text.starts_with(r#"{"v":1,"pkA":""#));
        let ea = text.find(r#""eA":"#).unwrap();
        let token = text.find(r#""token":"#).unwrap();
        assert!(ea < token);
        assert_eq!("abc", sanitize_name("a\u{202e}b\u{0}c"));
        assert_eq!("端末", sanitize_name("\u{7}"));
        assert_eq!(64, sanitize_name(&"あ".repeat(100)).chars().count());
        // Debug に token を出さない
        assert!(!format!("{:?}", a.offer()).contains(&a.offer().token));
    }

    #[test]
    fn hello_and_confirm_json_round_trip() {
        let f = Fixture::new();
        let mut a = f.responder("account-1", None);
        let hello = initiator(a.offer(), f.id_b.clone(), "account-1", None).hello();
        let value = hello_to_json(&hello);
        assert!(serde_json::to_string(&hello).unwrap().starts_with(r#"{"v":1,"eB":""#));
        assert_eq!(Some(hello.clone()), decode_hello(&value));
        let confirm = a.on_hello(&hello).unwrap();
        assert_eq!(Some(confirm.clone()), decode_confirm(&confirm_to_json(&confirm)));
        assert_eq!(None, decode_confirm(&serde_json::json!({"nonce": "x"})));
    }
}
