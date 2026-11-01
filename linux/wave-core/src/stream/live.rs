//! ライブ配信（YouTube の生配信）の検索と、再生に使う HLS の解決（Windows の Live.cs・Android の LiveParsers と同じ）。
//! - 検索は YouTube（www.youtube.com）の「ライブ」の絞り込み。YouTube Music の検索にはライブの絞り込みが無い
//! - 再生は ANDROID クライアントの /player の hlsManifestUrl。WEB_REMIX・WEB は匿名では再生できず、IOS は HLS を返さない
//! www.youtube.com へは YouTube Music の cookie・SAPISIDHASH を送らない（認証情報を持たない別のクライアントで送る）。

use std::time::Duration;

use serde_json::{Value, json};

use crate::json::{JsonPath, find_all};
use crate::music::models::{ArtistRef, Song};

/// YouTube の検索の「ライブ」の絞り込み。
pub const LIVE_FILTER: &str = "EgJAAQ%3D%3D";
pub const WEB_CLIENT_VERSION: &str = "2.20261001.00.00";
pub const ANDROID_CLIENT_VERSION: &str = "20.10.38";
pub const ANDROID_USER_AGENT: &str = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
/// 映像を見るときの画質の上限（高さ）。
pub const MAX_VIDEO_HEIGHT: u32 = 720;
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;

/// HLS のマスタープレイリストの1本（画質ごとの媒体プレイリスト）。
#[derive(Clone, PartialEq, Eq)]
pub struct HlsVariant {
    pub bandwidth: u64,
    pub codecs: Option<String>,
    pub url: String,
    pub height: Option<u32>,
}

/// 署名を含む URL は出さない。
impl std::fmt::Debug for HlsVariant {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "HlsVariant(bandwidth={}, codecs={:?}, height={:?})", self.bandwidth, self.codecs, self.height)
    }
}

/// YouTube の検索（ライブで絞り込み）の結果と続き。ライブのバッジが付いた動画だけを取る。
pub fn web_search(root: &Value) -> (Vec<Song>, Option<String>) {
    let mut songs: Vec<Song> = Vec::new();
    for video in find_all(root, "videoRenderer") {
        if !is_live_now(video) {
            continue;
        }
        let Some(video_id) = video.path(&["videoId"]).str_() else { continue };
        let title: String = video.path(&["title", "runs"]).items().iter().filter_map(|r| r.path(&["text"]).str_()).collect();
        if title.trim().is_empty() || songs.iter().any(|s| s.video_id == video_id) {
            continue;
        }
        let owner = video.path(&["ownerText", "runs", "0", "text"]).str_();
        songs.push(Song {
            video_id: video_id.to_string(),
            title,
            artists: owner.map(|name| vec![ArtistRef { name: name.to_string(), browse_id: None }]).unwrap_or_default(),
            album: None,
            duration_seconds: None,
            thumbnail_url: video.path(&["thumbnail", "thumbnails"]).items().last().and_then(|t| t.path(&["url"]).str_()).map(String::from),
            kind: Some("MUSIC_VIDEO_TYPE_UGC".into()),
            is_live: true,
        });
    }
    let continuation = find_all(root, "continuationItemRenderer").into_iter().find_map(|c| c.path(&["continuationEndpoint", "continuationCommand", "token"]).str_()).map(String::from);
    (songs, continuation)
}

fn is_live_now(video: &Value) -> bool {
    video.path(&["badges"]).items().iter().any(|b| b.path(&["metadataBadgeRenderer", "style"]).str_() == Some("BADGE_STYLE_TYPE_LIVE_NOW"))
        || video.path(&["thumbnailOverlays"]).items().iter().any(|o| o.path(&["thumbnailOverlayTimeStatusRenderer", "style"]).str_() == Some("LIVE"))
}

/// /player の応答から、再生できるライブの HLS のマスタープレイリストの URL。ライブでない・再生できないなら None。
pub fn hls_manifest_url(player: &Value) -> Option<String> {
    if player.path(&["playabilityStatus", "status"]).str_() != Some("OK") {
        return None;
    }
    player.path(&["streamingData", "hlsManifestUrl"]).str_().filter(|u| is_allowed_manifest(u)).map(String::from)
}

