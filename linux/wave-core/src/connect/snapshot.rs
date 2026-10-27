//! 引き取りのときに送るキューのスナップショット（Android 版の HandoffSnapshot・Windows 版と同じ JSON）と、その文字列の形（HandoffCodec）。
//! 端末の中の状態と認証情報は入れない。端末の外に出るので、プラットフォームに依らない形に凍結する。
//! 長さの上限は Kotlin・C# と同じく UTF-16 の数で数える。

use serde::{Deserialize, Serialize};

use super::models::connect_json;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HandoffSnapshot {
    pub schema_version: i32,
    /// 引き取りの ID（UUID）。
    pub handoff_id: String,
    pub items: Vec<HandoffItem>,
    pub current_item_id: String,
    pub position_ms: i64,
    pub playing: bool,
    pub repeat: HandoffRepeat,
    #[serde(default)]
    pub source: Option<HandoffSource>,
    #[serde(default)]
    pub continuation: Option<String>,
    #[serde(default)]
    pub radio_playlist_id: Option<String>,
    pub exhausted: bool,
    /// 今の曲を聴いた時間（送る側の単調時計で数えた値）。
    pub listened_ms: i64,
    /// 今の曲の再生履歴を送り済みか。
    pub history_sent: bool,
    /// 表示とログのためだけ（端末間の時計はずれるので判定に使わない）。
    pub captured_at_epoch_ms: i64,
}

