//! 歌詞の一節から曲を探す（Android の LyricsSearch・Windows の LyricsSearch と同じ）。外部の歌詞検索サービスは使わず、
//! YouTube Music の絞り込まない検索（歌詞の一節でも曲が上に来る）の上位の曲の歌詞を取り、打った言葉を含む曲を前に出す。
//! 歌詞は再生画面と同じ LyricsService から取る。

use std::collections::{HashMap, HashSet};
use std::future::Future;

use super::SongLyrics;
use super::service::LyricsResult;
use crate::innertube::ApiResult;
use crate::music::models::Song;

/// 歌詞を照らし合わせる、検索の上位の曲の数。
pub const CANDIDATES: usize = 6;
/// 大体合っているとみなす、言葉の2文字の組が行に入っている割合。
pub const APPROXIMATE_RATIO: f64 = 0.7;

const KIND_SONG: &str = "MUSIC_VIDEO_TYPE_ATV";
const KIND_USER_VIDEO: &str = "MUSIC_VIDEO_TYPE_UGC";

/// 歌詞の中で、打った言葉が見つかった行。start_ms は時刻付きの歌詞のときだけ。exact は言葉がそのまま入っていたか（false は大体合っている）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LyricsMatch {
    pub line: String,
    pub start_ms: Option<u64>,
    pub exact: bool,
}

/// 歌詞から探した結果の1曲。matched が None なら、歌詞が取れなかったか、言葉が見つからなかった（後ろに並べる）。
#[derive(Debug, Clone, PartialEq)]
pub struct LyricsHit {
    pub song: Song,
    pub matched: Option<LyricsMatch>,
}

/// 探す。candidates は絞り込まない検索、lyrics は曲の歌詞。
pub async fn search<C, CF, L, LF>(query: &str, candidates: C, lyrics: L) -> ApiResult<Vec<LyricsHit>>
where
    C: FnOnce(String) -> CF,
    CF: Future<Output = ApiResult<Vec<Song>>>,
    L: Fn(Song) -> LF,
    LF: Future<Output = LyricsResult>,
{
    if normalize(query).is_empty() {
        return Ok(Vec::new());
    }
    let found = candidates(query.to_string()).await?;
    let picked: Vec<Song> = distinct_songs(found).into_iter().take(CANDIDATES).collect();
    let hits = futures_util::future::join_all(picked.into_iter().map(|song| {
        let fetched = lyrics(song.clone());
        async move {
            let matched = match fetched.await {
                LyricsResult::Found(l) => find(&l, query),
                _ => None,
            };
            LyricsHit { song, matched }
        }
    }))
    .await;
    Ok(rank(hits))
}

/// そのまま入っていた曲、大体合っている曲、見つからなかった曲、見つからなかった投稿者の動画（歌ってみた・歌詞動画など）の順。同じ組の中は検索の順のまま。
pub fn rank(mut hits: Vec<LyricsHit>) -> Vec<LyricsHit> {
    hits.sort_by_key(|h| match &h.matched {
        Some(m) if m.exact => 0,
        Some(_) => 1,
        None if h.song.kind.as_deref() != Some(KIND_USER_VIDEO) => 2,
        None => 3,
    });
    hits
}

/// 同じ曲の音源と MV が両方出たら、先に出た位置に音源を残す（曲として流すため）。ライブ配信は除く。
/// 同じ曲かは、曲名と最初のアーティストを正規化して比べる。
pub fn distinct_songs(songs: Vec<Song>) -> Vec<Song> {
    let mut result: Vec<Song> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    for song in songs {
        if song.is_live || result.iter().any(|s| s.video_id == song.video_id) {
            continue;
        }
        let key = format!("{}\0{}", normalize(&song.title), normalize(song.artists.first().map_or("", |a| a.name.as_str())));
        match index.get(&key) {
            None => {
                index.insert(key, result.len());
                result.push(song);
            }
            Some(&at) => {
                if song.kind.as_deref() == Some(KIND_SONG) && result[at].kind.as_deref() != Some(KIND_SONG) {
                    result[at] = song;
                }
            }
        }
    }
    result
}