/// マスタープレイリストの画質の一覧。相対の URL はマスターの URL を基準に直す。
pub fn variants(master: &str, master_url: &str) -> Vec<HlsVariant> {
    let lines: Vec<&str> = master.lines().map(str::trim).collect();
    let mut variants = Vec::new();
    for (index, line) in lines.iter().enumerate() {
        let Some(rest) = line.strip_prefix("#EXT-X-STREAM-INF:") else { continue };
        let attributes = attributes_of(rest);
        let uri = lines.iter().skip(index + 1).find(|l| !l.is_empty() && !l.starts_with('#'));
        let bandwidth = attributes.iter().find(|(k, _)| k == "BANDWIDTH").and_then(|(_, v)| v.parse().ok());
        if let (Some(uri), Some(bandwidth)) = (uri, bandwidth) {
            let height = attributes.iter().find(|(k, _)| k == "RESOLUTION").and_then(|(_, v)| v.split('x').nth(1)).and_then(|h| h.parse().ok());
            let codecs = attributes.iter().find(|(k, _)| k == "CODECS").map(|(_, v)| v.clone());
            variants.push(HlsVariant { bandwidth, codecs, url: resolve_url(master_url, uri), height });
        }
    }
    variants
}

fn is_aac_lc(variant: &HlsVariant) -> bool {
    variant.codecs.as_deref().is_some_and(|c| c.contains("mp4a.40.2"))
}

/// 音を聴くための1本: AAC-LC の中で最も帯域の小さいもの（映像も同じ流れに入っているので、小さい画質で通信量を抑える）。無ければ全体で最も小さいもの。
pub fn choose_audio_variant(variants: &[HlsVariant]) -> Option<&HlsVariant> {
    variants.iter().filter(|v| is_aac_lc(v)).min_by_key(|v| v.bandwidth).or_else(|| variants.iter().min_by_key(|v| v.bandwidth))
}

/// 映像を見るための1本: 高さ 720 以下で最も帯域の大きいもの（AAC-LC を先に）。高さが書かれていなければ音のための1本。
pub fn choose_video_variant(variants: &[HlsVariant]) -> Option<&HlsVariant> {
    let fitting: Vec<&HlsVariant> = variants.iter().filter(|v| v.height.is_some_and(|h| h <= MAX_VIDEO_HEIGHT)).collect();
    fitting
        .iter()
        .filter(|v| is_aac_lc(v))
        .max_by_key(|v| v.bandwidth)
        .or_else(|| fitting.iter().max_by_key(|v| v.bandwidth))
        .copied()
        .or_else(|| choose_audio_variant(variants))
}

fn attributes_of(text: &str) -> Vec<(String, String)> {
    let mut result = Vec::new();
    let mut rest = text;
    while let Some(eq) = rest.find('=') {
        let name = rest[..eq].trim().to_string();
        let after = &rest[eq + 1..];
        let (value, next) = if let Some(quoted) = after.strip_prefix('"') {
            let close = quoted.find('"').unwrap_or(quoted.len());
            let tail = &quoted[(close + 1).min(quoted.len())..];
            (quoted[..close].to_string(), tail.find(',').map_or("", |c| &tail[c + 1..]))
        } else {
            let end = after.find(',').unwrap_or(after.len());
            (after[..end].to_string(), if end < after.len() { &after[end + 1..] } else { "" })
        };
        result.push((name, value));
        rest = next;
    }
    result
}

fn resolve_url(base: &str, reference: &str) -> String {
    match url::Url::parse(base).and_then(|b| b.join(reference)) {
        Ok(url) => url.to_string(),
        Err(_) => reference.to_string(),
    }
}

/// ライブの HLS のマスター・媒体プレイリストの URL として使ってよいか（https・443・manifest.googlevideo.com の /api/manifest/hls_ 配下）。
pub fn is_allowed_manifest(text: &str) -> bool {
    plain_https(text).is_some_and(|u| u.host_str() == Some("manifest.googlevideo.com") && u.path().starts_with("/api/manifest/hls_"))
}

