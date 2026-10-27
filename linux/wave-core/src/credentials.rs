//! ログインで得た認証情報。値は Debug・ログ・エラーの文に出さない。値を取り出す場所は reveal で探せる。

use serde::{Deserialize, Serialize};

const SAPISID_COOKIE_NAMES: [&str; 3] = ["SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"];

#[derive(Clone, PartialEq)]
pub struct Credentials {
    cookie: String,
    data_sync_id: Option<String>,
    /// 追跡用の ID で、認証情報ではない。
    pub visitor_data: Option<String>,
    pub obtained_at_epoch_ms: i64,
}

impl std::fmt::Debug for Credentials {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Credentials(cookie=present, dataSyncId={}, visitorData={})", self.data_sync_id.is_some(), self.visitor_data.is_some())
    }
}

/// 保存用の形（キーリングに入れる）。
#[derive(Serialize, Deserialize)]
struct Stored {
    cookie: String,
    #[serde(rename = "dataSyncId")]
    data_sync_id: Option<String>,
    #[serde(rename = "visitorData")]
    visitor_data: Option<String>,
    #[serde(rename = "obtainedAt", default)]
    obtained_at: i64,
}

impl Credentials {
    /// ブラウザから取った生の値で作る。制御文字を含むときと、SAPISID 系の cookie が無いときは None。
    pub fn from_login(raw_cookie: &str, visitor_data: Option<&str>, raw_data_sync_id: Option<&str>, now_epoch_ms: i64) -> Option<Self> {
        let has_control = |v: &str| v.chars().any(char::is_control);
        if has_control(raw_cookie) || visitor_data.is_some_and(has_control) || raw_data_sync_id.is_some_and(has_control) {
            return None;
        }
        let cookie = raw_cookie.trim().to_string();
        sapisid_of(&cookie)?;
        Some(Self {
            cookie,
            data_sync_id: normalize_data_sync_id(raw_data_sync_id),
            visitor_data: visitor_data.filter(|v| !v.trim().is_empty()).map(String::from),
            obtained_at_epoch_ms: now_epoch_ms,
        })
    }

    pub fn reveal_cookie(&self) -> &str {
        &self.cookie
    }

    pub fn reveal_data_sync_id(&self) -> Option<&str> {
        self.data_sync_id.as_deref()
    }

    /// SAPISIDHASH に使う値。無ければ None。
    pub(crate) fn reveal_sapisid(&self) -> Option<&str> {
        sapisid_of(&self.cookie)
    }

    pub fn serialize(&self) -> String {
        serde_json::to_string(&Stored {
            cookie: self.cookie.clone(),
            data_sync_id: self.data_sync_id.clone(),
            visitor_data: self.visitor_data.clone(),
            obtained_at: self.obtained_at_epoch_ms,
        })
        .expect("plain strings always serialize")
    }

    pub fn deserialize(text: &str) -> Option<Self> {
        let stored: Stored = serde_json::from_str(text).ok()?;
        Self::from_login(&stored.cookie, stored.visitor_data.as_deref(), stored.data_sync_id.as_deref(), stored.obtained_at)
    }
}

fn sapisid_of(cookie: &str) -> Option<&str> {
    let pairs: Vec<(&str, &str)> = cookie
        .split(';')
        .filter_map(|part| {
            let (name, value) = part.split_once('=')?;
            let name = name.trim();
            (!name.is_empty()).then_some((name, value.trim()))
        })
        .collect();
    SAPISID_COOKIE_NAMES
        .iter()
        .find_map(|wanted| pairs.iter().rev().find(|(name, value)| name == wanted && !value.is_empty()).map(|(_, v)| *v))
}

/// DATASYNC_ID は "||" の前だけを onBehalfOfUser に使う（丸ごと渡すと 500 になる）。
pub(crate) fn normalize_data_sync_id(raw: Option<&str>) -> Option<String> {
    let raw = raw?;
    let value = raw.split("||").next().unwrap_or("").trim();
    (!value.is_empty()).then(|| value.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn needs_a_sapisid_cookie() {
        assert!(Credentials::from_login("HSID=a; SID=b", None, None, 0).is_none());
        let c = Credentials::from_login("SID=b; __Secure-3PAPISID=xyz", Some("v"), Some("123||456"), 7).unwrap();
        assert_eq!(c.reveal_sapisid(), Some("xyz"));
        assert_eq!(c.reveal_data_sync_id(), Some("123"));
    }

    #[test]
    fn rejects_control_characters() {
        assert!(Credentials::from_login("SAPISID=a\nX", None, None, 0).is_none());
    }

    #[test]
    fn debug_does_not_reveal_values() {
        let c = Credentials::from_login("SAPISID=secret-value", None, Some("dsid-value"), 0).unwrap();
        let text = format!("{c:?}");
        assert!(!text.contains("secret-value") && !text.contains("dsid-value"));
    }

    #[test]
    fn round_trips_through_storage() {
        let c = Credentials::from_login("SAPISID=a; SID=b", Some("v"), Some("d"), 42).unwrap();
        assert_eq!(Credentials::deserialize(&c.serialize()), Some(c));
    }
}