fn is_false(value: &bool) -> bool {
    !*value
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HandoffItem {
    /// 項目ごとの ID（UUID）。
    pub item_id: String,
    pub video_id: String,
    pub title: String,
    pub artists: Vec<HandoffRef>,
    #[serde(default)]
    pub album: Option<HandoffRef>,
    #[serde(default)]
    pub duration_seconds: Option<i32>,
    #[serde(default)]
    pub thumbnail_url: Option<String>,
    pub origin: HandoffOrigin,
    /// ライブ配信か（長さもシークも無い。映す側はライブの印を出し、映像はその端末で同じ配信の HLS を読んで出す）。
    /// false のときは書かない（ライブのときだけ "isLive": true。無ければ false として読む）。
    #[serde(default, skip_serializing_if = "is_false")]
    pub is_live: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HandoffRef {
    pub name: String,
    #[serde(default)]
    pub browse_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum HandoffOrigin {
    #[serde(rename = "source")]
    Source,
    #[serde(rename = "suggestion")]
    Suggestion,
    #[serde(rename = "user")]
    UserAdded,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum HandoffRepeat {
    #[serde(rename = "off")]
    Off,
    #[serde(rename = "one")]
    One,
    #[serde(rename = "all")]
    All,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum HandoffSource {
    #[serde(rename = "radio", rename_all = "camelCase")]
    Radio { seed_video_id: String },
    #[serde(rename = "playlist", rename_all = "camelCase")]
    Playlist { playlist_id: String },
}

/// 受け取ったスナップショットの検証の結果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HandoffDecodeResult {
    Ok(HandoffSnapshot),
    /// 形式のバージョンが違う（相手を更新する必要がある）。
    UnsupportedVersion(Option<i32>),
    /// 読めない・上限を超える・矛盾している。理由は種類だけ（値を含めない）。
    Rejected(&'static str),
}

pub const SCHEMA_VERSION: i32 = 1;

/// 暗号化する前のバイト数の上限。
pub const MAX_BYTES: usize = 512 * 1024;

pub const MAX_PLAYED_ITEMS: usize = 20;
pub const MAX_UPCOMING_ITEMS: usize = 300;
const MAX_TEXT: usize = 500;
const MAX_CONTINUATION: usize = 4096;
const MAX_ARTISTS: usize = 20;
const MAX_TIME_MS: i64 = 24 * 3600 * 1000;
const THUMBNAIL_HOSTS: [&str; 3] = [".googleusercontent.com", ".ggpht.com", ".ytimg.com"];
/// RSS のエピソードの曲の ID の頭（ADR-0012。Windows 版の Music.Feed.AudioPrefix）。
const AUDIO_PREFIX: &str = "ext:";

fn utf16_len(text: &str) -> usize {
    text.encode_utf16().count()
}

fn all_chars(text: &str, allowed: impl Fn(u8) -> bool) -> bool {
    text.bytes().all(allowed)
}

fn id_char(c: u8) -> bool {
    c.is_ascii_alphanumeric() || c == b'_' || c == b'-'
}

/// YouTube の動画 ID（`[A-Za-z0-9_-]{11}`）。
fn is_video_id(id: &str) -> bool {
    id.len() == 11 && all_chars(id, id_char)
}

fn is_browse_id(id: &str) -> bool {
    (2..=64).contains(&id.len()) && all_chars(id, id_char)
}

fn is_playlist_id(id: &str) -> bool {
    (2..=128).contains(&id.len()) && all_chars(id, id_char)
}

fn is_uuid(id: &str) -> bool {
    let bytes = id.as_bytes();
    bytes.len() == 36
        && bytes.iter().enumerate().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => *c == b'-',
            _ => c.is_ascii_hexdigit(),
        })
}

fn is_host(host: &str) -> bool {
    (1..=253).contains(&host.len()) && all_chars(host, |c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'.' || c == b'-')
}

/// Java の Character.isISOControl と同じ（U+0000〜U+001F・U+007F〜U+009F）。
pub(crate) fn is_iso_control(c: char) -> bool {
    c <= '\u{1f}' || ('\u{7f}'..='\u{9f}').contains(&c)
}

/// ext: の曲なら音声の URL（https のものだけ。Windows 版の Feed.AudioUrlOf と同じ）。
fn audio_url_of(video_id: &str) -> Option<&str> {
    let url = video_id.strip_prefix(AUDIO_PREFIX)?;
    let https = url.get(..8).is_some_and(|head| head.eq_ignore_ascii_case("https://"));
    let length = utf16_len(url);
    (https && (12..=4096).contains(&length) && !url.chars().any(char::is_whitespace)).then_some(url)
}

/// キューの項目の曲の ID: YouTube の動画 ID か、RSS のエピソード（ext: + https の音声の URL。ADR-0012）。
pub fn is_item_video_id(id: &str) -> bool {
    is_video_id(id) || audio_url_of(id).is_some_and(|url| !url.chars().any(is_iso_control))
}

pub fn encode(snapshot: &HandoffSnapshot) -> String {
    connect_json::encode(snapshot)
}

/// 受け取った文字列を読み、上限と形を検証する。サムネイルのホストが合わないものは、スナップショットを拒否せずに None にする。
/// `expected_handoff_id`: 発行して待っている ID。違えば拒否。
pub fn decode(text: &str, expected_handoff_id: &str) -> HandoffDecodeResult {
    if text.len() > MAX_BYTES {
        return HandoffDecodeResult::Rejected("too_large");
    }
    // 読めない JSON は「相手を更新して」ではなく拒否
    let Ok(value) = serde_json::from_str::<serde_json::Value>(text) else {
        return HandoffDecodeResult::Rejected("unreadable");
    };
    let Some(root) = value.as_object() else {
        return HandoffDecodeResult::Rejected("unreadable");
    };
    let version = match root.get("schemaVersion") {
        None | Some(serde_json::Value::Null) => None,
        Some(v) => match v.as_i64().and_then(|n| i32::try_from(n).ok()) {
            Some(n) => Some(n),
            None => return HandoffDecodeResult::Rejected("unreadable"),
        },
    };
    if version != Some(SCHEMA_VERSION) {
        return HandoffDecodeResult::UnsupportedVersion(version);
    }
    let Some(snapshot) = connect_json::decode::<HandoffSnapshot>(text) else {
        return HandoffDecodeResult::Rejected("unreadable");
    };
    validate(snapshot, expected_handoff_id)
}

pub fn validate(snapshot: HandoffSnapshot, expected_handoff_id: &str) -> HandoffDecodeResult {
    use HandoffDecodeResult::Rejected;
    if snapshot.handoff_id != expected_handoff_id || !is_uuid(&snapshot.handoff_id) {
        return Rejected("handoff_id");
    }
    if snapshot.items.is_empty() || snapshot.items.len() > MAX_PLAYED_ITEMS + 1 + MAX_UPCOMING_ITEMS {
        return Rejected("item_count");
    }
    let ids: Vec<&str> = snapshot.items.iter().map(|i| i.item_id.as_str()).collect();
    let distinct: std::collections::HashSet<&str> = ids.iter().copied().collect();
    if distinct.len() != ids.len() || ids.iter().any(|id| !is_uuid(id)) {
        return Rejected("item_id");
    }
    let Some(current_index) = ids.iter().position(|id| *id == snapshot.current_item_id) else {
        return Rejected("current_item");
    };
    if current_index > MAX_PLAYED_ITEMS || snapshot.items.len() - 1 - current_index > MAX_UPCOMING_ITEMS {
        return Rejected("item_window");
    }
    if snapshot.listened_ms < 0 || snapshot.position_ms < 0 {
        return Rejected("negative_time");
    }
    for item in &snapshot.items {
        if !is_item_video_id(&item.video_id) {
            return Rejected("video_id");
        }
        let title = utf16_len(&item.title);
        if title == 0 || title > MAX_TEXT {
            return Rejected("title");
        }
        if item.artists.len() > MAX_ARTISTS || item.artists.iter().any(|a| !valid_ref(a)) {
            return Rejected("artist");
        }
        if item.album.as_ref().is_some_and(|a| !valid_ref(a)) {
            return Rejected("album");
        }
        if item.duration_seconds.is_some_and(|d| !(0..=24 * 3600).contains(&d)) {
            return Rejected("duration");
        }
    }
    match &snapshot.source {
        Some(HandoffSource::Radio { seed_video_id }) if !is_video_id(seed_video_id) => return Rejected("source"),
        Some(HandoffSource::Playlist { playlist_id }) if !is_playlist_id(playlist_id) => return Rejected("source"),
        _ => {}
    }
    if snapshot.radio_playlist_id.as_deref().is_some_and(|id| !is_playlist_id(id)) {
        return Rejected("radio_playlist");
    }
    if snapshot.continuation.as_deref().is_some_and(|c| utf16_len(c) > MAX_CONTINUATION || c.chars().any(is_iso_control)) {
        return Rejected("continuation");
    }
    let limit = match snapshot.items[current_index].duration_seconds {
        Some(seconds) => i64::from(seconds) * 1000,
        None => MAX_TIME_MS,
    };
    let mut snapshot = snapshot;
    for item in &mut snapshot.items {
        if item.thumbnail_url.as_deref().is_some_and(|url| !allowed_thumbnail(url)) {
            item.thumbnail_url = None;
        }
    }
    // 位置と聴いた時間を曲の長さ以下（長さが無ければ24時間以下）に切り詰める（拒否はしない）
    snapshot.position_ms = snapshot.position_ms.min(limit);
    snapshot.listened_ms = snapshot.listened_ms.min(limit);
    HandoffDecodeResult::Ok(snapshot)
}

/// 送る側の切り詰め: 今の曲より前は MAX_PLAYED_ITEMS 件、後ろは MAX_UPCOMING_ITEMS 件まで。
/// 戻りは（切り詰めた項目, 今の項目の位置, continuation を捨てたか）。
pub fn window<T>(items: &[T], current_index: usize) -> (&[T], usize, bool) {
    let start = current_index.saturating_sub(MAX_PLAYED_ITEMS);
    let end = (current_index + 1 + MAX_UPCOMING_ITEMS).min(items.len());
    (&items[start..end], current_index - start, end < items.len())
}

fn valid_ref(reference: &HandoffRef) -> bool {
    let name = utf16_len(&reference.name);
    name > 0 && name <= MAX_TEXT && reference.browse_id.as_deref().is_none_or(is_browse_id)
}

/// https・既知の画像ホストだけ（ポート・userinfo の無いもの）。
fn allowed_thumbnail(url: &str) -> bool {
    if !url.starts_with("https://") || utf16_len(url) > 2048 {
        return false;
    }
    // バックスラッシュ・空白・制御文字は、URL パーサによって区切りや別のホストとして解釈されるので、どこにあっても拒否する
    if url.chars().any(|c| c == '\\' || c.is_whitespace() || is_iso_control(c)) {
        return false;
    }
    let mut authority = &url["https://".len()..];
    for separator in ['/', '?', '#'] {
        if let Some(index) = authority.find(separator) {
            authority = &authority[..index];
        }
    }
    is_host(authority) && THUMBNAIL_HOSTS.iter().any(|h| authority.ends_with(h))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub(crate) const HANDOFF_ID: &str = "0f8fad5b-d9cb-469f-a165-70867728950e";

    /// Kotlin の CSharpInteropTest の sampleSnapshot と同じもの。
    pub(crate) fn sample_snapshot() -> HandoffSnapshot {
        HandoffSnapshot {
            schema_version: SCHEMA_VERSION,
            handoff_id: HANDOFF_ID.into(),
            items: vec![
                HandoffItem {
                    item_id: "00000000-0000-4000-8000-000000000001".into(),
                    video_id: "abcdefghijA".into(),
                    title: "夜に駆ける \"quoted\" 🎵".into(),
                    artists: vec![HandoffRef { name: "YOASOBI".into(), browse_id: Some("UCsynthetic000000000000000".into()) }],
                    album: Some(HandoffRef { name: "THE BOOK".into(), browse_id: None }),
                    duration_seconds: Some(261),
                    thumbnail_url: Some("https://lh3.googleusercontent.com/x=w120-h120".into()),
                    origin: HandoffOrigin::Source,
                    is_live: false,
                },
                HandoffItem {
                    item_id: "00000000-0000-4000-8000-000000000002".into(),
                    video_id: "abcdefghijB".into(),
                    title: "second".into(),
                    artists: vec![],
                    album: None,
                    duration_seconds: None,
                    thumbnail_url: None,
                    origin: HandoffOrigin::UserAdded,
                    is_live: false,
                },
            ],
            current_item_id: "00000000-0000-4000-8000-000000000001".into(),
            position_ms: 42_000,
            playing: true,
            repeat: HandoffRepeat::All,
            source: Some(HandoffSource::Radio { seed_video_id: "abcdefghijA".into() }),
            continuation: None,
            radio_playlist_id: Some("RDsynthetic".into()),
            exhausted: false,
            listened_ms: 30_000,
            history_sent: true,
            captured_at_epoch_ms: 1_700_000_000_000,
        }
    }

    #[test]
    fn validates_shape_and_clamps_times() {
        let text = encode(&HandoffSnapshot { position_ms: 999_999_999, ..sample_snapshot() });
        let HandoffDecodeResult::Ok(ok) = decode(&text, HANDOFF_ID) else { panic!("ok") };
        assert_eq!(261_000, ok.position_ms);
        assert_eq!(HandoffDecodeResult::Rejected("handoff_id"), decode(&text, "00000000-0000-4000-8000-000000000009"));
        assert_eq!(
            HandoffDecodeResult::UnsupportedVersion(Some(2)),
            decode(&encode(&HandoffSnapshot { schema_version: 2, ..sample_snapshot() }), HANDOFF_ID)
        );
        assert_eq!(HandoffDecodeResult::UnsupportedVersion(None), decode("{}", HANDOFF_ID));
        assert_eq!(HandoffDecodeResult::Rejected("unreadable"), decode("[", HANDOFF_ID));
        assert_eq!(HandoffDecodeResult::Rejected("unreadable"), decode(r#"{"schemaVersion":1}"#, HANDOFF_ID));
        let mut bad_thumb = sample_snapshot();
        bad_thumb.items[0].thumbnail_url = Some("https://evil.example/x.jpg".into());
        let HandoffDecodeResult::Ok(ok) = decode(&encode(&bad_thumb), HANDOFF_ID) else { panic!("ok") };
        assert_eq!(None, ok.items[0].thumbnail_url);
        let mut bad_video = sample_snapshot();
        bad_video.items = vec![HandoffItem { video_id: "short".into(), ..bad_video.items[0].clone() }];
        assert_eq!(HandoffDecodeResult::Rejected("video_id"), decode(&encode(&bad_video), HANDOFF_ID));
    }

    #[test]
    fn rss_episodes_are_items_and_live_is_written_only_when_true() {
        assert!(is_item_video_id("ext:https://feeds.example/1.mp3"));
        assert!(!is_item_video_id("ext:http://feeds.example/1.mp3"));
        assert!(!is_item_video_id("ext:https://feeds.example/1 .mp3"));
        let mut live = sample_snapshot();
        live.items[1].is_live = true;
        let text = encode(&live);
        assert_eq!(1, text.matches("\"isLive\":true").count());
        assert!(!encode(&sample_snapshot()).contains("isLive"));
    }

    #[test]
    fn window_keeps_twenty_before_and_three_hundred_after() {
        let items: Vec<i32> = (0..400).collect();
        let (window, index, dropped) = window(&items, 50);
        assert_eq!(30, window[0]);
        assert_eq!(20, index);
        assert_eq!(321, window.len());
        assert!(dropped);
    }
}
