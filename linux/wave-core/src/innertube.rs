//! YouTube Music の InnerTube（WEB_REMIX）への通信（Windows の InnerTubeClient と同じ）。
//! 認証のヘッダ（cookie・SAPISIDHASH）は music.youtube.com への https にだけ付ける。
//! 送った値・受けた値はログに出さない（呼び出し側が出すのは件数と状態だけ）。

use std::sync::{Arc, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use serde_json::{Value, json};
use sha1::{Digest, Sha1};

use crate::credentials::Credentials;

pub const CLIENT_NAME: &str = "WEB_REMIX";
pub const CLIENT_ID: &str = "67";
pub const CLIENT_VERSION: &str = "1.20260707.12.00";
pub const USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0";
pub const ORIGIN: &str = "https://music.youtube.com";
const API_BASE: &str = "https://music.youtube.com/youtubei/v1/";
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;

/// 呼び出しの失敗の種類。
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum Failure {
    /// ログインが要る（ログインしていない、または切れた）。
    #[error("not logged in")]
    NotLoggedIn,
    #[error("network")]
    Network,
    #[error("http {0}")]
    Http(u16),
    #[error("parse")]
    Parse,
}

pub type ApiResult<T> = Result<T, Failure>;

/// InnerTube への入り口。複製しても同じログインを見る。
#[derive(Clone)]
pub struct InnerTube {
    http: reqwest::Client,
    state: Arc<RwLock<State>>,
    hl: String,
    gl: String,
}

#[derive(Default)]
struct State {
    credentials: Option<Credentials>,
    /// ログインしていないときの訪問者 ID（最初の応答の responseContext から取る。続きのトークンがこれに結び付く）。
    anonymous_visitor: Option<String>,
    /// ログインの世代。ログイン・ログアウトのたびに変わる（古い世代で始めた結果を別のアカウントのものとして使わない）。
    generation: u64,
}

impl InnerTube {
    /// hl は表示の言語（YouTube から届く文字の言語）、gl は地域。
    pub fn new(hl: &str, gl: &str) -> Self {
        let http = reqwest::Client::builder()
            // cookie はこちらで付ける。勝手に保存・転送しない
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(30))
            .build()
            .expect("the TLS backend is built in");
        Self { http, state: Arc::default(), hl: hl.to_string(), gl: gl.to_string() }
    }

    pub fn set_credentials(&self, credentials: Option<Credentials>) {
        let mut state = self.state.write().unwrap();
        state.credentials = credentials;
        state.anonymous_visitor = None;
        state.generation += 1;
    }

    pub fn is_logged_in(&self) -> bool {
        self.state.read().unwrap().credentials.is_some()
    }

    /// 今のログインの取得時刻（ログインしていなければ None）。キューを持ち越すときに、同じログインかを見る。
    pub fn obtained_at(&self) -> Option<i64> {
        self.state.read().unwrap().credentials.as_ref().map(|c| c.obtained_at_epoch_ms)
    }

    /// 今のログインの世代（ログイン・ログアウトのたびに変わる）。
    pub fn generation(&self) -> u64 {
        self.state.read().unwrap().generation
    }

    /// 今のログインの DATASYNC_ID（"||" の前）。外へ送らない・ログに出さない。
    pub fn data_sync_id(&self) -> Option<String> {
        self.state.read().unwrap().credentials.as_ref().and_then(|c| c.reveal_data_sync_id().map(String::from))
    }

    /// youtubei/v1/{endpoint} に POST する。set_login なら（ログイン中のとき）認証のヘッダを付ける。
    pub async fn post(&self, endpoint: &str, mut body: Value, set_login: bool, query: &[(&str, &str)]) -> ApiResult<Value> {
        let (creds, generation, anonymous) = {
            let state = self.state.read().unwrap();
            (state.credentials.clone().filter(|_| set_login), state.generation, state.anonymous_visitor.clone())
        };
        let visitor = creds.as_ref().and_then(|c| c.visitor_data.clone()).or(anonymous);
        body["context"] = self.context(creds.as_ref(), visitor.as_deref());
        let mut url = reqwest::Url::parse(&format!("{API_BASE}{endpoint}")).map_err(|_| Failure::Parse)?;
        url.query_pairs_mut().append_pair("prettyPrint", "false").extend_pairs(query);
        let headers = self.headers(&url, creds.as_ref(), visitor.as_deref())?;
        let response = self.http.post(url).headers(headers).json(&body).send().await.map_err(|_| Failure::Network)?;
        let status = response.status().as_u16();
        if creds.is_some() && matches!(status, 401 | 403) {
            return Err(Failure::NotLoggedIn);
        }
        if !(200..=299).contains(&status) {
            return Err(Failure::Http(status));
        }
        // 送ったあとでログインが変わっていたら、別のアカウントの結果として使わない
        if creds.is_some() && generation != self.state.read().unwrap().generation {
            return Err(Failure::NotLoggedIn);
        }
        let bytes = read_limited(response).await?;
        let root: Value = serde_json::from_slice(&bytes).map_err(|_| Failure::Parse)?;
        let mut state = self.state.write().unwrap();
        if state.credentials.is_none() && state.anonymous_visitor.is_none() {
            state.anonymous_visitor = root.pointer("/responseContext/visitorData").and_then(Value::as_str).map(String::from);
        }
        Ok(root)
    }

    fn context(&self, creds: Option<&Credentials>, visitor: Option<&str>) -> Value {
        let mut client = json!({ "clientName": CLIENT_NAME, "clientVersion": CLIENT_VERSION, "gl": self.gl, "hl": self.hl });
        if let Some(visitor) = visitor {
            client["visitorData"] = json!(visitor);
        }
        let mut user = json!({ "lockedSafetyMode": false });
        if let Some(dsid) = creds.and_then(Credentials::reveal_data_sync_id) {
            user["onBehalfOfUser"] = json!(dsid);
        }
        json!({
            "client": client,
            "request": { "internalExperimentFlags": [], "useSsl": true },
            "user": user,
        })
    }

    fn headers(&self, url: &reqwest::Url, creds: Option<&Credentials>, visitor: Option<&str>) -> ApiResult<HeaderMap> {
        // 認証のヘッダは music.youtube.com への https にだけ付ける
        assert!(
            url.scheme() == "https" && url.host_str() == Some("music.youtube.com") && url.port().is_none(),
            "auth route violation"
        );
        let mut headers = HeaderMap::new();
        let mut put = |name: &'static str, value: &str| -> ApiResult<()> {
            headers.insert(HeaderName::from_static(name), HeaderValue::from_str(value).map_err(|_| Failure::Parse)?);
            Ok(())
        };
        put("user-agent", USER_AGENT)?;
        put("x-goog-api-format-version", "1")?;
        put("x-youtube-client-name", CLIENT_ID)?;
        put("x-youtube-client-version", CLIENT_VERSION)?;
        put("origin", ORIGIN)?;
        put("x-origin", ORIGIN)?;
        put("referer", &format!("{ORIGIN}/"))?;
        put("accept-language", &self.hl)?;
        if let Some(visitor) = visitor {
            put("x-goog-visitor-id", visitor)?;
        }
        put("cookie", &self.with_pref(creds.map(Credentials::reveal_cookie)))?;
        if let Some(sapisid) = creds.and_then(Credentials::reveal_sapisid) {
            let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
            put("x-goog-authuser", "0")?;
            put("authorization", &sapisid_hash(sapisid, now))?;
        }
        Ok(headers)
    }

    /// cookie の PREF に表示の言語と地域を入れる（無ければ PREF だけ）。
    fn with_pref(&self, cookie: Option<&str>) -> String {
        let lead = [format!("hl={}", self.hl), format!("gl={}", self.gl)];
        let Some(cookie) = cookie.filter(|c| !c.trim().is_empty()) else {
            return format!("PREF={}", lead.join("&"));
        };
        let mut pref: Vec<String> = lead.to_vec();
        let mut others = Vec::new();
        for part in cookie.split(';').map(str::trim).filter(|p| !p.is_empty()) {
            match part.get(..5).filter(|head| head.eq_ignore_ascii_case("PREF=")) {
                Some(_) => pref.extend(part[5..].split('&').filter(|p| !p.starts_with("hl=") && !p.starts_with("gl=")).map(String::from)),
                None => others.push(part.to_string()),
            }
        }
        others.push(format!("PREF={}", pref.join("&")));
        others.join("; ")
    }
}

