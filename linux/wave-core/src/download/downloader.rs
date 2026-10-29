//! 曲を丸ごと1つのファイルに取る。音質は再生と同じ（StreamResolver が選ぶ形式）。頭から区切りごとに取り、
//! 途中で止まっても続きから取れる。URL を拒否されたら（403 など）調べ直してやり直す。URL はログ・ファイルに書かない。

use super::store::{DownloadStore, DownloadedFile};
use crate::music::models::Song;
use crate::stream::range::{RangeError, RangeReader};
use crate::stream::resolver::{StreamFailure, StreamResolver};

/// 1回に取る大きさ。
pub const CHUNK_SIZE: u64 = 1024 * 1024;
/// 拒否されて調べ直す回数。
const MAX_RESOLVES: usize = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DownloadOutcome {
    Done,
    /// その曲だけ取れない（再生できない・音声の形式が無い・年齢の確認など）。この起動の間は取り直さない。
    Unavailable,
    Live,
    NotLoggedIn,
    /// 通信などの失敗（あとで取り直す）。
    Failed,
    /// 取っている途中で要らなくなった（消された）。
    Cancelled,
}

/// 1曲を取る。still_wanted が false を返したら止める。progress には取れたバイト数と全体の長さを渡す。
pub async fn download_song(
    resolver: &StreamResolver,
    store: &DownloadStore,
    song: &Song,
    still_wanted: impl Fn() -> bool,
    progress: impl Fn(u64, u64),
) -> DownloadOutcome {
    if song.is_live {
        return DownloadOutcome::Live;
    }
    for _ in 0..MAX_RESOLVES {
        let stream = match resolver.resolve(&song.video_id).await {
            Ok(stream) => stream,
            Err(StreamFailure::LiveStream) => return DownloadOutcome::Live,
            Err(StreamFailure::NotLoggedIn) => return DownloadOutcome::NotLoggedIn,
            Err(f) if f.is_this_song_only() => return DownloadOutcome::Unavailable,
            Err(_) => return DownloadOutcome::Failed,
        };
        let (itag, mime) = (stream.itag, stream.mime_type.clone());
        let reader = match RangeReader::open(stream).await {
            Ok(reader) => reader,
            Err(RangeError::Rejected(_)) => {
                resolver.on_stream_rejected().await;
                continue;
            }
            Err(_) => return DownloadOutcome::Failed,
        };
        let length = reader.length();
        let format = DownloadedFile { video_id: song.video_id.clone(), mime_type: mime, length, itag, complete: false, song: Some(song.clone()) };
        let Ok(mut offset) = store.begin(format) else { return DownloadOutcome::Failed };
        let mut rejected = false;
        while offset < length {
            if !still_wanted() {
                return DownloadOutcome::Cancelled;
            }
            match reader.read_chunk(offset, CHUNK_SIZE).await {
                Ok(bytes) if bytes.is_empty() => break,
                Ok(bytes) => {
                    if !store.append(&song.video_id, offset, &bytes) {
                        return if still_wanted() { DownloadOutcome::Failed } else { DownloadOutcome::Cancelled };
                    }
                    offset += bytes.len() as u64;
                    progress(offset, length);
                }
                Err(RangeError::Rejected(_)) => {
                    // 期限切れ・拒否: 調べ直してやり直す（取れた分はそのまま、続きから）
                    resolver.on_stream_rejected().await;
                    rejected = true;
                    break;
                }
                Err(RangeError::Network) => return DownloadOutcome::Failed,
            }
        }
        if rejected {
            continue;
        }
        return if store.complete(&song.video_id) { DownloadOutcome::Done } else { DownloadOutcome::Failed };
    }
    DownloadOutcome::Failed
}

