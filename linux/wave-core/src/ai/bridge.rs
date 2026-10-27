//! 中継（ai-bridge）とのやりとり。/v1/run に頼みを送り、1行ごとの JSON（session・delta・tool_call・error・done）を受ける。
//! 道具の結果は /v1/run/result に返す。トークンはログ・エラーの文に出さない。

use std::time::Duration;

use serde_json::{Value, json};
use tokio::sync::mpsc;

/// つなぐ先（QR の中身 waveai://pair?u=&t=&n= から作る）。
#[derive(Clone, PartialEq, Eq)]
pub struct BridgeConfig {
    pub url: String,
    token: String,
    pub name: Option<String>,
}

impl std::fmt::Debug for BridgeConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "BridgeConfig(url={}, token=present, name={:?})", self.url, self.name)
    }
}

impl BridgeConfig {
    /// 接続の文字列を読む。形が違う・https でも手元（127.0.0.1・localhost）でもなければ None。
    pub fn pair(text: &str) -> Option<Self> {
        let parsed = url::Url::parse(text.trim()).ok()?;
        if parsed.scheme() != "waveai" || parsed.host_str() != Some("pair") {
            return None;
        }
        let get = |name: &str| parsed.query_pairs().find(|(k, _)| k == name).map(|(_, v)| v.to_string());
        let url = get("u")?.trim_end_matches('/').to_string();
        let token = get("t").filter(|t| !t.is_empty())?;
        let local = url.starts_with("http://127.0.0.1:") || url.starts_with("http://localhost:");
        if url.is_empty() || !(url.starts_with("https://") || local) {
            return None;
        }
        Some(Self { url, token, name: get("n").filter(|n| !n.is_empty()) })
    }

    /// 保存用（キーリングに入れる）。
    pub fn serialize(&self) -> String {
        json!({ "url": self.url, "token": self.token, "name": self.name }).to_string()
    }

    pub fn deserialize(text: &str) -> Option<Self> {
        let v: Value = serde_json::from_str(text).ok()?;
        Some(Self {
            url: v.get("url")?.as_str()?.to_string(),
            token: v.get("token")?.as_str()?.to_string(),
            name: v.get("name").and_then(Value::as_str).map(String::from),
        })
    }
}

/// 中継から届く出来事。
#[derive(Debug, Clone, PartialEq)]
pub enum BridgeEvent {
    Session(String),
    Delta(String),
    ToolCall { id: String, name: String, arguments: Value },
    Error(String),
    Done,
    /// トークンを断られた（つなぎ直しが要る）。
    Unauthorized,
    /// 届かなかった・途中で切れた。
    Unreachable,
}

#[derive(Clone)]
pub struct Bridge {
    http: reqwest::Client,
    config: BridgeConfig,
}

impl Bridge {
    pub fn new(config: BridgeConfig) -> Self {
        // 認証情報を持たないクライアント（cookie・リダイレクトなし）。答えは長く続くので全体の時間は切らない
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(15))
            .build()
            .expect("the TLS backend is built in");
        Self { http, config }
    }

    /// 頼みを送り、届いた出来事を順に events へ流す（終わり・失敗で閉じる）。tokio の上で動かす。
    pub async fn run(&self, body: Value, events: mpsc::UnboundedSender<BridgeEvent>) {
        let response = self.http.post(format!("{}/v1/run", self.config.url)).bearer_auth(&self.config.token).json(&body).send().await;
        let mut response = match response {
            Ok(r) if r.status().as_u16() == 401 => {
                let _ = events.send(BridgeEvent::Unauthorized);
                return;
            }
            Ok(r) if r.status().is_success() => r,
            _ => {
                let _ = events.send(BridgeEvent::Unreachable);
                return;
            }
        };
        let mut pending = Vec::new();
        loop {
            let chunk = match response.chunk().await {
                Ok(Some(chunk)) => chunk,
                Ok(None) => break,
                Err(_) => {
                    let _ = events.send(BridgeEvent::Unreachable);
                    return;
                }
            };
            pending.extend_from_slice(&chunk);
            while let Some(at) = pending.iter().position(|b| *b == b'\n') {
                let line: Vec<u8> = pending.drain(..=at).collect();
                if let Some(event) = parse_line(&line) {
                    let done = event == BridgeEvent::Done;
                    if events.send(event).is_err() || done {
                        return;
                    }
                }
            }
        }
        if let Some(event) = parse_line(&pending) {
            let _ = events.send(event);
        }
        let _ = events.send(BridgeEvent::Done);
    }

    /// 道具の結果（または失敗）を返す。
    pub async fn post_result(&self, session: &str, id: &str, result: Result<Value, String>) {
        let mut reply = json!({ "session": session, "id": id });
        match result {
            Ok(value) => reply["result"] = value,
            Err(error) => reply["error"] = json!(error),
        }
        let _ = self.http.post(format!("{}/v1/run/result", self.config.url)).bearer_auth(&self.config.token).json(&reply).send().await;
    }
}