fn plain_https(text: &str) -> Option<url::Url> {
    let url = url::Url::parse(text).ok()?;
    (url.scheme() == "https" && url.port().is_none() && url.username().is_empty() && url.password().is_none()).then_some(url)
}

/// 解決したライブの再生先。URL は IP に結び付いた署名を含むので、Debug に出さない。
#[derive(Clone)]
pub struct LiveStream {
    pub video_id: String,
    url: String,
}

impl LiveStream {
    /// 再生器にだけ渡す。ログ・画面に出さない。
    pub fn reveal_url(&self) -> &str {
        &self.url
    }
}

impl std::fmt::Debug for LiveStream {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "LiveStream(videoId={})", self.video_id)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum LiveFailure {
    #[error("network")]
    Network,
    #[error("not live")]
    NotLive,
    #[error("no variant")]
    NoVariant,
}

/// ライブ配信の検索と HLS の解決。認証情報を持たないクライアントで www.youtube.com へ送る。
#[derive(Clone)]
pub struct LiveClient {
    http: reqwest::Client,
    hl: String,
    gl: String,
}

impl LiveClient {
    pub fn new(hl: &str, gl: &str) -> Self {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(30)).build().expect("the TLS backend is built in");
        Self { http, hl: hl.to_string(), gl: gl.to_string() }
    }

    /// YouTube の検索をライブに絞る。continuation を渡したら続きのページ。読めなければ None。
    pub async fn search(&self, query: &str, continuation: Option<&str>) -> Option<(Vec<Song>, Option<String>)> {
        let mut body = json!({ "context": { "client": { "clientName": "WEB", "clientVersion": WEB_CLIENT_VERSION, "hl": self.hl, "gl": self.gl } } });
        match continuation {
            Some(token) => body["continuation"] = json!(token),
            None => {
                body["query"] = json!(query);
                body["params"] = json!(LIVE_FILTER);
            }
        }
        let request = self
            .http
            .post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("user-agent", crate::innertube::USER_AGENT)
            .header("origin", "https://www.youtube.com")
            .json(&body);
        let root = self.send_json(request).await?;
        Some(web_search(&root))
    }

    /// YouTube の browse（チャンネルのタブなど）。認証情報は送らない。読めなければ None。
    pub async fn browse(&self, browse_id: &str, params: &str) -> Option<Value> {
        let body = json!({
            "context": { "client": { "clientName": "WEB", "clientVersion": WEB_CLIENT_VERSION, "hl": self.hl, "gl": self.gl } },
            "browseId": browse_id, "params": params,
        });
        let request = self
            .http
            .post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("user-agent", crate::innertube::USER_AGENT)
            .header("origin", "https://www.youtube.com")
            .json(&body);
        self.send_json(request).await
    }

    /// YouTube の動画のページ（WEB の next。説明文・再生回数）。認証情報は送らない。読めなければ None。
    pub async fn next(&self, video_id: &str) -> Option<Value> {
        let body = json!({
            "context": { "client": { "clientName": "WEB", "clientVersion": WEB_CLIENT_VERSION, "hl": self.hl, "gl": self.gl } },
            "videoId": video_id,
        });
        let request = self
            .http
            .post("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("user-agent", crate::innertube::USER_AGENT)
            .header("origin", "https://www.youtube.com")
            .json(&body);
        self.send_json(request).await
    }

    /// ライブの HLS（音を聴くための1本、video なら映像を見るための1本の媒体プレイリスト）を解決する。
    pub async fn resolve(&self, video_id: &str, video: bool) -> Result<LiveStream, LiveFailure> {
        let body = json!({
            "context": { "client": {
                "clientName": "ANDROID", "clientVersion": ANDROID_CLIENT_VERSION, "androidSdkVersion": 30,
                "osName": "Android", "osVersion": "11", "hl": self.hl, "gl": self.gl,
            } },
            "videoId": video_id, "contentCheckOk": true, "racyCheckOk": true,
        });
        let request = self
            .http
            .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("user-agent", ANDROID_USER_AGENT)
            .header("x-youtube-client-name", "3")
            .header("x-youtube-client-version", ANDROID_CLIENT_VERSION)
            .json(&body);
        let player = self.send_json(request).await.ok_or(LiveFailure::Network)?;
        let master_url = hls_manifest_url(&player).ok_or(LiveFailure::NotLive)?;
        let response = self.http.get(&master_url).send().await.map_err(|_| LiveFailure::Network)?;
        if !response.status().is_success() {
            return Err(LiveFailure::Network);
        }
        let master = read_limited(response).await.ok_or(LiveFailure::Network)?;
        let master = String::from_utf8(master).map_err(|_| LiveFailure::Network)?;
        let all = variants(&master, &master_url);
        let chosen = if video { choose_video_variant(&all) } else { choose_audio_variant(&all) };
        let variant = chosen.filter(|v| is_allowed_manifest(&v.url)).ok_or(LiveFailure::NoVariant)?;
        Ok(LiveStream { video_id: video_id.to_string(), url: variant.url.clone() })
    }

    async fn send_json(&self, request: reqwest::RequestBuilder) -> Option<Value> {
        let response = request.send().await.ok()?;
        if !response.status().is_success() {
            return None;
        }
        serde_json::from_slice(&read_limited(response).await?).ok()
    }
}

