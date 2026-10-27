//! googlevideo の音声を、範囲を区切って少しずつ読む（Windows の RangeStream と同じ考え）。
//! 範囲を付けない取り方（丸ごと・bytes=0-）は 403 で拒まれるため、いつも終わりのある範囲で取る。

use std::time::Duration;

use super::resolver::ResolvedStream;
use crate::innertube::USER_AGENT;

/// 1回に取る大きさ（Windows と同じ 512KB）。
pub const CHUNK_SIZE: u64 = 512 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum RangeError {
    #[error("network")]
    Network,
    /// 配信元が断った（URL の期限切れ・403 など）。解き直す。
    #[error("rejected {0}")]
    Rejected(u16),
}

/// 1曲分の読み手。複製しても同じ接続を使う。
#[derive(Clone)]
pub struct RangeReader {
    http: reqwest::Client,
    stream: ResolvedStream,
    length: u64,
}

impl RangeReader {
    /// 長さが分からなければ、先頭の1バイトを取って Content-Range から知る。
    pub async fn open(stream: ResolvedStream) -> Result<Self, RangeError> {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(30)).build().expect("the TLS backend is built in");
        let mut reader = Self { http, stream, length: 0 };
        reader.length = match reader.stream.content_length {
            Some(length) if length > 0 => length,
            _ => {
                let response = reader.request(0, 0).await?;
                content_range_length(response.headers().get("content-range").and_then(|v| v.to_str().ok())).ok_or(RangeError::Rejected(response.status().as_u16()))?
            }
        };
        Ok(reader)
    }

    pub fn length(&self) -> u64 {
        self.length
    }

    pub fn video_id(&self) -> &str {
        &self.stream.video_id
    }

    /// start から、少なくとも at_least（CHUNK_SIZE に満たなければ CHUNK_SIZE）を読む。終わりで切れる。終わりを過ぎていれば空。
    pub async fn read_chunk(&self, start: u64, at_least: u64) -> Result<Vec<u8>, RangeError> {
        if start >= self.length {
            return Ok(Vec::new());
        }
        let end = (start + at_least.max(CHUNK_SIZE) - 1).min(self.length - 1);
        let response = self.request(start, end).await?;
        Ok(response.bytes().await.map_err(|_| RangeError::Network)?.to_vec())
    }

    async fn request(&self, start: u64, end: u64) -> Result<reqwest::Response, RangeError> {
        let response = self
            .http
            .get(self.stream.reveal_url())
            .header("user-agent", USER_AGENT)
            .header("range", format!("bytes={start}-{end}"))
            .send()
            .await
            .map_err(|_| RangeError::Network)?;
        match response.status().as_u16() {
            206 | 200 => Ok(response),
            status => Err(RangeError::Rejected(status)),
        }
    }
}

/// "bytes 0-0/5399765" の全体の長さ。
fn content_range_length(header: Option<&str>) -> Option<u64> {
    header?.rsplit('/').next()?.trim().parse().ok().filter(|l| *l > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_total_length_from_content_range() {
        assert_eq!(content_range_length(Some("bytes 0-0/5399765")), Some(5_399_765));
        assert_eq!(content_range_length(Some("bytes 0-0/*")), None);
        assert_eq!(content_range_length(None), None);
    }
}
