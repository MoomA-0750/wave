//! LRCLIB（lrclib.net）から時刻付きの歌詞を探す。送るのは曲名・アーティスト名・アルバム名・長さだけ（YouTube の cookie は持たない）。

use std::sync::LazyLock;
use std::time::Duration;

use regex::Regex;
use serde::Deserialize;

use super::{LyricLine, lrc};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LrclibResult {
    Found(Vec<LyricLine>),
    NotFound,
    Failed,
}

#[derive(Deserialize)]
struct Track {
    #[serde(rename = "trackName", default)]
    track_name: Option<String>,
    #[serde(rename = "artistName", default)]
    artist_name: Option<String>,
    #[serde(rename = "duration")]
    duration: Option<f64>,
    #[serde(default)]
    instrumental: bool,
    #[serde(rename = "syncedLyrics")]
    synced_lyrics: Option<String>,
}

const DURATION_TOLERANCES: [f64; 2] = [2.0, 5.0];
const USER_AGENT: &str = concat!("wave-linux/", env!("CARGO_PKG_VERSION"));

#[derive(Clone)]
pub struct LrclibClient {
    http: reqwest::Client,
    base_url: String,
}

impl Default for LrclibClient {
    fn default() -> Self {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(15)).build().expect("the TLS backend is built in");
        Self { http, base_url: "https://lrclib.net".into() }
    }
}

/// 曲の、もう1つの言語（画面が日本語なら英語、それ以外なら日本語）での曲名とアーティスト名。
/// YouTube Music では「緑黄色社会」、LRCLIB では「Ryokuoushoku Shakai」のように、言語で表記の違う曲を一致させるために使う。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OtherNames {
    pub title: String,
    pub artist: String,
}

impl LrclibClient {
    pub async fn synced(&self, title: &str, artist: &str, album: Option<&str>, duration_seconds: Option<u32>) -> LrclibResult {
        self.synced_with_other_names(title, artist, album, duration_seconds, || async { None }).await
    }

    /// synced と同じだが、検索の結果に時刻付きの候補があるのに1つも一致しなかったときだけ other_names を呼んで、
    /// 曲名・アーティスト名の別名（もう1つの言語での表記）を足してもう一度選ぶ。
    pub async fn synced_with_other_names<F, Fut>(&self, title: &str, artist: &str, album: Option<&str>, duration_seconds: Option<u32>, other_names: F) -> LrclibResult
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = Option<OtherNames>>,
    {
        match self.try_synced(title, artist, album, duration_seconds, other_names).await {
            Ok(Some(lines)) => LrclibResult::Found(lines),
            Ok(None) => LrclibResult::NotFound,
            Err(()) => LrclibResult::Failed,
        }
    }

    async fn try_synced<F, Fut>(&self, title: &str, artist: &str, album: Option<&str>, duration_seconds: Option<u32>, other_names: F) -> Result<Option<Vec<LyricLine>>, ()>
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = Option<OtherNames>>,
    {
        let mut query: Vec<(&str, String)> = vec![("track_name", title.into()), ("artist_name", artist.into())];
        if let Some(album) = album {
            query.push(("album_name", album.into()));
        }
        if let Some(d) = duration_seconds {
            query.push(("duration", d.to_string()));
        }
        let url = url::Url::parse_with_params(&format!("{}/api/get", self.base_url), &query).map_err(|_| ())?;
        let response = self.http.get(url).header("user-agent", USER_AGENT).send().await.map_err(|_| ())?;
        match response.status().as_u16() {
            404 => {}
            200 => {
                if let Some(lines) = response.json::<Track>().await.ok().and_then(|t| lines_of(&t)) {
                    return Ok(Some(lines));
                }
            }
            _ => return Err(()),
        }
        let titles = search_titles(title);
        let mut seen: Vec<Track> = Vec::new();
        for t in &titles {
            let tracks = self.search(t, Some(artist)).await?;
            if let Some(lines) = pick(&tracks, title, artist, duration_seconds).and_then(lines_of) {
                return Ok(Some(lines));
            }
            seen.extend(tracks);
        }
        // アーティスト名を付けて探すと、LRCLIB で別の表記（ローマ字など）で登録された曲は返らない。曲名だけでも探して候補に足す
        for t in &titles {
            seen.extend(self.search(t, None).await?);
        }
        Ok(pick_with_other_names(&seen, title, artist, duration_seconds, other_names).await.and_then(lines_of))
    }

