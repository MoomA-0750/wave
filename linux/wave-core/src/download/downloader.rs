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
