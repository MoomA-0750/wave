//! アーティストのページに足すもの: チャンネルの投稿からライブ・ツアーの告知を拾う（Windows の LiveNews・Android と同じ）、
//! 高く評価した曲からそのアーティストの曲を拾う（Windows の LibraryState.ByArtist と同じ）。

use std::sync::LazyLock;

use regex::Regex;

use super::models::{ArtistPost, Song};

static KEYWORDS: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)ライブ|ﾗｲﾌﾞ|ツアー|公演|ワンマン|チケット|開催決定|出演決定|フェス|ドーム|アリーナ|ホール|\bLIVE\b|\bTOUR\b|\bconcert|\btickets?\b|\bfestival\b").unwrap()
});

// 「1 年前」「2 years ago」など、年で数える古さ（1年以上前）
static YEARS_AGO: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\d+\s*(年前|years? ago)").unwrap());

/// ライブ・ツアー・出演の告知らしい文か。
pub fn is_live_related(text: &str) -> bool {
    KEYWORDS.is_match(text)
}

/// 1年以内の投稿か（投稿の時期の表示が無ければ、分からないので残す）。
pub fn is_recent(published_text: Option<&str>) -> bool {
    published_text.is_none_or(|t| !YEARS_AGO.is_match(t))
}

/// 1年以内の、告知らしい投稿だけ（何年も前の告知は終わっているので出さない）。
pub fn live_news(posts: Vec<ArtistPost>) -> Vec<ArtistPost> {
    posts.into_iter().filter(|p| is_recent(p.published_text.as_deref()) && is_live_related(&p.text)).collect()
}

/// 高く評価した曲のうち、そのアーティストのもの（ID が取れない行は名前で照らす。Android と同じ）。
pub fn songs_by_artist(songs: &[Song], browse_id: Option<&str>, name: &str) -> Vec<Song> {
    songs
        .iter()
        .filter(|song| {
            song.artists.iter().any(|a| match (browse_id, a.browse_id.as_deref()) {
                (Some(want), Some(id)) => want == id,
                _ => a.name.to_lowercase() == name.to_lowercase(),
            })
        })
        .cloned()
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::music::models::ArtistRef;

    fn post(text: &str, published: Option<&str>) -> ArtistPost {
        ArtistPost { post_id: "p".into(), text: text.into(), published_text: published.map(String::from), image_url: None }
    }

    #[test]
    fn only_recent_live_announcements_are_kept() {
        let kept = live_news(vec![post("台北ドーム2DAYS開催決定！", Some("2 日前")), post("新曲 10/14 配信リリース決定！", Some("2 日前"))]);
        assert_eq!(kept.len(), 1);
        assert!(is_live_related("ASIA DOME TOUR 2026"));
        assert!(!is_live_related("DELIVER"));
        // 1年以上前の告知は出さない
        assert!(live_news(vec![post("ワンマンライブ", Some("2 年前"))]).is_empty());
        assert!(is_recent(Some("11 か月前")));
        assert!(!is_recent(Some("1 year ago (edited)")));
        assert!(is_recent(None));
    }

    #[test]
    fn liked_songs_by_the_artist_match_by_id_or_by_name() {
        let song = |id: &str, artist: &str, artist_id: Option<&str>| Song {
            video_id: id.into(),
            title: id.into(),
            artists: vec![ArtistRef { name: artist.into(), browse_id: artist_id.map(String::from) }],
            album: None,
            duration_seconds: None,
            thumbnail_url: None,
            kind: None,
            is_live: false,
        };
        let liked = [song("a", "YOASOBI", Some("UC1")), song("b", "yoasobi", None), song("c", "YOASOBI", Some("UC2")), song("d", "Other", None)];
        let ids = |songs: Vec<Song>| songs.into_iter().map(|s| s.video_id).collect::<Vec<_>>();
        // ID があれば ID で（同じ名前の別人は入れない）、無い行は名前で
        assert_eq!(ids(songs_by_artist(&liked, Some("UC1"), "YOASOBI")), ["a", "b"]);
        assert_eq!(ids(songs_by_artist(&liked, None, "YOASOBI")), ["a", "b", "c"]);
    }
}