    async fn search(&self, track_name: &str, artist: Option<&str>) -> Result<Vec<Track>, ()> {
        let mut query = vec![("track_name", track_name)];
        if let Some(artist) = artist {
            query.push(("artist_name", artist));
        }
        let url = url::Url::parse_with_params(&format!("{}/api/search", self.base_url), &query).map_err(|_| ())?;
        let response = self.http.get(url).header("user-agent", USER_AGENT).send().await.map_err(|_| ())?;
        if response.status().as_u16() != 200 {
            return Err(());
        }
        response.json().await.map_err(|_| ())
    }
}

/// 元の名前で選び、時刻付きの候補があるのに1つも一致しなければ、別名（other_names）を足してもう一度選ぶ。
/// 一致したとき・時刻付きの候補が無いときは other_names を呼ばない（別名を取りに行かない）。
async fn pick_with_other_names<'a, F, Fut>(tracks: &'a [Track], title: &str, artist: &str, duration_seconds: Option<u32>, other_names: F) -> Option<&'a Track>
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Option<OtherNames>>,
{
    if let Some(track) = pick(tracks, title, artist, duration_seconds) {
        return Some(track);
    }
    if !tracks.iter().any(is_synced) {
        return None;
    }
    let other = other_names().await?;
    pick_any(tracks, &[title, &other.title], &[artist, &other.artist], duration_seconds)
}

fn is_synced(track: &Track) -> bool {
    !track.instrumental && track.synced_lyrics.as_deref().is_some_and(|s| !s.trim().is_empty())
}

fn lines_of(track: &Track) -> Option<Vec<LyricLine>> {
    if track.instrumental {
        return None;
    }
    let lines = lrc::parse(track.synced_lyrics.as_deref().filter(|s| !s.is_empty())?);
    lines.iter().any(|l| !l.text.trim().is_empty()).then_some(lines)
}

/// 曲名が一致しなくても、アーティストが一致して長さがこの秒数以内なら同じ曲とみなす（日本語の曲名がローマ字・英語で登録されていることがあるため）。
const SAME_LENGTH_WITHOUT_TITLE: f64 = 2.0;

/// 検索の結果から、曲名とアーティストが一致する時刻付きのうち長さの近いものを選ぶ。長さが分からなければ最初の一致。
/// 一致するものが無ければ選ばない（長さだけが近い別の曲を選ばないため）。
fn pick<'a>(tracks: &'a [Track], title: &str, artist: &str, duration_seconds: Option<u32>) -> Option<&'a Track> {
    pick_any(tracks, &[title], &[artist], duration_seconds)
}

/// pick と同じだが、曲名・アーティストはそれぞれ titles・artists のどれかに一致すれば良い（元の名前と別名）。
fn pick_any<'a>(tracks: &'a [Track], titles: &[&str], artists: &[&str], duration_seconds: Option<u32>) -> Option<&'a Track> {
    let wanted_titles: Vec<String> = titles.iter().flat_map(|t| title_keys(t)).collect();
    let wanted_artists: Vec<String> = artists.iter().flat_map(|a| artist_keys(a)).collect();
    let duration = duration_seconds.map(f64::from);
    let matching: Vec<&Track> = tracks
        .iter()
        .filter(|t| is_synced(t))
        .filter(|t| {
            if !keys_overlap(&wanted_artists, &artist_keys(t.artist_name.as_deref().unwrap_or(""))) {
                return false;
            }
            keys_overlap(&wanted_titles, &title_keys(t.track_name.as_deref().unwrap_or("")))
                || duration.zip(t.duration).is_some_and(|(want, d)| (d - want).abs() <= SAME_LENGTH_WITHOUT_TITLE)
        })
        .collect();
    let Some(duration) = duration else { return matching.first().copied() };
    DURATION_TOLERANCES.iter().find_map(|tolerance| {
        matching
            .iter()
            .filter(|t| t.duration.is_some_and(|d| (d - duration).abs() <= *tolerance))
            .min_by(|a, b| (a.duration.unwrap() - duration).abs().total_cmp(&(b.duration.unwrap() - duration).abs()))
            .copied()
    })
}

static DASH: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s[-–—]\s").unwrap());
static FEAT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\s(?:feat\.?|ft\.|featuring)\s").unwrap());
static ARTIST_SEPARATORS: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\s*[,&、/;×]\s*|\s+(?:x|feat\.?|ft\.|featuring|vs\.?)\s+").unwrap());