/// 比べやすい形にする。小文字にし、全角の英数字を半角に、カタカナをひらがなにし、文字と数字以外（空白・句読点・記号）を除く。
/// 「i'm」と「im」、「夜に駆ける」と「夜にかける」の打ち方の違いを吸収する（漢字とかなの違いは吸収しない）。
pub fn normalize(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for raw in text.chars() {
        let mut c = raw;
        if ('！'..='～').contains(&c) {
            c = char::from_u32(c as u32 - 0xFEE0).unwrap_or(c);
        }
        if ('ァ'..='ヶ').contains(&c) {
            c = char::from_u32(c as u32 - 0x60).unwrap_or(c);
        }
        if c.is_alphanumeric() {
            out.extend(c.to_lowercase());
        }
    }
    out
}

/// 打った言葉を含む最初の行。行をまたいだ言葉も、その行と次の行をつないで探す。無ければ大体合っている行（一番近いもの）。
pub fn find(lyrics: &SongLyrics, query: &str) -> Option<LyricsMatch> {
    let lines: Vec<(String, Option<u64>)> = match lyrics {
        SongLyrics::Synced { lines, .. } => lines.iter().map(|l| (l.text.clone(), Some(l.start_ms))).collect::<Vec<_>>(),
        SongLyrics::Plain { text, .. } => text.lines().map(|t| (t.to_string(), None)).collect::<Vec<_>>(),
    }
    .into_iter()
    .filter(|(t, _)| !t.trim().is_empty())
    .collect();
    let q = normalize(query);
    if q.is_empty() || lines.is_empty() {
        return None;
    }
    let normalized: Vec<String> = lines.iter().map(|(t, _)| normalize(t)).collect();
    let spans: Vec<String> = (0..normalized.len()).map(|i| format!("{}{}", normalized[i], normalized.get(i + 1).map_or("", String::as_str))).collect();
    let hit = |i: usize, exact: bool| LyricsMatch { line: lines[i].0.trim().to_string(), start_ms: lines[i].1, exact };
    // 1行に収まる一致を先に探す（つないだ方だけで探すと、次の行だけに入っている言葉を前の行の一致にしてしまう）
    if let Some(i) = normalized.iter().position(|n| n.contains(&q)).or_else(|| spans.iter().position(|s| s.contains(&q))) {
        return Some(hit(i, true));
    }
    if q.chars().count() < 4 {
        return None;
    }
    let wanted = bigrams(&q);
    let mut best: Option<(usize, f64)> = None;
    for (i, span) in spans.iter().enumerate() {
        let have = bigrams(span);
        let ratio = wanted.iter().filter(|b| have.contains(*b)).count() as f64 / wanted.len() as f64;
        if best.is_none_or(|(_, r)| ratio > r) {
            best = Some((i, ratio));
        }
    }
    best.filter(|(_, r)| *r >= APPROXIMATE_RATIO).map(|(i, _)| hit(i, false))
}

