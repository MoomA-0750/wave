//! WEB_REMIX の /player から音声のストリーム URL を解決する（Windows の StreamResolver・Android の ADR-0001 と同じ経路。po_token は作らない）。

use std::sync::LazyLock;

use regex::Regex;
use serde_json::{Value, json};

use super::cipher::CipherSolver;
use super::player::PlayerCipherProvider;
use crate::innertube::{Failure, InnerTube};
use crate::json::JsonPath;

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum StreamFailure {
    #[error("not logged in")]
    NotLoggedIn,
    #[error("network")]
    Network,
    #[error("http")]
    Http,
    #[error("parse")]
    Parse,
    #[error("player config unavailable")]
    PlayerConfigUnavailable,
    #[error("unplayable")]
    Unplayable,
    #[error("no audio format")]
    NoAudioFormat,
    #[error("sabr only")]
    SabrOnly,
    #[error("decipher failed")]
    DecipherFailed,
    #[error("rejected url")]
    RejectedUrl,
    /// ライブ配信だった。HLS で流し直す。
    #[error("live stream")]
    LiveStream,
    /// その曲だけがログインの確認を求めた（年齢確認など）。ログインが切れたとは扱わない。
    #[error("song login required")]
    SongLoginRequired,
}

impl StreamFailure {
    /// その曲だけの失敗か。そうなら知らせて次の曲へ飛ばす（どの曲でも起きる失敗では飛ばさない）。
    pub fn is_this_song_only(self) -> bool {
        matches!(self, Self::Unplayable | Self::NoAudioFormat | Self::SongLoginRequired)
    }
}

/// 解決済みのストリーム。署名付き URL を含むので、Debug に出さない。
#[derive(Clone)]
pub struct ResolvedStream {
    pub video_id: String,
    pub itag: u32,
    pub mime_type: String,
    pub content_length: Option<u64>,
    url: String,
    /// 再生履歴を送る統計の URL（音で、ログイン中に解いたときだけ）と、解いたときのログインの世代。
    stats_url: Option<String>,
    pub generation: u64,
}

impl std::fmt::Debug for ResolvedStream {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "ResolvedStream(videoId={}, itag={}, mimeType={})", self.video_id, self.itag, self.mime_type)
    }
}

impl ResolvedStream {
    /// 音声を取得するところだけが呼ぶ。ログ・エラーの文・画面に渡さない。
    pub fn reveal_url(&self) -> &str {
        &self.url
    }

    /// 試験用: URL を持たない解決済みのストリーム。
    #[doc(hidden)]
    pub fn for_test(video_id: &str, itag: u32, mime_type: &str, content_length: Option<u64>) -> Self {
        Self { video_id: video_id.into(), itag, mime_type: mime_type.into(), content_length, url: String::new(), stats_url: None, generation: 0 }
    }

    /// 再生履歴の統計の URL（送るところだけが呼ぶ。ログに出さない）。
    pub fn reveal_stats_url(&self) -> Option<&str> {
        self.stats_url.as_deref()
    }
}

/// 優先順。AAC 256kbps → AAC 128 → opus 約 256 → opus 160 → opus 70 → opus 50（Windows と同じ。GStreamer はどれも鳴らせる）。
pub const AUDIO_ITAG_PREFERENCE: [u32; 6] = [141, 140, 774, 251, 250, 249];
const UNWANTED_PARAMETERS: [&str; 2] = ["pot", "ump"];

static VIDEO_ID: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Za-z0-9_-]{11}$").unwrap());
static QUERY_NAME: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Za-z0-9_]{1,32}$").unwrap());
static N_PARAMETER: LazyLock<Regex> = LazyLock::new(|| Regex::new("([?&])n=([^&]+)").unwrap());

/// Premium 音質の形式の itag。
pub const PREMIUM_ITAGS: [u32; 2] = [141, 774];
const PREMIUM_PROBE_VIDEO_ID: &str = "dQw4w9WgXcQ";

#[derive(Clone)]
pub struct StreamResolver {
    client: InnerTube,
    cipher: PlayerCipherProvider,
}

impl StreamResolver {
    pub fn new(client: InnerTube, cipher: PlayerCipherProvider) -> Self {
        Self { client, cipher }
    }