async fn read_limited(mut response: reqwest::Response) -> Option<Vec<u8>> {
    let mut buffer = Vec::new();
    while let Some(chunk) = response.chunk().await.ok()? {
        if buffer.len() + chunk.len() > MAX_RESPONSE_BYTES {
            return None;
        }
        buffer.extend_from_slice(&chunk);
    }
    Some(buffer)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> String {
        std::fs::read_to_string(format!("{}/../../core/src/desktopTest/resources/fixtures/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
    }

    #[test]
    fn web_search_keeps_only_streams_that_are_live_now() {
        let root: Value = serde_json::from_str(&fixture("search_live_web.json")).unwrap();
        let (songs, _) = web_search(&root);
        assert!(!songs.is_empty());
        assert!(songs.iter().all(|s| s.is_live && !s.title.is_empty()));
    }

    #[test]
    fn android_player_gives_an_allowed_manifest() {
        let player: Value = serde_json::from_str(&fixture("player_live_android.json")).unwrap();
        let url = hls_manifest_url(&player).unwrap();
        assert!(is_allowed_manifest(&url));
    }

    #[test]
    fn master_playlist_picks_the_smallest_aac_for_audio_and_up_to_720p_for_video() {
        let master = fixture("hls_master.m3u8");
        let all = variants(&master, "https://manifest.googlevideo.com/api/manifest/hls_variant/x/index.m3u8");
        assert!(all.len() > 2);
        let audio = choose_audio_variant(&all).unwrap();
        assert!(all.iter().filter(|v| is_aac_lc(v)).all(|v| v.bandwidth >= audio.bandwidth));
        let video = choose_video_variant(&all).unwrap();
        assert!(video.height.is_some_and(|h| h <= MAX_VIDEO_HEIGHT));
        assert!(all.iter().all(|v| is_allowed_manifest(&v.url)));
    }

    #[test]
    fn manifests_must_be_plain_https_on_the_manifest_host() {
        assert!(is_allowed_manifest("https://manifest.googlevideo.com/api/manifest/hls_playlist/x"));
        assert!(!is_allowed_manifest("http://manifest.googlevideo.com/api/manifest/hls_playlist/x"));
        assert!(!is_allowed_manifest("https://manifest.googlevideo.com:8443/api/manifest/hls_playlist/x"));
        assert!(!is_allowed_manifest("https://evil.example/api/manifest/hls_playlist/x"));
    }

    #[test]
    fn quoted_attributes_with_commas_are_read() {
        let attrs = attributes_of(r#"BANDWIDTH=290288,CODECS="mp4a.40.5,avc1.42C00B",RESOLUTION=256x144"#);
        assert_eq!(attrs[1], ("CODECS".to_string(), "mp4a.40.5,avc1.42C00B".to_string()));
        assert_eq!(attrs[2], ("RESOLUTION".to_string(), "256x144".to_string()));
    }
}
