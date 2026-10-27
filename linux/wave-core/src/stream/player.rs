//! 今の YouTube のプレイヤー（ハッシュ）と、それに合う faraday の表の行、解読器を用意する（Windows の PlayerCipherProvider と同じ）。
//! どれも公開データで、cookie は送らない。1時間ごとに取り直す。

use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

use regex::Regex;
use serde_json::Value;
use tokio::sync::Mutex;

use super::cipher::{CipherSolver, PlayerCipherConfig};

pub const FARADAY_CONFIG_URL: &str = "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json";
const TTL: Duration = Duration::from_secs(60 * 60);
const MAX_PLAYER_BYTES: usize = 8 * 1024 * 1024;
const BROWSER_USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0";

/// 解読の準備ができた状態（プレイヤーのハッシュ・STS・解読器）。
pub struct Ready {
    pub player_hash: String,
    pub signature_timestamp: u32,
    pub solver: CipherSolver,
    fetched_at: Instant,
}

#[derive(Clone)]
pub struct PlayerCipherProvider {
    http: reqwest::Client,
    ready: Arc<Mutex<Option<Arc<Ready>>>>,
}

static PLAYER_HASH: LazyLock<Regex> = LazyLock::new(|| Regex::new("/s/player/([a-f0-9]{8})/").unwrap());
static SIG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[A-Za-z0-9$_]{1,8}\(\d+,\d+,INPUT\)$").unwrap());
static N_CLASS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[A-Za-z0-9$_]{1,8}$").unwrap());

impl Default for PlayerCipherProvider {
    fn default() -> Self {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(30)).build().expect("the TLS backend is built in");
        Self { http, ready: Arc::default() }
    }
}

impl PlayerCipherProvider {
    /// 解読器を返す。取れなければ None。
    pub async fn get(&self) -> Option<Arc<Ready>> {
        let mut slot = self.ready.lock().await;
        if let Some(ready) = slot.as_ref().filter(|r| r.fetched_at.elapsed() < TTL) {
            return Some(ready.clone());
        }
        let hash = self.current_player_hash().await?;
        if let Some(same) = slot.as_ref().filter(|r| r.player_hash == hash) {
            // 同じプレイヤーなら解読器はそのまま使い、取り直した時刻だけ進める
            let same = same.clone();
            return Some(same);
        }
        let config = parse_faraday(&self.get_text(FARADAY_CONFIG_URL, 4 * 1024 * 1024).await?, &hash)?;
        let code = self.get_text(&format!("https://www.youtube.com/s/player/{hash}/player_ias.vflset/en_GB/base.js"), MAX_PLAYER_BYTES).await?;
        let solver = tokio::task::spawn_blocking(move || CipherSolver::create(&code, &config).map(|s| (s, config))).await.ok()??;
        let ready = Arc::new(Ready { player_hash: hash, signature_timestamp: solver.1.signature_timestamp, solver: solver.0, fetched_at: Instant::now() });
        *slot = Some(ready.clone());
        Some(ready)
    }

    /// 解いた URL が拒否された。次は取り直す。
    pub async fn invalidate(&self) {
        *self.ready.lock().await = None;
    }

    async fn current_player_hash(&self) -> Option<String> {
        let api = self.get_text("https://www.youtube.com/iframe_api", 256 * 1024).await?;
        PLAYER_HASH.captures(&api.replace("\\/", "/")).map(|c| c[1].to_string())
    }

    async fn get_text(&self, url: &str, max_bytes: usize) -> Option<String> {
        let response = self
            .http
            .get(url)
            .header("user-agent", BROWSER_USER_AGENT)
            .header("referer", "https://www.youtube.com/")
            .send()
            .await
            .ok()?;
        if !response.status().is_success() || response.content_length().is_some_and(|l| l as usize > max_bytes) {
            return None;
        }
        let text = response.text().await.ok()?;
        (text.len() <= max_bytes).then_some(text)
    }
}

/// faraday の表から、ハッシュ（または別名）に合う行を読む。形が合わない行は使わない。
pub fn parse_faraday(text: &str, hash: &str) -> Option<PlayerCipherConfig> {
    let root: Value = serde_json::from_str(text).ok()?;
    if root.get("schemaVersion")?.as_i64()? != 1 {
        return None;
    }
    let players = root.get("players")?.as_object()?;
    let entry = players.get(hash).or_else(|| {
        players.values().find(|v| v.get("aliases").and_then(Value::as_array).is_some_and(|a| a.iter().any(|x| x.as_str() == Some(hash))))
    })?;
    let sig = entry.get("sig")?.as_str()?;
    let n_class = entry.get("nClass")?.as_str()?;
    let sts = entry.get("sts")?.as_u64().filter(|s| *s > 0)?;
    (SIG.is_match(sig) && N_CLASS.is_match(n_class)).then(|| PlayerCipherConfig {
        sig_expression: sig.to_string(),
        n_class: n_class.to_string(),
        signature_timestamp: sts as u32,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn faraday_rows_by_hash_and_alias() {
        let text = r#"{"schemaVersion":1,"players":{"aaaa1111":{"sig":"Ab(1,2,INPUT)","nClass":"Qz","sts":20123,"aliases":["bbbb2222"]},
            "cccc3333":{"sig":"alert(1)","nClass":"Qz","sts":1}}}"#;
        assert_eq!(parse_faraday(text, "aaaa1111").unwrap().signature_timestamp, 20123);
        assert_eq!(parse_faraday(text, "bbbb2222").unwrap().n_class, "Qz");
        // 式の形が合わない行は使わない
        assert!(parse_faraday(text, "cccc3333").is_none());
        assert!(parse_faraday(r#"{"schemaVersion":2,"players":{}}"#, "aaaa1111").is_none());
    }
}