fn bigrams(text: &str) -> HashSet<(char, char)> {
    let chars: Vec<char> = text.chars().collect();
    chars.windows(2).map(|w| (w[0], w[1])).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::innertube::Failure;
    use crate::lyrics::LyricLine;
    use crate::music::models::ArtistRef;

    fn block_on<T>(future: impl Future<Output = T>) -> T {
        tokio::runtime::Builder::new_current_thread().build().unwrap().block_on(future)
    }

    fn track(id: &str, title: &str, artist: &str, kind: &str) -> Song {
        Song {
            video_id: id.into(),
            title: title.into(),
            artists: vec![ArtistRef { name: artist.into(), browse_id: None }],
            album: None,
            duration_seconds: Some(200),
            thumbnail_url: None,
            kind: Some(kind.into()),
            is_live: false,
        }
    }

    #[test]
    fn normalize_ignores_case_width_kana_and_punctuation() {
        assert_eq!(normalize("I'm in love with the shape of you"), normalize("im in LOVE with　the shape of you!"));
        assert_eq!(normalize("ＡＢＣ　カタカナ、です。"), normalize("abc かたかなです"));
        assert_eq!(normalize(" 、。!? "), "");
    }

    #[test]
    fn finds_the_line_even_when_the_phrase_spans_two_lines() {
        let line = |start_ms, text: &str| LyricLine { start_ms, text: text.into() };
        let lyrics = SongLyrics::Synced { lines: vec![line(1_000, "沈むように"), line(3_000, "溶けてゆくように"), line(6_000, "二人だけの空が")], source: None };
        assert_eq!(find(&lyrics, "沈むように溶けてゆく"), Some(LyricsMatch { line: "沈むように".into(), start_ms: Some(1_000), exact: true }));
        assert_eq!(find(&lyrics, "二人だけの"), Some(LyricsMatch { line: "二人だけの空が".into(), start_ms: Some(6_000), exact: true }));
        let plain = SongLyrics::Plain { text: "Hello, it's me\n\nI was wondering if after all these years".into(), source: None };
        assert_eq!(find(&plain, "wondering if after").map(|m| (m.line, m.exact)), Some(("I was wondering if after all these years".into(), true)));
    }

    #[test]
    fn a_slightly_wrong_phrase_is_approximate_and_unrelated_text_is_none() {
        let lyrics = SongLyrics::Plain { text: "to tell you I'm sorry for everything that I've done".into(), source: None };
        assert_eq!(find(&lyrics, "to tell you sorry for everything i've done").map(|m| m.exact), Some(false));
        assert_eq!(find(&lyrics, "never gonna give you up"), None);
        // 短すぎる言葉は大体の一致を取らない（何にでも合ってしまう）
        assert_eq!(find(&lyrics, "toz"), None);
    }

    #[test]
    fn songs_with_the_lyrics_come_first_and_the_song_replaces_its_video() {
        let songs = vec![track("v1", "夜に駆ける", "YOASOBI", "MUSIC_VIDEO_TYPE_OMV"), track("o1", "別の曲", "B", KIND_SONG), track("a1", "夜に駆ける", "YOASOBI", KIND_SONG)];
        let hits = block_on(search(
            "溶けてゆくように",
            |_| async move { Ok(songs) },
            |song: Song| async move {
                if song.video_id == "a1" { LyricsResult::Found(SongLyrics::Plain { text: "沈むように溶けてゆくように".into(), source: None }) } else { LyricsResult::None }
            },
        ))
        .unwrap();
        assert_eq!(hits.iter().map(|h| h.song.video_id.as_str()).collect::<Vec<_>>(), ["a1", "o1"]);
        assert_eq!(hits[0].matched.as_ref().map(|m| m.line.as_str()), Some("沈むように溶けてゆくように"));
        assert!(hits[1].matched.is_none());
    }

    #[test]
    fn unmatched_user_videos_go_below_unmatched_songs() {
        let cover = LyricsHit { song: track("u1", "歌ってみた", "A", KIND_USER_VIDEO), matched: None };
        let plain = LyricsHit { song: track("a1", "曲", "A", KIND_SONG), matched: None };
        let matched = LyricsHit { song: track("u2", "歌詞動画", "A", KIND_USER_VIDEO), matched: Some(LyricsMatch { line: "行".into(), start_ms: None, exact: false }) };
        assert_eq!(rank(vec![cover.clone(), plain.clone(), matched.clone()]), vec![matched, plain, cover]);
    }

    #[test]
    fn failed_search_is_passed_on_and_blank_query_is_empty() {
        let asked = std::cell::Cell::new(false);
        let none = |_: Song| async { LyricsResult::None };
        let blank = block_on(search(
            " 。",
            |_| {
                asked.set(true);
                async { Err(Failure::Network) }
            },
            none,
        ));
        assert_eq!(blank, Ok(Vec::new()));
        assert!(!asked.get());
        let failed = block_on(search("夜に駆ける", |_| async { Err(Failure::Network) }, none));
        assert!(failed.is_err());
    }
}