    /// Premium 音質（AAC 256kbps・opus 約 256kbps）を使えるか（Windows・Android と同じ）。決まった1曲で Premium の形式が返るかを見る。分からなければ None。
    pub async fn premium_audio(&self) -> Option<bool> {
        let ready = self.cipher.get().await?;
        let body = json!({
            "videoId": PREMIUM_PROBE_VIDEO_ID,
            "playbackContext": { "contentPlaybackContext": { "signatureTimestamp": ready.signature_timestamp } },
            "contentCheckOk": true,
            "racyCheckOk": true,
        });
        let root = self.client.post("player", body, true, &[]).await.ok()?;
        if root.path(&["playabilityStatus", "status"]).str_() != Some("OK") {
            return None;
        }
        Some(root.path(&["streamingData", "adaptiveFormats"]).items().iter().any(|f| f.get("itag").and_then(Value::as_u64).is_some_and(|i| PREMIUM_ITAGS.contains(&(i as u32)))))
    }

    pub async fn resolve(&self, video_id: &str) -> Result<ResolvedStream, StreamFailure> {
        self.resolve_kind(video_id, StreamKind::Audio).await
    }

    /// 音（StreamKind::Audio）か、音の無い映像（StreamKind::Video。曲に重ねる MV）を解く。
    pub async fn resolve_kind(&self, video_id: &str, kind: StreamKind) -> Result<ResolvedStream, StreamFailure> {
        if !VIDEO_ID.is_match(video_id) {
            return Err(StreamFailure::Parse);
        }
        let ready = self.cipher.get().await.ok_or(StreamFailure::PlayerConfigUnavailable)?;
        let generation = self.client.generation();
        let body = json!({
            "videoId": video_id,
            "playbackContext": { "contentPlaybackContext": { "html5Preference": "HTML5_PREF_WANTS", "signatureTimestamp": ready.signature_timestamp } },
            "contentCheckOk": true,
            "racyCheckOk": true,
            "videoCheckOk": true,
        });
        let root = self.client.post("player", body, true, &[]).await.map_err(|f| match f {
            Failure::NotLoggedIn => StreamFailure::NotLoggedIn,
            Failure::Network => StreamFailure::Network,
            Failure::Parse => StreamFailure::Parse,
            Failure::Http(_) => StreamFailure::Http,
        })?;
        // ライブ配信は HLS で流す。WEB_REMIX の応答では再生できない
        if root.path(&["videoDetails", "isLive"]).and_then(Value::as_bool) == Some(true) {
            return Err(StreamFailure::LiveStream);
        }
        match root.path(&["playabilityStatus", "status"]).str_() {
            Some("OK") => {}
            // 年齢確認などで曲ごとに返る。その曲だけの失敗にする
            Some("LOGIN_REQUIRED") => return Err(StreamFailure::SongLoginRequired),
            _ => return Err(StreamFailure::Unplayable),
        }
        let formats = root.path(&["streamingData", "adaptiveFormats"]).items();
        let candidates = match kind {
            StreamKind::Audio => audio_candidates(&formats),
            StreamKind::Video => video_candidates(&formats),
        };
        if candidates.is_empty() {
            let sabr_only = !formats.is_empty() && formats.iter().all(|f| !has_source(f));
            return Err(if sabr_only { StreamFailure::SabrOnly } else { StreamFailure::NoAudioFormat });
        }
        for format in candidates {
            let Some(url) = decipher(format, &ready.solver) else { continue };
            let url = remove_query_parameters(&url, &UNWANTED_PARAMETERS);
            if !is_allowed_media_url(&url) {
                return Err(StreamFailure::RejectedUrl);
            }
            return Ok(ResolvedStream {
                video_id: video_id.to_string(),
                itag: format.path(&["itag"]).and_then(Value::as_u64).unwrap_or(0) as u32,
                mime_type: format.path(&["mimeType"]).str_().unwrap_or("audio/mp4").to_string(),
                content_length: format.path(&["contentLength"]).str_().and_then(|s| s.parse().ok()),
                url,
                stats_url: (kind == StreamKind::Audio && self.client.is_logged_in() && self.client.generation() == generation)
                    .then(|| root.path(&["playbackTracking", "videostatsPlaybackUrl", "baseUrl"]).str_().map(String::from))
                    .flatten(),
                generation,
            });
        }
        self.cipher.invalidate().await;
        Err(StreamFailure::DecipherFailed)
    }