/// RSS のエピソード（ADR-0012）を取る（Windows の DownloadEpisodeAsync と同じ）。cookie を持たず、https の間だけリダイレクトを追う。
/// Range に応じる配信元は区切りごとに（続きから取れる）、応じない配信元は頭から1回で取る。URL はログに書かない。
pub async fn download_episode(store: &DownloadStore, song: &Song, user_agent: &str, still_wanted: impl Fn() -> bool, progress: impl Fn(u64, u64)) -> DownloadOutcome {
    let Some(url) = crate::music::feed::audio_url_of(&song.video_id) else { return DownloadOutcome::Unavailable };
    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::custom(|attempt| if attempt.url().scheme() == "https" && attempt.previous().len() < 10 { attempt.follow() } else { attempt.stop() }))
        .connect_timeout(std::time::Duration::from_secs(20))
        .build()
        .expect("the TLS backend is built in");
    let get = |url: &str, range: Option<(u64, u64)>| {
        let mut request = http.get(url).header("user-agent", user_agent);
        if let Some((start, end)) = range {
            request = request.header("range", format!("bytes={start}-{end}"));
        }
        request.send()
    };
    // 先頭の1バイトで、リダイレクトの行き先と長さを知る（2回目からは行き先へ直接取りに行く）
    let Ok(probe) = get(url, Some((0, 0))).await else { return DownloadOutcome::Failed };
    let final_url = probe.url().to_string();
    if probe.url().scheme() != "https" {
        return DownloadOutcome::Failed;
    }
    let content_type = probe.headers().get("content-type").and_then(|v| v.to_str().ok()).map(String::from);
    let mime = crate::music::feed::audio_mime(content_type.as_deref(), &final_url);
    let length = (probe.status().as_u16() == 206)
        .then(|| probe.headers().get("content-range").and_then(|v| v.to_str().ok()).and_then(|r| r.rsplit('/').next()?.trim().parse::<u64>().ok()))
        .flatten()
        .filter(|l| *l > 0);
    drop(probe);
    let format = |length| DownloadedFile { video_id: song.video_id.clone(), mime_type: mime.clone(), length, itag: 0, complete: false, song: Some(song.clone()) };
    if let Some(length) = length {
        let Ok(mut offset) = store.begin(format(length)) else { return DownloadOutcome::Failed };
        while offset < length {
            if !still_wanted() {
                return DownloadOutcome::Cancelled;
            }
            let end = (offset + CHUNK_SIZE - 1).min(length - 1);
            let bytes = match get(&final_url, Some((offset, end))).await {
                Ok(r) if r.status().as_u16() == 206 => match r.bytes().await {
                    Ok(b) if !b.is_empty() => b,
                    _ => return DownloadOutcome::Failed,
                },
                _ => return DownloadOutcome::Failed,
            };
            if !store.append(&song.video_id, offset, &bytes) {
                return if still_wanted() { DownloadOutcome::Failed } else { DownloadOutcome::Cancelled };
            }
            offset += bytes.len() as u64;
            progress(offset, length);
        }
        return if store.complete(&song.video_id) { DownloadOutcome::Done } else { DownloadOutcome::Failed };
    }
    // Range に応じない: 頭から全部（長さは取り終えて分かる）
    if store.begin(format(0)).is_err() {
        return DownloadOutcome::Failed;
    }
    let Ok(mut response) = get(&final_url, None).await else { return DownloadOutcome::Failed };
    if !response.status().is_success() || response.url().scheme() != "https" {
        return DownloadOutcome::Failed;
    }
    let total = response.content_length().unwrap_or(0);
    let mut have = 0u64;
    loop {
        match response.chunk().await {
            Ok(Some(chunk)) => {
                if !still_wanted() {
                    return DownloadOutcome::Cancelled;
                }
                if !store.append(&song.video_id, have, &chunk) {
                    return DownloadOutcome::Cancelled;
                }
                have += chunk.len() as u64;
                progress(have, total);
            }
            Ok(None) => break,
            Err(_) => return DownloadOutcome::Failed,
        }
    }
    if store.complete_with_length(&song.video_id, have) { DownloadOutcome::Done } else { DownloadOutcome::Failed }
}
