//! 再生の統計（YouTube Music の再生履歴に載せる）の送信（Windows の PlaybackStats・Android の ADR-0006 と同じ）。
//! 統計の URL は /player の応答のもので、music.youtube.com の /api/stats/playback に書き換えてから送る。

use crate::innertube::{CLIENT_NAME, InnerTube};
use crate::stream::resolver::ResolvedStream;

const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const SOURCE_HOSTS: [&str; 3] = ["s.youtube.com", "music.youtube.com", "www.youtube.com"];

/// 再生1回ごとの ID（16文字）。
pub fn new_cpn() -> String {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).expect("the OS random source");
    bytes.iter().map(|b| ALPHABET[(*b as usize) % ALPHABET.len()] as char).collect()
}

/// 1回の再生を報告する。送れたら HTTP の状態、送らなかったら None。
pub async fn report(client: &InnerTube, stream: &ResolvedStream, cpn: &str, position_seconds: f64) -> Option<u16> {
    let target = target(stream.reveal_stats_url()?, cpn, (position_seconds.max(0.0) * 1000.0).floor() / 1000.0)?;
    client.get_stats(&target, stream.generation).await
}

/// 送り先の URL（music.youtube.com の /api/stats/playback。cpn・cmt・ver・c を付け直す）。元の URL が決まりの形でなければ None。
pub fn target(stats_url: &str, cpn: &str, cmt: f64) -> Option<String> {
    let url = url::Url::parse(stats_url).ok()?;
    if url.scheme() != "https" || url.port().is_some() || !url.username().is_empty() || url.password().is_some() {
        return None;
    }
    if !SOURCE_HOSTS.contains(&url.host_str()?) || url.path() != "/api/stats/playback" {
        return None;
    }
    let mut query: Vec<String> =
        url.query().unwrap_or("").split('&').filter(|p| !p.is_empty()).filter(|p| !matches!(p.split('=').next(), Some("list" | "cpn" | "cmt" | "ver" | "c" | "prettyPrint"))).map(String::from).collect();
    query.extend(["prettyPrint=false".into(), "ver=2".into(), format!("c={CLIENT_NAME}"), format!("cpn={cpn}"), format!("cmt={cmt}")]);
    Some(format!("https://music.youtube.com/api/stats/playback?{}", query.join("&")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn target_moves_to_music_youtube_and_replaces_the_session_fields() {
        let url = target("https://s.youtube.com/api/stats/playback?cl=1&docid=abc&cpn=old&list=PL1&ver=1&ei=x", "CPN", 31.5).unwrap();
        assert_eq!(url, "https://music.youtube.com/api/stats/playback?cl=1&docid=abc&ei=x&prettyPrint=false&ver=2&c=WEB_REMIX&cpn=CPN&cmt=31.5");
    }

    #[test]
    fn target_rejects_other_hosts_paths_and_schemes() {
        assert!(target("https://evil.example/api/stats/playback?x=1", "c", 0.0).is_none());
        assert!(target("https://s.youtube.com/api/stats/watchtime?x=1", "c", 0.0).is_none());
        assert!(target("http://s.youtube.com/api/stats/playback?x=1", "c", 0.0).is_none());
    }

    #[test]
    fn cpn_is_16_url_safe_characters() {
        let cpn = new_cpn();
        assert_eq!(cpn.len(), 16);
        assert!(cpn.bytes().all(|b| ALPHABET.contains(&b)));
    }
}