/// 比べる形。NFKC（全角・半角をそろえる）にして小文字にし、文字（L）と数字（N）だけを残す。
fn match_key(text: &str) -> String {
    let gc = icu_properties::CodePointMapData::<icu_properties::props::GeneralCategory>::new();
    let kept = icu_properties::props::GeneralCategoryGroup::Letter.union(icu_properties::props::GeneralCategoryGroup::Number);
    nfkc(text).to_lowercase().chars().filter(|c| kept.contains(gc.get(*c))).collect()
}

fn nfkc(text: &str) -> String {
    icu_normalizer::ComposingNormalizerBorrowed::new_nfkc().normalize(text).into_owned()
}

/// 曲名を比べる形。そのまま、括弧の中の補足を除いた形、「 - 」の後ろを除いた形、「feat.」の後ろを除いた形。
fn title_keys(title: &str) -> Vec<String> {
    let full = nfkc(title);
    let without_brackets = BRACKETS.replace_all(&full, "").to_string();
    let before_dash = DASH.split(&without_brackets).next().unwrap_or("").to_string();
    let before_feat = FEAT.split(&before_dash).next().unwrap_or("").to_string();
    distinct_keys([full, without_brackets, before_dash, before_feat])
}

/// アーティストを比べる形。全体と、「,」「&」「feat.」「×」などで分けたそれぞれの名前。
fn artist_keys(artist: &str) -> Vec<String> {
    let full = nfkc(artist);
    let mut parts: Vec<String> = vec![full.clone()];
    parts.extend(ARTIST_SEPARATORS.split(&full).map(str::to_string));
    distinct_keys(parts)
}

fn distinct_keys(texts: impl IntoIterator<Item = String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for key in texts.into_iter().map(|t| match_key(&t)) {
        if !key.is_empty() && !out.contains(&key) {
            out.push(key);
        }
    }
    out
}

/// どれかの形が等しいか、片方がもう片方を含めば一致。
fn keys_overlap(a: &[String], b: &[String]) -> bool {
    a.iter().any(|x| b.iter().any(|y| x.contains(y.as_str()) || y.contains(x.as_str())))
}

static BRACKETS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s*[(\[（【][^)\]）】]*[)\]）】]").unwrap());

