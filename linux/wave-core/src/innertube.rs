//! YouTube Music の InnerTube（WEB_REMIX）への通信（Windows の InnerTubeClient と同じ）。
//! 認証のヘッダ（cookie・SAPISIDHASH）は music.youtube.com への https にだけ付ける。
//! 送った値・受けた値はログに出さない（呼び出し側が出すのは件数と状態だけ）。

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use serde_json::{Value, json};
use sha1::{Digest, Sha1};

use crate::credentials::Credentials;
use crate::music::original_titles;

pub const CLIENT_NAME: &str = "WEB_REMIX";
pub const CLIENT_ID: &str = "67";
pub const CLIENT_VERSION: &str = "1.20260707.12.00";
pub const USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0";
pub const ORIGIN: &str = "https://music.youtube.com";
const API_BASE: &str = "https://music.youtube.com/youtubei/v1/";
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
/// 曲名を元の表記で出すときに、2つの言語で送る読み取り（曲・アルバム・アーティストの一覧を返すもの。Android と同じ）。
const BILINGUAL_ENDPOINTS: [&str; 3] = ["browse", "search", "next"];
/// 元の表記を選ぶときに覚えておくアーティストの数（古い方から忘れる）。
const MAX_KNOWN_ARTISTS: usize = 5000;

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
    /// 曲名・アーティスト名・アルバム名を、YouTube が画面の言語に合わせて書き換える前の表記で出すか（設定の「曲名を元の表記で」。既定は出さない）。
    /// 出すときは、曲の一覧を返す読み取り（search・browse・next）をもう1つの言語でも同時に送るので、通信が倍になる。
    original_titles: Arc<AtomicBool>,
    /// 元の表記を選ぶときに覚えたアーティスト（browseId → `ja`・`en`）。アプリを閉じるまで。
    known_artists: Arc<Mutex<indexmap::IndexMap<String, String>>>,
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
        Self {
            http,
            state: Arc::default(),
            hl: hl.to_string(),
            gl: gl.to_string(),
            original_titles: Arc::default(),
            known_artists: Arc::default(),
        }
    }

    /// 曲名を元の表記で出すかを切り替える（次の読み取りから効く）。
    pub fn set_original_titles(&self, on: bool) {
        self.original_titles.store(on, Ordering::Relaxed);
    }

    /// この読み取りを、もう1つの言語でも送るなら、その言語。
    fn bilingual(&self, endpoint: &str) -> Option<&'static str> {
        (self.original_titles.load(Ordering::Relaxed) && BILINGUAL_ENDPOINTS.contains(&endpoint)).then(|| original_titles::other_language(&self.hl))
    }

    /// 表示の言語と地域（ほかの口に同じ値を渡す）。
    pub fn locale(&self) -> (&str, &str) {
        (&self.hl, &self.gl)
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
    /// 曲名を元の表記で出すなら、曲の一覧を返す読み取りを、もう1つの言語でも同時に送って突き合わせる（もう1つが失敗したら画面の言語のまま）。
    pub async fn post(&self, endpoint: &str, body: Value, set_login: bool, query: &[(&str, &str)]) -> ApiResult<Value> {
        let Some(other_hl) = self.bilingual(endpoint) else {
            return self.post_in(&self.hl, endpoint, body, set_login, query).await;
        };
        let other = std::pin::pin!(self.post_in(other_hl, endpoint, body.clone(), set_login, query));
        let primary = std::pin::pin!(self.post_in(&self.hl, endpoint, body, set_login, query));
        // 画面の言語の方が失敗したら、もう1つの言語は待たずにやめる
        let (root, other) = match futures_util::future::select(primary, other).await {
            futures_util::future::Either::Left((primary, other)) => {
                let root = primary?;
                (root, other.await.ok())
            }
            futures_util::future::Either::Right((other, primary)) => (primary.await?, other.ok()),
        };
        Ok(self.localized(root, other.as_ref()))
    }

    /// 画面の言語の応答を、もう1つの言語の応答と突き合わせて元の表記にする（other が無ければそのまま）。わかったアーティストを覚える。
    fn localized(&self, root: Value, other: Option<&Value>) -> Value {
        let Some(other) = other else { return root };
        let known: HashMap<String, String> = self.known_artists.lock().unwrap().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
        let merged = original_titles::merge(root, other, &self.hl, &known);
        if !merged.learned.is_empty() {
            let mut known = self.known_artists.lock().unwrap();
            known.extend(merged.learned);
            let over = known.len().saturating_sub(MAX_KNOWN_ARTISTS);
            known.drain(..over);
        }
        merged.json
    }

    /// hl の言語で POST する。訪問者 ID は画面の言語の応答からだけ取る（続きのトークンがそれに結び付く）。
    async fn post_in(&self, hl: &str, endpoint: &str, mut body: Value, set_login: bool, query: &[(&str, &str)]) -> ApiResult<Value> {
        let (creds, generation, anonymous) = {
            let state = self.state.read().unwrap();
            (state.credentials.clone().filter(|_| set_login), state.generation, state.anonymous_visitor.clone())
        };
        let visitor = creds.as_ref().and_then(|c| c.visitor_data.clone()).or(anonymous);
        body["context"] = self.context(hl, creds.as_ref(), visitor.as_deref());
        let mut url = reqwest::Url::parse(&format!("{API_BASE}{endpoint}")).map_err(|_| Failure::Parse)?;
        url.query_pairs_mut().append_pair("prettyPrint", "false").extend_pairs(query);
        let headers = self.headers(hl, &url, creds.as_ref(), visitor.as_deref())?;
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
        if hl == self.hl && state.credentials.is_none() && state.anonymous_visitor.is_none() {
            state.anonymous_visitor = root.pointer("/responseContext/visitorData").and_then(Value::as_str).map(String::from);
        }
        Ok(root)
    }

    /// 再生履歴の統計（music.youtube.com の /api/stats/playback）へ GET する。送れたら HTTP の状態。
    /// 解いたときとログインが変わっていたら送らない（別のアカウントの履歴に載せない）。
    pub async fn get_stats(&self, url: &str, expected_generation: u64) -> Option<u16> {
        let creds = {
            let state = self.state.read().unwrap();
            if state.generation != expected_generation {
                return None;
            }
            state.credentials.clone()?
        };
        let url = reqwest::Url::parse(url).ok()?;
        let mut headers = self.headers(&self.hl, &url, Some(&creds), creds.visitor_data.as_deref()).ok()?;
        headers.insert(HeaderName::from_static("cache-control"), HeaderValue::from_static("no-cache"));
        let response = self.http.get(url).headers(headers).send().await.ok()?;
        Some(response.status().as_u16())
    }

    fn context(&self, hl: &str, creds: Option<&Credentials>, visitor: Option<&str>) -> Value {
        let mut client = json!({ "clientName": CLIENT_NAME, "clientVersion": CLIENT_VERSION, "gl": self.gl, "hl": hl });
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

    fn headers(&self, hl: &str, url: &reqwest::Url, creds: Option<&Credentials>, visitor: Option<&str>) -> ApiResult<HeaderMap> {
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
        put("accept-language", hl)?;
        if let Some(visitor) = visitor {
            put("x-goog-visitor-id", visitor)?;
        }
        put("cookie", &self.with_pref(hl, creds.map(Credentials::reveal_cookie)))?;
        if let Some(sapisid) = creds.and_then(Credentials::reveal_sapisid) {
            let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
            put("x-goog-authuser", "0")?;
            put("authorization", &sapisid_hash(sapisid, now))?;
        }
        Ok(headers)
    }

    /// cookie の PREF に表示の言語と地域を入れる（無ければ PREF だけ）。
    fn with_pref(&self, hl: &str, cookie: Option<&str>) -> String {
        let lead = [format!("hl={hl}"), format!("gl={}", self.gl)];
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
        assert_eq!(tube.with_pref("ja", None), "PREF=hl=ja&gl=JP");
        assert_eq!(tube.with_pref("ja", Some("SID=a; PREF=f6=8&hl=en")), "SID=a; PREF=hl=ja&gl=JP&f6=8");
        // もう1つの言語で送るときは、その言語を入れる
        assert_eq!(tube.with_pref("en", None), "PREF=hl=en&gl=JP");
        assert_eq!(tube.context("en", None, None)["client"]["hl"], "en");
    }

    #[test]
    fn original_titles_ask_in_two_languages_only_when_on() {
        // 設定の「曲名を元の表記で」: 入れたときだけ、曲の一覧を返す読み取りを、もう1つの言語でも送る
        let tube = InnerTube::new("ja", "JP");
        assert_eq!(tube.bilingual("search"), None);
        tube.set_original_titles(true);
        // 複製（MusicRepository などが持つもの）にも効く
        let copy = tube.clone();
        for endpoint in BILINGUAL_ENDPOINTS {
            assert_eq!(copy.bilingual(endpoint), Some("en"));
        }
        assert_eq!(copy.bilingual("player"), None);
        assert_eq!(copy.bilingual("music/get_search_suggestions"), None);
        assert_eq!(InnerTube::new("en", "US").tap_on().bilingual("browse"), Some("ja"));
        tube.set_original_titles(false);
        assert_eq!(copy.bilingual("search"), None);
    }

    fn fixture(name: &str) -> Value {
        let path = format!("{}/../../core/src/desktopTest/resources/fixtures/{name}.json", env!("CARGO_MANIFEST_DIR"));
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    impl InnerTube {
        fn tap_on(self) -> Self {
            self.set_original_titles(true);
            self
        }
    }

    fn artists(root: &Value) -> Vec<String> {
        crate::music::parsers::search_songs(root).songs.into_iter().flat_map(|s| s.artists.into_iter().map(|a| a.name)).collect()
    }

    #[test]
    fn a_failed_second_language_keeps_the_screen_language() {
        let tube = InnerTube::new("ja", "JP").tap_on();
        let names = artists(&tube.localized(fixture("bilingual_search_songs_ja"), None));
        assert!(names.iter().any(|n| n == "テイラー・スウィフト"), "{names:?}");
        let other = fixture("bilingual_search_songs_en");
        let names = artists(&tube.localized(fixture("bilingual_search_songs_ja"), Some(&other)));
        assert!(names.iter().all(|n| n == "Taylor Swift"), "{names:?}");
    }

    #[test]
    fn learned_artists_carry_over_to_the_next_response() {
        // アーティストのページで洋楽と分かったテイラーを覚え、次のキュー（題しか違わない行）に使う
        let tube = InnerTube::new("ja", "JP").tap_on();
        let next = |tube: &InnerTube| {
            let other = fixture("bilingual_next_taylor_en");
            crate::music::parsers::up_next(&tube.localized(fixture("bilingual_next_taylor_ja"), Some(&other))).songs.remove(0).title
        };
        assert!(next(&tube).starts_with("シェイク"));
        let other = fixture("bilingual_artist_taylor_en");
        tube.localized(fixture("bilingual_artist_taylor_ja"), Some(&other));
        assert_eq!(next(&tube.clone()), "Shake It Off");
    }
}
