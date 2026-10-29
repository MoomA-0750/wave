//! ライブラリの「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012。Windows の NewEpisodes・Android と同じ規則）。
//! YouTube の回は「6 日前」「2025/09/08」のような表示しか持たないので、だいたいの日時に直して新しい順に並べる。

use std::sync::LazyLock;

use regex::Regex;

use super::feed::{self, EpisodePosition, FeedShow};
use super::podcast::PodcastEpisode;

/// RSS の番組ごとに混ぜる回の数。
pub const PER_FEED: usize = 10;
/// これより古い RSS の回は混ぜない（YouTube の新着も数か月分まで）。
pub const MAX_AGE_DAYS: i64 = 90;

const DAY: i64 = 86_400;
const MONTH_NAMES: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

static RELATIVE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"([0-9]+)[ \t\n\x0B\f\r]*(分|時間|日|週間|か月|ヶ月|年)前").expect("valid regex"));
static ABSOLUTE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"([0-9]{4})[/年.-]([0-9]{1,2})[/月.-]([0-9]{1,2})").expect("valid regex"));

/// YouTube の新しいエピソード（新しい順）に、RSS の番組の新しい回を混ぜて新しい順にする。positions は音声の URL から聴いたところ。
pub fn merge(youtube: &[PodcastEpisode], feeds: &[FeedShow], now_epoch_seconds: i64, positions: impl Fn(&str) -> Option<EpisodePosition>) -> Vec<PodcastEpisode> {
    // 読めない表示の YouTube の回は、一つ前の回と同じ日時にする（並びを崩さない）
    let mut previous = now_epoch_seconds;
    let mut dated: Vec<(i64, PodcastEpisode)> = Vec::new();
    for episode in youtube {
        let at = episode.published_text.as_deref().and_then(|t| approximate_epoch_seconds(t, now_epoch_seconds)).unwrap_or(previous);
        previous = at;
        dated.push((at, episode.clone()));
    }
    for show in feeds {
        for e in show.episodes(0, PER_FEED) {
            if let Some(at) = e.published_epoch_seconds
                && now_epoch_seconds - at <= MAX_AGE_DAYS * DAY
            {
                dated.push((at, e.to_episode(show, positions(&e.audio_url).as_ref())));
            }
        }
    }
    // 同じ時刻なら YouTube を先に（安定な並べ替え）
    dated.sort_by_key(|(at, _)| std::cmp::Reverse(*at));
    dated.into_iter().map(|(_, e)| e).collect()
}

/// 「6 日前」「2 時間前」「2025/09/08」「2025年9月8日」をだいたいの日時（UTC のエポック秒）にする。読めなければ None。
pub fn approximate_epoch_seconds(text: &str, now_epoch_seconds: i64) -> Option<i64> {
    if let Some(relative) = RELATIVE.captures(text) {
        let n: i64 = relative[1].parse().ok()?;
        let unit = match &relative[2] {
            "分" => 60,
            "時間" => 3_600,
            "日" => DAY,
            "週間" => 7 * DAY,
            "か月" | "ヶ月" => 30 * DAY,
            _ => 365 * DAY,
        };
        return Some(now_epoch_seconds - n * unit);
    }
    let absolute = ABSOLUTE.captures(text)?;
    let month: usize = absolute[2].parse().ok()?;
    if !(1..=12).contains(&month) {
        return None;
    }
    feed::parse_rfc822(&format!("{} {} {} 00:00:00 GMT", &absolute[3], MONTH_NAMES[month - 1], &absolute[1]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::music::models::Song;

    const NOW: i64 = 1_791_853_200; // 2026-10-13 01:00 UTC

    fn youtube(id: &str, published: Option<&str>) -> PodcastEpisode {
        PodcastEpisode {
            song: Song { video_id: id.into(), title: id.into(), artists: Vec::new(), album: None, duration_seconds: None, thumbnail_url: None, kind: None, is_live: false },
            published_text: published.map(String::from),
            duration_text: None,
            description: None,
            progress_percent: None,
            show_browse_id: None,
        }
    }

    #[test]
    fn reads_youtube_dates() {
        assert_eq!(approximate_epoch_seconds("6 日前", NOW), Some(NOW - 6 * 86_400));
        assert_eq!(approximate_epoch_seconds("2 時間前", NOW), Some(NOW - 2 * 3_600));
        assert_eq!(approximate_epoch_seconds("2025/09/08", NOW), feed::parse_rfc822("8 Sep 2025 00:00:00 GMT"));
        assert_eq!(approximate_epoch_seconds("2025年9月8日", NOW), feed::parse_rfc822("8 Sep 2025 00:00:00 GMT"));
        assert_eq!(approximate_epoch_seconds("ライブ", NOW), None);
    }

    #[test]
    fn merges_newest_first() {
        let rss: String = [3i64, 10, 200]
            .iter()
            .map(|days| {
                let text = feed::date_text(NOW - days * 86_400);
                let parts: Vec<&str> = text.split('/').collect();
                let month: usize = parts[1].parse().unwrap();
                format!("<item><title>r{days}</title><pubDate>{} {} {} 01:00:00 GMT</pubDate><enclosure url=\"https://x.example/{days}.mp3\"/></item>", parts[2], MONTH_NAMES[month - 1], parts[0])
            })
            .collect();
        let show = feed::parse_feed("https://x.example/f", &format!("<rss><channel><title>R</title>{rss}</channel></rss>")).unwrap();
        let merged = merge(&[youtube("y1", Some("1 日前")), youtube("y6", Some("6 日前")), youtube("y?", None), youtube("y30", Some("2026/09/13"))], &[show], NOW, |_| None);
        // 200 日前の RSS の回は混ぜない。読めない表示の回は一つ前と同じ日時
        assert_eq!(merged.iter().map(|e| e.song.title.as_str()).collect::<Vec<_>>(), ["y1", "r3", "y6", "y?", "r10", "y30"]);
    }
}