/// 検索に使う曲名。「(From "THE FIRST TAKE")」「[Remastered]」「 - Orion」のような添え書きを外したものも試す。
pub fn search_titles(title: &str) -> Vec<String> {
    let without_brackets = BRACKETS.replace_all(title, "").trim().to_string();
    let before_dash = without_brackets.split(" - ").next().unwrap_or("").trim().to_string();
    let mut out: Vec<String> = Vec::new();
    for t in [title.to_string(), without_brackets, before_dash] {
        if !t.trim().is_empty() && !out.contains(&t) {
            out.push(t);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_titles_drop_decorations() {
        assert_eq!(search_titles("夜に駆ける (From \"THE FIRST TAKE\")"), ["夜に駆ける (From \"THE FIRST TAKE\")", "夜に駆ける"]);
        assert_eq!(search_titles("Song - Orion"), ["Song - Orion", "Song"]);
    }

    fn track(title: &str, artist: &str, d: f64, synced: bool) -> Track {
        Track {
            track_name: Some(title.into()),
            artist_name: Some(artist.into()),
            duration: Some(d),
            instrumental: false,
            synced_lyrics: synced.then(|| "[00:01.00]a".into()),
        }
    }

    #[test]
    fn picks_the_closest_synced_track() {
        let tracks = [track("t", "a", 200.0, true), track("t", "a", 181.0, false), track("t", "a", 184.0, true)];
        assert_eq!(pick(&tracks, "t", "a", Some(182)).unwrap().duration, Some(184.0));
        assert!(pick(&tracks, "t", "a", Some(100)).is_none());
    }

    #[test]
    fn a_different_song_of_the_same_length_is_not_picked() {
        // 同じ長さの別の曲が先に並んでも、曲名とアーティストの合う方を選ぶ
        let tracks = [track("別の曲", "別の人", 262.0, true), track("群青", "YOASOBI", 263.0, true)];
        assert_eq!(pick(&tracks, "群青", "YOASOBI", Some(262)).unwrap().track_name.as_deref(), Some("群青"));
        // 合うものが無ければ選ばない
        assert!(pick(&tracks[..1], "群青", "YOASOBI", Some(262)).is_none());
        assert!(pick(&tracks[..1], "群青", "YOASOBI", None).is_none());
        // 曲名だけ合ってもアーティストが違えば選ばない
        assert!(pick(&[track("群青", "別の人", 262.0, true)], "群青", "YOASOBI", Some(262)).is_none());
    }

    #[test]
    fn decorations_and_width_do_not_prevent_a_match() {
        let tracks = [track("Ｓｏｎｇ", "ＡＢＣ, Someone", 200.0, true)];
        assert!(pick(&tracks, "song (feat. X) [Remix]", "abc", Some(200)).is_some());
        assert!(pick(&tracks, "Song - From \"THE FIRST TAKE\"", "abc", Some(200)).is_some());
        assert!(pick(&tracks, "Song feat. Someone", "Someone", Some(200)).is_some());
    }

    fn other(title: &str, artist: &str) -> Option<OtherNames> {
        Some(OtherNames { title: title.into(), artist: artist.into() })
    }

    /// 別名を取りに行った回数を数えながら選ぶ。
    fn pick_counting<'a>(tracks: &'a [Track], title: &str, artist: &str, d: Option<u32>, names: Option<OtherNames>) -> (Option<&'a Track>, usize) {
        let calls = std::cell::Cell::new(0);
        let picked = futures_util::FutureExt::now_or_never(pick_with_other_names(tracks, title, artist, d, || {
            calls.set(calls.get() + 1);
            async move { names }
        }))
        .unwrap();
        (picked, calls.get())
    }

    #[test]
    fn other_language_names_match_when_the_original_names_do_not() {
        // YouTube Music では「緑黄色社会」、LRCLIB では「Ryokuoushoku Shakai」
        let tracks = [track("揺れる", "Ryokuoushoku Shakai", 238.0, true), track("揺れる", "ねごと", 205.0, true)];
        let (picked, calls) = pick_counting(&tracks, "揺れる", "緑黄色社会", Some(238), other("Yureru", "Ryokuoushoku Shakai"));
        assert_eq!(picked.and_then(|t| t.artist_name.as_deref()), Some("Ryokuoushoku Shakai"));
        assert_eq!(calls, 1);
        // 曲名が別名（ローマ字）でだけ一致する
        let tracks = [track("Yureru", "Ryokuoushoku Shakai", 234.0, true)];
        assert!(pick_counting(&tracks, "揺れる", "緑黄色社会", Some(238), other("Yureru", "Ryokuoushoku Shakai")).0.is_some());
    }

    #[test]
    fn nothing_is_picked_when_the_other_names_do_not_match_either() {
        let tracks = [track("揺れる", "ねごと", 205.0, true)];
        let (picked, calls) = pick_counting(&tracks, "揺れる", "緑黄色社会", Some(238), other("Yureru", "Ryokuoushoku Shakai"));
        assert!(picked.is_none());
        assert_eq!(calls, 1);
        // 別名が取れなければ今までどおり選ばない
        let tracks = [track("揺れる", "Ryokuoushoku Shakai", 238.0, true)];
        assert!(pick_counting(&tracks, "揺れる", "緑黄色社会", Some(238), None).0.is_none());
    }

    #[test]
    fn other_names_are_not_fetched_when_a_track_matches_or_none_is_synced() {
        let tracks = [track("揺れる", "緑黄色社会", 238.0, true), track("揺れる", "Ryokuoushoku Shakai", 238.0, true)];
        let (picked, calls) = pick_counting(&tracks, "揺れる", "緑黄色社会", Some(238), other("Yureru", "Ryokuoushoku Shakai"));
        assert_eq!(picked.and_then(|t| t.artist_name.as_deref()), Some("緑黄色社会"));
        assert_eq!(calls, 0);
        // 時刻付きの候補が無ければ取りに行かない
        let tracks = [track("揺れる", "Ryokuoushoku Shakai", 238.0, false)];
        assert_eq!(pick_counting(&tracks, "揺れる", "緑黄色社会", Some(238), other("Yureru", "Ryokuoushoku Shakai")).1, 0);
        assert_eq!(pick_counting(&[], "揺れる", "緑黄色社会", Some(238), other("Yureru", "Ryokuoushoku Shakai")).1, 0);
    }

    #[test]
    fn a_romanized_title_matches_only_by_artist_and_close_length() {
        let tracks = [track("Gunjo", "YOASOBI", 250.0, true)];
        assert!(pick(&tracks, "群青", "YOASOBI", Some(249)).is_some());
        assert!(pick(&tracks, "群青", "YOASOBI", Some(246)).is_none());
        assert!(pick(&tracks, "群青", "YOASOBI", None).is_none());
    }
}