    /// ログインの世代（ログイン・ログアウトで変わる）。
    pub fn generation(&self) -> u64 {
        self.client.generation()
    }

    /// 解決した URL が拒否された（403 など）。次は解読の準備を取り直す。
    pub async fn on_stream_rejected(&self) {
        self.cipher.invalidate().await;
    }
}

/// 取るもの。映像は音の無い映像だけ（曲に重ねる MV）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamKind {
    Audio,
    Video,
}

/// 映像の高さの上限（右の欄・全画面でも足りる高さで、通信量を抑える）。
pub const MAX_VIDEO_HEIGHT: u64 = 720;

/// 映像だけのフォーマット。確実に鳴らせる H.264（mp4・avc1）だけを、高い方から（Windows と同じ）。
pub fn video_candidates<'a>(formats: &[&'a Value]) -> Vec<&'a Value> {
    let height = |f: &Value| f.path(&["height"]).and_then(Value::as_u64);
    let mut list: Vec<&Value> = formats
        .iter()
        .copied()
        .filter(|f| f.path(&["mimeType"]).str_().is_some_and(|m| m.starts_with("video/mp4") && m.contains("avc1")))
        .filter(|f| height(f).is_none_or(|h| h <= MAX_VIDEO_HEIGHT))
        .filter(|f| has_source(f))
        .collect();
    list.sort_by_key(|f| std::cmp::Reverse(height(f).unwrap_or(0)));
    list
}

pub fn audio_candidates<'a>(formats: &[&'a Value]) -> Vec<&'a Value> {
    let rank = |f: &Value| f.path(&["itag"]).and_then(Value::as_u64).and_then(|i| AUDIO_ITAG_PREFERENCE.iter().position(|p| *p as u64 == i));
    let mut list: Vec<&Value> = formats
        .iter()
        .copied()
        .filter(|f| f.path(&["mimeType"]).str_().is_some_and(|m| m.starts_with("audio/")))
        .filter(|f| f.path(&["isDrc"]).and_then(Value::as_bool) != Some(true))
        .filter(|f| rank(f).is_some())
        // 吹き替えなど音声トラックが複数ある曲では、既定のトラックだけを使う
        .filter(|f| f.get("audioTrack").is_none() || f.path(&["audioTrack", "audioIsDefault"]).and_then(Value::as_bool) == Some(true))
        .filter(|f| has_source(f))
        .collect();
    list.sort_by_key(|f| rank(f));
    list
}

fn has_source(f: &Value) -> bool {
    f.get("url").is_some() || f.get("signatureCipher").is_some() || f.get("cipher").is_some()
}

/// signatureCipher を解いて n を変換した URL。解けなければ None。
pub(crate) fn decipher(format: &Value, solver: &CipherSolver) -> Option<String> {
    let mut url = format.path(&["url"]).str_().map(String::from);
    let cipher_text = format.path(&["signatureCipher"]).str_().or_else(|| format.path(&["cipher"]).str_());
    if url.is_none()
        && let Some(cipher_text) = cipher_text
    {
        let params: Vec<(String, String)> = url::form_urlencoded::parse(cipher_text.as_bytes()).into_owned().collect();
        let get = |name: &str| params.iter().find(|(k, _)| k == name).map(|(_, v)| v.clone());
        let (s, base) = (get("s")?, get("url")?);
        let sp = get("sp").unwrap_or_else(|| "signature".into());
        if !is_allowed_media_url(&base) || !QUERY_NAME.is_match(&sp) {
            return None;
        }
        let solved = solver.solve_signature(&s)?;
        let joiner = if base.contains('?') { '&' } else { '?' };
        url = Some(format!("{base}{joiner}{sp}={}", encode(&solved)));
    }
    let url = url?;
    let Some(n) = N_PARAMETER.captures(&url).and_then(|c| c.get(2)) else { return Some(url) };
    let original = percent_encoding::percent_decode_str(n.as_str()).decode_utf8().ok()?.to_string();
    // n を解けない URL は遅い（または拒否される）ので使わない
    let transformed = solver.solve_n(&original)?;
    Some(format!("{}{}{}", &url[..n.start()], encode(&transformed), &url[n.end()..]))
}