fn parse_line(line: &[u8]) -> Option<BridgeEvent> {
    let v: Value = serde_json::from_slice(line).ok()?;
    if let Some(s) = v.get("session").and_then(Value::as_str) {
        return Some(BridgeEvent::Session(s.to_string()));
    }
    if let Some(d) = v.get("delta").and_then(Value::as_str) {
        return Some(BridgeEvent::Delta(d.to_string()));
    }
    if let Some(call) = v.get("tool_call") {
        return Some(BridgeEvent::ToolCall {
            id: call.get("id").and_then(Value::as_str).unwrap_or("").to_string(),
            name: call.get("name").and_then(Value::as_str).unwrap_or("").to_string(),
            arguments: call.get("arguments").cloned().filter(Value::is_object).unwrap_or_else(|| json!({})),
        });
    }
    if let Some(e) = v.get("error") {
        return Some(BridgeEvent::Error(e.as_str().map(String::from).unwrap_or_else(|| e.to_string())));
    }
    v.get("done").map(|_| BridgeEvent::Done)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pairing_accepts_https_and_local_only() {
        let c = BridgeConfig::pair("waveai://pair?u=https%3A%2F%2Fbridge.example%2F&t=secret&n=home").unwrap();
        assert_eq!((c.url.as_str(), c.name.as_deref()), ("https://bridge.example", Some("home")));
        assert!(BridgeConfig::pair("waveai://pair?u=http%3A%2F%2F127.0.0.1%3A8787&t=x").is_some());
        assert!(BridgeConfig::pair("waveai://pair?u=http%3A%2F%2Fevil.example&t=x").is_none());
        assert!(BridgeConfig::pair("waveai://pair?u=https%3A%2F%2Fa&t=").is_none());
        assert!(BridgeConfig::pair("https://pair?u=https%3A%2F%2Fa&t=x").is_none());
        assert!(!format!("{c:?}").contains("secret"));
        assert_eq!(BridgeConfig::deserialize(&c.serialize()), Some(c));
    }

    #[test]
    fn reads_bridge_lines() {
        assert_eq!(parse_line(br#"{"session":"s1"}"#), Some(BridgeEvent::Session("s1".into())));
        assert_eq!(parse_line(br#"{"delta":"hi"}"#), Some(BridgeEvent::Delta("hi".into())));
        assert_eq!(
            parse_line(br#"{"tool_call":{"id":"1","name":"search_songs","arguments":{"query":"q"}}}"#),
            Some(BridgeEvent::ToolCall { id: "1".into(), name: "search_songs".into(), arguments: json!({"query": "q"}) })
        );
        assert_eq!(parse_line(br#"{"done":true}"#), Some(BridgeEvent::Done));
        assert_eq!(parse_line(b"not json"), None);
    }
}