pub(crate) fn sapisid_hash(sapisid: &str, unix_seconds: u64) -> String {
    let digest = Sha1::digest(format!("{unix_seconds} {sapisid} {ORIGIN}").as_bytes());
    format!("SAPISIDHASH {unix_seconds}_{}", hex::encode(digest))
}

async fn read_limited(mut response: reqwest::Response) -> ApiResult<Vec<u8>> {
    let mut buffer = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| Failure::Network)? {
        if buffer.len() + chunk.len() > MAX_RESPONSE_BYTES {
            return Err(Failure::Parse);
        }
        buffer.extend_from_slice(&chunk);
    }
    Ok(buffer)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sapisid_hash_matches_the_known_form() {
        // SHA1("1700000000 abc https://music.youtube.com")
        let expected = hex::encode(Sha1::digest(b"1700000000 abc https://music.youtube.com"));
        assert_eq!(sapisid_hash("abc", 1_700_000_000), format!("SAPISIDHASH 1700000000_{expected}"));
    }

    #[test]
    fn pref_carries_language_and_region() {
        let tube = InnerTube::new("ja", "JP");
        assert_eq!(tube.with_pref(None), "PREF=hl=ja&gl=JP");
        assert_eq!(tube.with_pref(Some("SID=a; PREF=f6=8&hl=en")), "SID=a; PREF=hl=ja&gl=JP&f6=8");
    }
}