fn encode(value: &str) -> String {
    percent_encoding::utf8_percent_encode(value, percent_encoding::NON_ALPHANUMERIC).to_string()
}

/// googlevideo の /videoplayback への https だけを使う。
pub fn is_allowed_media_url(url: &str) -> bool {
    url::Url::parse(url).is_ok_and(|u| {
        u.scheme() == "https"
            && u.port().is_none()
            && u.username().is_empty()
            && u.password().is_none()
            && u.host_str().is_some_and(|h| h == "googlevideo.com" || h.ends_with(".googlevideo.com"))
            && u.path() == "/videoplayback"
    })
}

/// クエリの並びと他の値の符号化を変えずに、指定した名前のパラメータだけを外す。
pub fn remove_query_parameters(url: &str, names: &[&str]) -> String {
    let Some(query_start) = url.find('?') else { return url.to_string() };
    let fragment_start = url[query_start..].find('#').map_or(url.len(), |i| query_start + i);
    let kept: Vec<&str> = url[query_start + 1..fragment_start]
        .split('&')
        .filter(|p| !p.is_empty() && !names.contains(&p.split('=').next().unwrap_or("")))
        .collect();
    let base = &url[..query_start];
    let rest = &url[fragment_start..];
    if kept.is_empty() { format!("{base}{rest}") } else { format!("{base}?{}{rest}", kept.join("&")) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefers_aac_then_opus_and_skips_drc_and_dubs() {
        let formats: Value = serde_json::from_str(
            r#"[{"itag":251,"mimeType":"audio/webm","url":"u"},{"itag":140,"mimeType":"audio/mp4","url":"u"},
                {"itag":141,"mimeType":"audio/mp4","url":"u","isDrc":true},
                {"itag":250,"mimeType":"audio/webm","url":"u","audioTrack":{"audioIsDefault":false}},
                {"itag":137,"mimeType":"video/mp4","url":"u"}]"#,
        )
        .unwrap();
        let list: Vec<&Value> = formats.as_array().unwrap().iter().collect();
        let itags: Vec<u64> = audio_candidates(&list).iter().map(|f| f["itag"].as_u64().unwrap()).collect();
        assert_eq!(itags, [140, 251]);
    }

    #[test]
    fn video_candidates_are_h264_up_to_720p_highest_first() {
        let formats: Value = serde_json::from_str(
            r#"[{"itag":137,"mimeType":"video/mp4; codecs=avc1.640028","height":1080,"url":"u"},
                {"itag":136,"mimeType":"video/mp4; codecs=avc1.4d401f","height":720,"url":"u"},
                {"itag":135,"mimeType":"video/mp4; codecs=avc1.4d401e","height":480,"url":"u"},
                {"itag":247,"mimeType":"video/webm; codecs=vp9","height":720,"url":"u"},
                {"itag":398,"mimeType":"video/mp4; codecs=av01.0.05M.08","height":720,"url":"u"},
                {"itag":140,"mimeType":"audio/mp4; codecs=mp4a.40.2","height":0,"url":"u"}]"#,
        )
        .unwrap();
        let list: Vec<&Value> = formats.as_array().unwrap().iter().collect();
        let itags: Vec<u64> = video_candidates(&list).iter().map(|f| f["itag"].as_u64().unwrap()).collect();
        assert_eq!(itags, [136, 135]);
    }

    #[test]
    fn only_googlevideo_playback_urls() {
        assert!(is_allowed_media_url("https://rr1---sn-x.googlevideo.com/videoplayback?a=1"));
        assert!(!is_allowed_media_url("http://rr1.googlevideo.com/videoplayback"));
        assert!(!is_allowed_media_url("https://evil.com/videoplayback"));
        assert!(!is_allowed_media_url("https://googlevideo.com.evil.com/videoplayback"));
        assert!(!is_allowed_media_url("https://rr1.googlevideo.com:8443/videoplayback"));
    }

    #[test]
    fn removes_only_named_parameters() {
        assert_eq!(remove_query_parameters("https://h/p?a=1&pot=x&b=%2F&ump=1#f", &UNWANTED_PARAMETERS), "https://h/p?a=1&b=%2F#f");
        assert_eq!(remove_query_parameters("https://h/p?pot=x", &UNWANTED_PARAMETERS), "https://h/p");
    }
}
