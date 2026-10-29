//! RSS で配られるポッドキャスト（ADR-0012。Windows の Feed.cs・FeedXml.cs・PodcastFeedClient.cs、Android の Feed と同じ規則）。
//! 番組の目印は rss: + フィードの URL、エピソードの曲の ID は ext: + 音声の URL。ext: の曲は YouTube に関わる処理をせず、音声の URL をそのまま流す。
//! フィードは木を作らず、find で目当てのタグへ飛んで要る要素だけを拾う（壊れた入力・宣言の無い接頭辞・HTML の実体参照でも止まらない）。

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::Value;

use super::models::{ArtistRef, SearchPage, Song};
use super::podcast::{PodcastEpisode, PodcastPage, PodcastSummary};

pub const SHOW_PREFIX: &str = "rss:";
pub const AUDIO_PREFIX: &str = "ext:";

/// Apple の公開の目録（キー不要）。
pub const DIRECTORY_URL: &str = "https://itunes.apple.com/search";
/// 長く続く番組のフィードは数 MB になる。これを超えたら読まない。
pub const MAX_FEED_BYTES: usize = 40 * 1024 * 1024;
const MAX_DIRECTORY_BYTES: usize = 4 * 1024 * 1024;
/// 番組のページのエピソードの数（続きもこの数ずつ）。
pub const PAGE_SIZE: usize = 30;
pub const CONTINUATION_PREFIX: &str = "feed:";
/// フィードを並べて読む数。
const PARALLEL_FEEDS: usize = 4;

pub fn show_browse_id(feed_url: &str) -> String {
    format!("{SHOW_PREFIX}{feed_url}")
}

/// rss: の番組ならフィードの URL。
pub fn feed_url_of(browse_id: &str) -> Option<&str> {
    browse_id.strip_prefix(SHOW_PREFIX)
}

pub fn is_feed_show(browse_id: &str) -> bool {
    browse_id.starts_with(SHOW_PREFIX)
}

pub fn audio_video_id(audio_url: &str) -> String {
    format!("{AUDIO_PREFIX}{audio_url}")
}

/// ext: の曲なら音声の URL（https のものだけ）。GStreamer にはこれをそのまま渡す。
pub fn audio_url_of(video_id: &str) -> Option<&str> {
    video_id.strip_prefix(AUDIO_PREFIX).filter(|url| is_allowed_feed_url(url))
}

/// YouTube の曲ではない（ストリームの解決・履歴・評価・歌詞・ラジオをしない）。
pub fn is_external(video_id: &str) -> bool {
    video_id.starts_with(AUDIO_PREFIX)
}

/// 音声の種類。応答の種類が音声ならそれを、違えば URL の拡張子から決める。
pub fn audio_mime(content_type: Option<&str>, url: &str) -> String {
    if let Some(kind) = content_type
        && kind.get(..6).is_some_and(|p| p.eq_ignore_ascii_case("audio/"))
    {
        return kind.to_string();
    }
    let path = url::Url::parse(url).map(|u| u.path().to_lowercase()).unwrap_or_default();
    let extension = std::path::Path::new(&path).extension().and_then(|e| e.to_str()).unwrap_or("");
    match extension {
        "m4a" | "mp4" | "m4b" => "audio/mp4",
        "aac" => "audio/aac",
        "ogg" | "oga" | "opus" => "audio/ogg",
        "wav" => "audio/wav",
        _ => "audio/mpeg",
    }
    .to_string()
}

/// 取りに行ってよい URL（https だけ。ADR-0012）。
pub fn is_allowed_feed_url(url: &str) -> bool {
    let length = url.chars().count();
    url.get(..8).is_some_and(|p| p.eq_ignore_ascii_case("https://")) && (12..=4096).contains(&length) && !url.chars().any(char::is_whitespace)
}

/// 打ち込まれた URL を整える（feed://・http://・スキーム無しを https にする）。使えなければ None。
pub fn normalize_feed_url(input: &str) -> Option<String> {
    let trimmed = input.trim();
    let starts = |prefix: &str| trimmed.get(..prefix.len()).is_some_and(|p| p.eq_ignore_ascii_case(prefix));
    let url = if starts("https://") {
        trimmed.to_string()
    } else if starts("http://") || starts("feed://") {
        format!("https://{}", &trimmed[7..])
    } else if starts("feed:") {
        normalize_feed_url(&trimmed[5..])?
    } else if trimmed.contains("://") {
        return None;
    } else if trimmed.contains('.') {
        format!("https://{trimmed}")
    } else {
        return None;
    };
    is_allowed_feed_url(&url).then_some(url)
}

/// RSS で登録した番組（端末ごとに覚える）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedSubscription {
    pub feed_url: String,
    pub title: String,
    pub author: Option<String>,
    pub thumbnail_url: Option<String>,
    /// 登録した時刻（エポックのミリ秒）。
    pub added_at_ms: i64,
}

impl FeedSubscription {
    pub fn new(feed_url: &str, title: &str) -> Self {
        Self { feed_url: feed_url.to_string(), title: title.to_string(), author: None, thumbnail_url: None, added_at_ms: 0 }
    }

    pub fn summary(&self) -> PodcastSummary {
        PodcastSummary { browse_id: show_browse_id(&self.feed_url), title: self.title.clone(), author: self.author.clone(), thumbnail_url: self.thumbnail_url.clone() }
    }
}

/// エピソードの聴いたところ（音声の URL ごと）。finished は最後まで（残り30秒未満まで）聴いた。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EpisodePosition {
    pub position_seconds: i32,
    pub duration_seconds: Option<i32>,
    pub finished: bool,
    pub updated_at_ms: i64,
}

impl EpisodePosition {
    pub const FINISHED_MARGIN_SECONDS: i32 = 30;

    pub fn of(position_seconds: i32, duration_seconds: Option<i32>, now_ms: i64) -> Self {
        let finished = duration_seconds.is_some_and(|d| d > 0 && d - position_seconds < Self::FINISHED_MARGIN_SECONDS);
        Self { position_seconds: position_seconds.max(0), duration_seconds, finished, updated_at_ms: now_ms }
    }

    /// 0〜100。分からなければ None。
    pub fn percent(&self) -> Option<u8> {
        if self.finished {
            return Some(100);
        }
        let d = self.duration_seconds.filter(|d| *d > 0)?;
        let p = (i64::from(self.position_seconds) * 100 / i64::from(d)).clamp(0, 100) as u8;
        (p > 0).then_some(p)
    }

    /// 続きから流す位置（聴き終えていれば頭から）。
    pub fn resume_seconds(&self) -> i32 {
        if self.finished { 0 } else { self.position_seconds }
    }
}

/// フィードのエピソード1回分。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedEpisode {
    pub title: String,
    pub audio_url: String,
    pub published_epoch_seconds: Option<i64>,
    pub duration_seconds: Option<u32>,
    pub description: Option<String>,
    pub thumbnail_url: Option<String>,
}

impl FeedEpisode {
    /// 番組の行の形にする。曲の ID は ext: + 音声の URL。
    pub fn to_episode(&self, show: &FeedShow, position: Option<&EpisodePosition>) -> PodcastEpisode {
        PodcastEpisode {
            song: Song {
                video_id: audio_video_id(&self.audio_url),
                title: self.title.clone(),
                artists: vec![ArtistRef { name: show.title.clone(), browse_id: None }],
                album: None,
                duration_seconds: self.duration_seconds,
                thumbnail_url: self.thumbnail_url.clone().or_else(|| show.thumbnail_url.clone()),
                kind: None,
                is_live: false,
            },
            published_text: self.published_epoch_seconds.map(date_text),
            duration_text: self.duration_seconds.map(duration_text),
            description: self.description.clone(),
            progress_percent: position.and_then(EpisodePosition::percent),
            show_browse_id: Some(show_browse_id(&show.feed_url)),
        }
    }
}

/// 読んだ RSS の番組。エピソードは新しい順。エピソードは要るところだけ読む（数百回分の番組を、開くたびに全部ほどかない）。
pub struct FeedShow {
    pub feed_url: String,
    pub title: String,
    pub author: Option<String>,
    pub description: Option<String>,
    pub thumbnail_url: Option<String>,
    items: Vec<XmlRange>,
    parsed: Mutex<HashMap<usize, Option<FeedEpisode>>>,
}

impl std::fmt::Debug for FeedShow {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // フィードの本文は大きいので出さない
        f.debug_struct("FeedShow").field("title", &self.title).field("items", &self.items.len()).finish_non_exhaustive()
    }
}

impl FeedShow {
    /// エピソードの項目の数（音声の無いものも数える）。
    pub fn item_count(&self) -> usize {
        self.items.len()
    }

    pub fn subscription(&self) -> FeedSubscription {
        FeedSubscription { feed_url: self.feed_url.clone(), title: self.title.clone(), author: self.author.clone(), thumbnail_url: self.thumbnail_url.clone(), added_at_ms: 0 }
    }

    /// from 番目から count 件の項目のうち、エピソードとして読めたもの。
    pub fn episodes(&self, from: usize, count: usize) -> Vec<FeedEpisode> {
        let mut parsed = self.parsed.lock().unwrap_or_else(|e| e.into_inner());
        (from..self.items.len().min(from.saturating_add(count)))
            .filter_map(|index| parsed.entry(index).or_insert_with(|| parse_episode(&self.items[index])).clone())
            .collect()
    }

    /// 全部のエピソード（確かめ用）。
    pub fn all_episodes(&self) -> Vec<FeedEpisode> {
        self.episodes(0, self.items.len())
    }

    /// 番組のページの形にする。エピソードは count 件の項目まで（saved は登録しているか）。positions は音声の URL から聴いたところ。
    pub fn to_page(&self, saved: Option<bool>, count: usize, positions: impl Fn(&str) -> Option<EpisodePosition>) -> PodcastPage {
        PodcastPage {
            browse_id: show_browse_id(&self.feed_url),
            title: self.title.clone(),
            author: self.author.clone(),
            description: self.description.clone(),
            thumbnail_url: self.thumbnail_url.clone(),
            episodes: self.episodes(0, count).iter().map(|e| e.to_episode(self, positions(&e.audio_url).as_ref())).collect(),
            continuation: self.continuation_after(count),
            saved,
        }
    }

    /// 続き（token は to_page・前の続きが返したもの）。
    pub fn more(&self, token: &str, positions: impl Fn(&str) -> Option<EpisodePosition>) -> SearchPage<PodcastEpisode> {
        match continuation_index(token).filter(|from| *from <= self.items.len()) {
            Some(from) => SearchPage {
                items: self.episodes(from, PAGE_SIZE).iter().map(|e| e.to_episode(self, positions(&e.audio_url).as_ref())).collect(),
                continuation: self.continuation_after(from + PAGE_SIZE),
            },
            None => SearchPage { items: Vec::new(), continuation: None },
        }
    }

    /// 続きのトークンから、そこまでに読んだ項目の数（続きを読んだ後に作り直すため）。
    pub fn loaded_after(token: &str) -> Option<usize> {
        continuation_index(token).map(|from| from + PAGE_SIZE)
    }

    fn continuation_after(&self, count: usize) -> Option<String> {
        (count < self.items.len()).then(|| format!("{CONTINUATION_PREFIX}{count}"))
    }
}

fn continuation_index(token: &str) -> Option<usize> {
    token.strip_prefix(CONTINUATION_PREFIX)?.trim().parse().ok()
}

/// RSS 2.0（iTunes の拡張つき）を読む。番組として読めなければ None。音声の無い項目は落とす。
pub fn parse_feed(feed_url: &str, xml: &str) -> Option<FeedShow> {
    let source: Arc<str> = Arc::from(xml);
    let channel = element(&source, "channel", 0, source.len())?;
    let items = channel.children("item");
    // 番組の欄は最初のエピソードより前だけを見る（エピソードの itunes:author などを拾わない）
    let head_end = items.first().map_or(channel.end, |first| channel.start.max(first.start - 1));
    let head = XmlRange { source: source.clone(), start: channel.start, end: head_end, attributes: HashMap::new() }.detached();
    let title = head.child_text("title", Some("image"))?;
    let image = head
        .child("itunes:image", None)
        .and_then(|i| i.attributes.get("href").cloned())
        .filter(|href| is_allowed_feed_url(href))
        .or_else(|| head.child("image", None).and_then(|i| i.child_text("url", None)).filter(|url| is_allowed_feed_url(url)));
    // ほとんどのフィードは新しい順。古い順（連続ものの番組）なら逆にする（端の5件ずつで、最初に日付が読めたものを比べる）
    let date_of = |range: &XmlRange| range.detached().child_text("pubDate", None).and_then(|t| parse_rfc822(&t));
    let first_date = items.iter().take(5).find_map(date_of);
    let last_date = items.iter().rev().take(5).find_map(date_of);
    let mut ordered = items;
    if let (Some(first), Some(last)) = (first_date, last_date)
        && first < last
    {
        ordered.reverse();
    }
    let description = head.child_text("itunes:summary", None).or_else(|| head.child_text("description", None)).map(|d| plain_text(&d)).filter(|d| !d.is_empty());
    Some(FeedShow {
        feed_url: feed_url.to_string(),
        title,
        author: head.child_text("itunes:author", None).or_else(|| head.child_text("author", None)).or_else(|| head.child_text("managingEditor", None)),
        description,
        thumbnail_url: image,
        items: ordered,
        parsed: Mutex::new(HashMap::new()),
    })
}

/// エピソード1回分。音声・題が無ければ None。
fn parse_episode(range: &XmlRange) -> Option<FeedEpisode> {
    let item = range.detached();
    let enclosure = item.child("enclosure", None)?.attributes.get("url")?.trim().to_string();
    let audio = upgrade_to_https(&enclosure);
    if !is_allowed_feed_url(&audio) {
        return None;
    }
    let title = item.child_text("title", None).or_else(|| item.child_text("itunes:title", None))?;
    let description = item
        .child_text("itunes:summary", None)
        .or_else(|| item.child_text("description", None))
        .or_else(|| item.child_text("content:encoded", None))
        .map(|d| plain_text(&d))
        .filter(|d| !d.is_empty());
    let thumbnail = item.child("itunes:image", None).and_then(|i| i.attributes.get("href").cloned()).filter(|t| is_allowed_feed_url(t));
    Some(FeedEpisode {
        title,
        audio_url: audio,
        published_epoch_seconds: item.child_text("pubDate", None).and_then(|d| parse_rfc822(&d)),
        duration_seconds: item.child_text("itunes:duration", None).and_then(|d| parse_duration(&d)),
        description,
        thumbnail_url: thumbnail,
    })
}

/// 音声は https でだけ取る。http の URL は同じ場所の https を試す（多くの配信元は両方で返す）。
fn upgrade_to_https(url: &str) -> String {
    match url.get(..7) {
        Some(p) if p.eq_ignore_ascii_case("http://") => format!("https://{}", &url[7..]),
        _ => url.to_string(),
    }
}

/// 一覧で使う説明の長さ（数百回分の番組で、長い HTML を全部ほどかない）。
const DESCRIPTION_SOURCE_LIMIT: usize = 600;

/// 改行にするタグ（閉じの p・li・見出しと br）。
const BREAK_TAGS: [&str; 11] = ["br", "br/", "/p", "/li", "/h1", "/h2", "/h3", "/h4", "/h5", "/h6", "/div"];

/// HTML の説明を、段落を残した素の文にする（最初の600字だけ）。
pub fn plain_text(html: &str) -> String {
    let source = match html.char_indices().nth(DESCRIPTION_SOURCE_LIMIT) {
        Some((at, _)) => &html[..at],
        None => html,
    };
    let mut stripped = String::with_capacity(source.len());
    let mut from = 0;
    loop {
        let Some(open) = source[from..].find('<').map(|i| i + from) else {
            stripped.push_str(&source[from..]);
            break;
        };
        stripped.push_str(&source[from..open]);
        let Some(close) = source[open..].find('>').map(|i| i + open) else { break };
        let tag = source[open + 1..close].trim();
        let name = tag.split(' ').next().unwrap_or("");
        if BREAK_TAGS.contains(&name.to_lowercase().as_str()) {
            stripped.push('\n');
        }
        from = close + 1;
    }
    // 行ごとに前後の空白を落とし、空行は2つまで
    let mut output = String::with_capacity(stripped.len());
    let mut blank = 0;
    for line in decode_entities(&stripped).split('\n') {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            blank += 1;
            continue;
        }
        if !output.is_empty() {
            output.push_str(if blank > 0 { "\n\n" } else { "\n" });
        }
        output.push_str(trimmed);
        blank = 0;
    }
    output
}

/// HH:MM:SS・MM:SS・秒。
pub fn parse_duration(text: &str) -> Option<u32> {
    let parts: Vec<&str> = text.trim().split(':').collect();
    if parts.len() > 3 {
        return None;
    }
    let mut total: i64 = 0;
    for part in parts {
        let whole = part.trim();
        let whole = whole.find('.').map_or(whole, |dot| &whole[..dot]);
        total = total * 60 + i64::from(whole.parse::<i32>().ok()?);
    }
    (total > 0 && total <= i64::from(i32::MAX)).then_some(total as u32)
}

/// 「1:02:03」「42:05」。言語に依らない形にする。
pub fn duration_text(seconds: u32) -> String {
    let (h, m, s) = (seconds / 3600, seconds % 3600 / 60, seconds % 60);
    if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m}:{s:02}") }
}

/// 「2026/10/13」（UTC の日付）。
pub fn date_text(epoch_seconds: i64) -> String {
    let (year, month, day) = civil_from_days(epoch_seconds.clamp(-62_135_596_800, 253_402_300_799).div_euclid(86_400));
    format!("{year}/{month:02}/{day:02}")
}

const MONTHS: [&str; 12] = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];

/// RFC 822 の日時（Mon, 13 Oct 2026 10:00:00 +0900）。曜日・秒・時差は省けてもよい。UTC のエポック秒。
pub fn parse_rfc822(text: &str) -> Option<i64> {
    let replaced = text.replace(',', " ");
    let tokens: Vec<&str> = replaced.split_whitespace().collect();
    let start = usize::from(tokens.first().and_then(|t| t.chars().next()).is_some_and(char::is_alphabetic));
    if tokens.len() <= start + 2 {
        return None;
    }
    let digits = |t: &str| t.bytes().all(|b| b.is_ascii_digit()).then(|| t.parse::<i32>().ok()).flatten();
    let day = digits(tokens[start])?;
    let month_token: String = tokens[start + 1].to_lowercase().chars().take(3).collect();
    let month = MONTHS.iter().position(|m| *m == month_token)? as i32 + 1;
    let mut year = digits(tokens[start + 2])?;
    if year < 100 {
        year += 2000;
    }
    let time: Vec<i64> = tokens.get(start + 3).map(|t| t.split(':').map(|n| n.trim().parse().unwrap_or(0)).collect()).unwrap_or_default();
    let offset_minutes = tokens.get(start + 4).and_then(|z| zone_offset_minutes(z)).unwrap_or(0);
    let at = |i: usize| time.get(i).copied().unwrap_or(0);
    let seconds = at(0) * 3600 + at(1) * 60 + at(2);
    Some(days_from_civil(year, month, day) * 86_400 + seconds - offset_minutes * 60)
}

fn zone_offset_minutes(zone: &str) -> Option<i64> {
    if zone.len() == 5 && (zone.starts_with('+') || zone.starts_with('-')) {
        let h: i64 = zone.get(1..3)?.parse().ok()?;
        let m: i64 = zone.get(3..5)?.parse().ok()?;
        return Some((h * 60 + m) * if zone.starts_with('-') { -1 } else { 1 });
    }
    Some(match zone.to_uppercase().as_str() {
        "GMT" | "UT" | "UTC" | "Z" => 0,
        "EST" => -300,
        "EDT" => -240,
        "CST" => -360,
        "CDT" => -300,
        "MST" => -420,
        "MDT" => -360,
        "PST" => -480,
        "PDT" => -420,
        "JST" => 540,
        _ => return None,
    })
}

// Howard Hinnant の days_from_civil（Android・Windows と同じ。月日のはみ出しも同じに数える）
fn days_from_civil(year: i32, month: i32, day: i32) -> i64 {
    let y = i64::from(if month <= 2 { year - 1 } else { year });
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = y - era * 400;
    let doy = (153 * i64::from(month + if month > 2 { -3 } else { 9 }) + 2) / 5 + i64::from(day) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

// 同じく civil_from_days（エポックからの日数を年月日に）
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = (if z >= 0 { z } else { z - 146_096 }) / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + i64::from(month <= 2), month, day)
}

// ---- XML の拾い読み（Windows の FeedXml と同じ読み方。要素の名前は接頭辞ごと、名前空間は解決しない） ----

const CDATA_OPEN: &str = "<![CDATA[";
const CDATA_CLOSE: &str = "]]>";

/// 文書の中の要素の範囲。start は中身の始まり（開きタグの > の次）、end は中身の終わり（閉じタグの <）。
#[derive(Clone)]
struct XmlRange {
    source: Arc<str>,
    start: usize,
    end: usize,
    /// 開きタグの属性。
    attributes: HashMap<String, String>,
}

impl XmlRange {
    /// 範囲の中の最初の name の要素。skip の要素の中にあるものは飛ばす（チャンネルの題と <image> の題を分ける）。
    fn child(&self, name: &str, skip: Option<&str>) -> Option<XmlRange> {
        let mut from = self.start;
        loop {
            let found = element(&self.source, name, from, self.end)?;
            if let Some(skipped) = skip.and_then(|s| element(&self.source, s, from, self.end))
                && skipped.start <= found.start
                && found.end <= skipped.end
            {
                from = skipped.end;
                continue;
            }
            return Some(found);
        }
    }

    /// 中身だけを切り出した範囲（中を何度も探すときに、文書の終わりまで探さないため）。
    fn detached(&self) -> XmlRange {
        let text = &self.source[self.start..self.end];
        XmlRange { source: Arc::from(text), start: 0, end: text.len(), attributes: self.attributes.clone() }
    }

    /// 範囲の中の name の要素を順に。
    fn children(&self, name: &str) -> Vec<XmlRange> {
        let mut output = Vec::new();
        let mut from = self.start;
        while let Some(found) = element(&self.source, name, from, self.end) {
            from = found.end;
            output.push(found);
        }
        output
    }

    /// 中の文字（CDATA はそのまま、ほかは実体参照をほどく）。前後の空白は落とす。
    fn text(&self) -> String {
        let source = &*self.source;
        let mut output = String::new();
        let mut from = self.start;
        while from < self.end {
            let Some(cdata) = find(source, CDATA_OPEN, from).filter(|c| *c < self.end) else {
                output.push_str(&decode_entities(&source[from..self.end]));
                break;
            };
            output.push_str(&decode_entities(&source[from..cdata]));
            let close = find(source, CDATA_CLOSE, cdata).filter(|c| *c <= self.end).unwrap_or(self.end);
            let content = cdata + CDATA_OPEN.len();
            if close > content {
                output.push_str(&source[content..close]);
            }
            from = (close + CDATA_CLOSE.len()).min(self.end);
        }
        output.trim().to_string()
    }

    /// 子要素の文字。無い・空なら None。
    fn child_text(&self, name: &str, skip: Option<&str>) -> Option<String> {
        self.child(name, skip).map(|c| c.text()).filter(|t| !t.is_empty())
    }
}

/// from〜to の中の、最初の <name の要素。閉じが無ければ範囲の終わりまで、/> で閉じていれば中身は空。
/// 同じ名前の要素の入れ子は考えない（RSS の item・title などは入れ子にならない）。
fn element(source: &Arc<str>, name: &str, from: usize, to: usize) -> Option<XmlRange> {
    let text = &**source;
    let bytes = text.as_bytes();
    let open = format!("<{name}");
    let mut at = find(text, &open, from);
    while let Some(position) = at.filter(|a| *a < to) {
        let after = position + open.len();
        let next = bytes.get(after).copied().unwrap_or(b'>');
        if matches!(next, b'>' | b'/' | b' ' | b'\t' | b'\n' | b'\r') {
            let tag_end = tag_end(text, after).filter(|e| *e < to)?;
            let self_closing = bytes[tag_end - 1] == b'/';
            let attributes = attributes(&text[after..if self_closing { (tag_end - 1).max(after) } else { tag_end }]);
            if self_closing {
                return Some(XmlRange { source: source.clone(), start: tag_end + 1, end: tag_end + 1, attributes });
            }
            let close = find(text, &format!("</{name}"), tag_end + 1).filter(|c| *c <= to).unwrap_or(to);
            return Some(XmlRange { source: source.clone(), start: tag_end + 1, end: close, attributes });
        }
        at = find(text, &open, after);
    }
    None
}

/// needle の位置（無ければ None）。
fn find(source: &str, needle: &str, from: usize) -> Option<usize> {
    source.get(from..)?.find(needle).map(|i| i + from)
}

/// 開きタグの > の位置。属性の値の中の > は飛ばす。
fn tag_end(source: &str, from: usize) -> Option<usize> {
    let mut end = find(source, ">", from);
    while let Some(e) = end {
        let tag = &source.as_bytes()[from..e];
        let count = |q: u8| tag.iter().filter(|b| **b == q).count();
        if count(b'"') % 2 == 0 && count(b'\'') % 2 == 0 {
            return Some(e);
        }
        end = find(source, ">", e + 1);
    }
    None
}

/// key="value" の並び（タグの文字だけを切り出して読む）。
fn attributes(source: &str) -> HashMap<String, String> {
    let bytes = source.as_bytes();
    let to = source.len();
    let mut output = HashMap::new();
    let mut key_from = 0;
    let mut eq = source.find('=');
    while let Some(e) = eq.filter(|e| *e >= key_from && *e < to) {
        let key = source[key_from..e].trim().to_string();
        let mut v = e + 1;
        while v < to && bytes[v].is_ascii_whitespace() {
            v += 1;
        }
        if v >= to {
            break;
        }
        let quote = bytes[v];
        if quote == b'"' || quote == b'\'' {
            let value_end = find(source, if quote == b'"' { "\"" } else { "'" }, v + 1).unwrap_or(to);
            output.insert(key, decode_entities(&source[v + 1..value_end]));
            key_from = (value_end + 1).min(to);
        } else {
            let mut close = v;
            while close < to && !bytes[close].is_ascii_whitespace() {
                close += 1;
            }
            output.insert(key, decode_entities(&source[v..close]));
            key_from = close;
        }
        eq = find(source, "=", key_from);
    }
    output
}

/// 実体参照（&amp; など・数値の参照）をほどく。知らないものはそのまま残す。
pub fn decode_entities(text: &str) -> String {
    let Some(mut amp) = text.find('&') else { return text.to_string() };
    let bytes = text.as_bytes();
    let mut output = String::with_capacity(text.len());
    let mut from = 0;
    // 1字ずつ見ずに、& から & へ飛ぶ
    loop {
        output.push_str(&text[from..amp]);
        // ; は & から10字以内だけを見る（それより遠ければ参照ではない）
        let window = &bytes[amp..(amp + 11).min(bytes.len())];
        let end = window.iter().position(|b| *b == b';').map(|i| i + amp).filter(|e| *e >= amp + 2);
        match end.and_then(|e| entity(&text[amp + 1..e])) {
            Some(decoded) => {
                output.push_str(&decoded);
                from = end.unwrap_or(amp) + 1;
            }
            None => {
                output.push('&');
                from = amp + 1;
            }
        }
        match text[from..].find('&') {
            Some(next) => amp = next + from,
            None => break,
        }
    }
    output.push_str(&text[from..]);
    output
}

fn entity(name: &str) -> Option<String> {
    let code_point = |code: Option<u32>| code.and_then(char::from_u32).map(String::from);
    match name {
        "amp" => Some("&".into()),
        "lt" => Some("<".into()),
        "gt" => Some(">".into()),
        "quot" => Some("\"".into()),
        "apos" => Some("'".into()),
        "nbsp" => Some(" ".into()),
        _ if name.len() > 2 && name[..2].eq_ignore_ascii_case("#x") => {
            let hex = &name[2..];
            code_point(hex.bytes().all(|b| b.is_ascii_hexdigit()).then(|| u32::from_str_radix(hex, 16).ok()).flatten())
        }
        _ if name.starts_with('#') => {
            let digits = &name[1..];
            code_point((!digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit())).then(|| digits.parse().ok()).flatten())
        }
        _ => None,
    }
}

// ---- フィードの取得と Apple の目録 ----

/// フィードを読めなかった理由。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FeedFailure {
    /// https でない・形のおかしい URL。
    NotAllowed,
    Network,
    /// 状態コード。
    Http(u16),
    TooLarge,
    /// RSS として読めなかった。
    NotAFeed,
}

/// RSS のポッドキャストの取得（Windows の PodcastFeedClient と同じ）。番組を Apple の目録で探し、フィードを読む。
/// 認証情報・cookie を持たないクライアントを使う（ADR-0003）。検索語のほかは何も送らない。
#[derive(Clone)]
pub struct FeedClient {
    http: reqwest::Client,
    country: String,
}

impl FeedClient {
    /// country は目録の国（JP など）。user_agent は wave/版。
    pub fn new(country: &str, user_agent: &str) -> Self {
        // cookie を持たず、リダイレクトは追う（計測用の転送 podtrac などを通るため）。https_only で、追うのは https の間だけになる
        let http = reqwest::Client::builder()
            .user_agent(user_agent)
            .https_only(true)
            .redirect(reqwest::redirect::Policy::limited(10))
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60))
            .build()
            .expect("the TLS backend is built in");
        Self { http, country: country.to_string() }
    }

    /// 番組を探す。URL を打ち込まれたら、そのフィードを読んで1件にする。読めなければ None。
    pub async fn search(&self, term: &str) -> Option<Vec<PodcastSummary>> {
        let trimmed = term.trim();
        if trimmed.is_empty() {
            return Some(Vec::new());
        }
        if trimmed.contains("://") || trimmed.starts_with("feed:") {
            let Some(url) = normalize_feed_url(trimmed) else { return Some(Vec::new()) };
            return Some(self.show(&url).await.map(|show| vec![show.subscription().summary()]).unwrap_or_default());
        }
        let params = [("term", trimmed), ("media", "podcast"), ("entity", "podcast"), ("limit", "25"), ("country", self.country.as_str())];
        let url = url::Url::parse_with_params(DIRECTORY_URL, &params).ok()?;
        let response = self.http.get(url).send().await.ok()?;
        if !response.status().is_success() {
            return None;
        }
        let body = read_limited(response, MAX_DIRECTORY_BYTES).await.ok()?;
        Some(directory_results(&String::from_utf8_lossy(&body)))
    }

    /// フィードを読む。
    pub async fn show(&self, feed_url: &str) -> Result<FeedShow, FeedFailure> {
        if !is_allowed_feed_url(feed_url) {
            return Err(FeedFailure::NotAllowed);
        }
        let response = self.http.get(feed_url).send().await.map_err(|_| FeedFailure::Network)?;
        let status = response.status();
        if !status.is_success() {
            return Err(FeedFailure::Http(status.as_u16()));
        }
        if response.url().scheme() != "https" {
            return Err(FeedFailure::NotAllowed);
        }
        if response.content_length().is_some_and(|n| n > MAX_FEED_BYTES as u64) {
            return Err(FeedFailure::TooLarge);
        }
        let bytes = read_limited(response, MAX_FEED_BYTES).await?;
        // UTF-8 で読む（Android と同じ）。先頭の BOM は落とす
        let text = String::from_utf8_lossy(&bytes);
        let text = text.strip_prefix('\u{feff}').unwrap_or(&text);
        parse_feed(feed_url, text).ok_or(FeedFailure::NotAFeed)
    }

    /// 登録した番組のフィードをまとめて読む（4つずつ並べて読む）。読めなかった番組は飛ばし、並びは渡した順。
    /// 「新しいエピソード」に混ぜる前と、番組の名前・画像の更新（FeedLibrary::refresh）に使う。tokio の上で呼ぶ。
    pub async fn shows(&self, feed_urls: &[String]) -> Vec<FeedShow> {
        let gate = Arc::new(tokio::sync::Semaphore::new(PARALLEL_FEEDS));
        let mut tasks = tokio::task::JoinSet::new();
        for (index, url) in feed_urls.iter().enumerate() {
            let (client, url, gate) = (self.clone(), url.clone(), gate.clone());
            tasks.spawn(async move {
                let _permit = gate.acquire_owned().await.ok()?;
                client.show(&url).await.ok().map(|show| (index, show))
            });
        }
        let mut shows = Vec::new();
        while let Some(result) = tasks.join_next().await {
            if let Ok(Some(show)) = result {
                shows.push(show);
            }
        }
        shows.sort_by_key(|(index, _)| *index);
        shows.into_iter().map(|(_, show)| show).collect()
    }
}

/// 本文を上限まで読む（超えたら TooLarge）。
async fn read_limited(mut response: reqwest::Response, limit: usize) -> Result<Vec<u8>, FeedFailure> {
    let mut buffer = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| FeedFailure::Network)? {
        if buffer.len() + chunk.len() > limit {
            return Err(FeedFailure::TooLarge);
        }
        buffer.extend_from_slice(&chunk);
    }
    Ok(buffer)
}

/// 目録の応答（results[]）。フィードの URL の無い番組は落とす。
pub fn directory_results(body: &str) -> Vec<PodcastSummary> {
    let Ok(root) = serde_json::from_str::<Value>(body) else { return Vec::new() };
    let Some(results) = root.get("results").and_then(Value::as_array) else { return Vec::new() };
    let mut output: Vec<PodcastSummary> = Vec::new();
    for item in results.iter().filter(|i| i.is_object()) {
        let text = |key: &str| item.get(key).and_then(Value::as_str).filter(|s| !s.trim().is_empty()).map(String::from);
        let Some(feed) = text("feedUrl").and_then(|raw| normalize_feed_url(&raw)) else { continue };
        let Some(title) = text("collectionName").or_else(|| text("trackName")) else { continue };
        let art = text("artworkUrl600").or_else(|| text("artworkUrl100")).filter(|a| is_allowed_feed_url(a));
        let show = PodcastSummary { browse_id: show_browse_id(&feed), title, author: text("artistName"), thumbnail_url: art };
        if !output.iter().any(|p| p.browse_id == show.browse_id) {
            output.push(show);
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> String {
        // 手本の RSS は Android の core と共通（core/src/desktopTest/resources/fixtures）
        let path = format!("{}/../../core/src/desktopTest/resources/fixtures/feed_rss.xml", env!("CARGO_MANIFEST_DIR"));
        std::fs::read_to_string(path).unwrap()
    }

    fn no_positions(_: &str) -> Option<EpisodePosition> {
        None
    }

    #[test]
    fn parses_show_and_episodes_newest_first() {
        let show = parse_feed("https://example.com/feed.xml", &fixture()).unwrap();
        assert_eq!(show.title, "テストの番組 & 友だち");
        assert_eq!(show.author.as_deref(), Some("wave"));
        assert_eq!(show.description.as_deref(), Some("説明の一行目\n二行目"));
        assert_eq!(show.thumbnail_url.as_deref(), Some("https://example.com/show.jpg"));
        let episodes = show.all_episodes();
        assert_eq!(episodes.iter().map(|e| e.title.as_str()).collect::<Vec<_>>(), ["第2回", "第1回"]);
        let newest = &episodes[0];
        assert_eq!(newest.audio_url, "https://example.com/ep2.mp3?x=1&y=2");
        assert_eq!(newest.duration_seconds, Some(2525));
        assert_eq!(newest.thumbnail_url.as_deref(), Some("https://example.com/ep2.jpg"));
        // 説明はエスケープされた HTML で来る（タグを落とし、文の中の実体は戻す）
        assert_eq!(newest.description.as_deref(), Some("エピソードの説明 1 < 2"));
        assert_eq!(episodes[1].duration_seconds, Some(3723));
    }

    #[test]
    fn episodes_become_external_songs() {
        let show = parse_feed("https://example.com/feed.xml", &fixture()).unwrap();
        let page = show.to_page(Some(true), PAGE_SIZE, |url| url.ends_with("ep1.mp3").then(|| EpisodePosition::of(600, Some(3723), 0)));
        assert_eq!(page.browse_id, "rss:https://example.com/feed.xml");
        let first = &page.episodes[0];
        assert_eq!(first.song.video_id, "ext:https://example.com/ep2.mp3?x=1&y=2");
        assert!(is_external(&first.song.video_id));
        assert_eq!(audio_url_of(&first.song.video_id), Some("https://example.com/ep2.mp3?x=1&y=2"));
        assert_eq!(first.published_text.as_deref(), Some("2026/10/13"));
        assert_eq!(first.duration_text.as_deref(), Some("42:05"));
        assert_eq!(first.song.artists.len(), 1);
        assert_eq!(first.song.artists[0].name, "テストの番組 & 友だち");
        assert_eq!(page.episodes[1].published_text.as_deref(), Some("2026/10/05"));
        assert_eq!(page.episodes[1].duration_text.as_deref(), Some("1:02:03"));
        assert_eq!(page.episodes[1].progress_percent, Some(16));
        assert_eq!(first.progress_percent, None);
    }

    #[test]
    fn parses_rfc822_with_offsets() {
        assert_eq!(parse_rfc822("Tue, 13 Oct 2026 01:00:00 GMT"), Some(1_791_853_200));
        assert_eq!(parse_rfc822("Tue, 13 Oct 2026 10:00:00 +0900"), Some(1_791_853_200));
        assert_eq!(parse_rfc822("13 Oct 2026 01:00 GMT"), Some(1_791_853_200));
        assert_eq!(parse_rfc822("not a date"), None);
    }

    #[test]
    fn normalizes_feed_urls() {
        assert_eq!(normalize_feed_url(" feed://example.com/feed ").as_deref(), Some("https://example.com/feed"));
        assert_eq!(normalize_feed_url("http://example.com/feed").as_deref(), Some("https://example.com/feed"));
        assert_eq!(normalize_feed_url("example.com/feed").as_deref(), Some("https://example.com/feed"));
        assert_eq!(normalize_feed_url("ftp://example.com/feed"), None);
        assert_eq!(normalize_feed_url("ポッドキャスト"), None);
    }

    #[test]
    fn finished_near_the_end() {
        let near = EpisodePosition::of(3700, Some(3723), 0);
        assert!(near.finished);
        assert_eq!(near.resume_seconds(), 0);
        assert_eq!(near.percent(), Some(100));
        assert_eq!(EpisodePosition::of(600, Some(3723), 0).resume_seconds(), 600);
    }

    #[test]
    fn reads_directory_results() {
        let body = r#"{"resultCount":2,"results":[{"collectionName":"番組A","artistName":"A","feedUrl":"https://a.example/feed","artworkUrl600":"https://a.example/a.jpg"},{"collectionName":"フィード無し"}]}"#;
        let results = directory_results(body);
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].browse_id, "rss:https://a.example/feed");
        assert_eq!(results[0].title, "番組A");
    }

    #[test]
    fn tolerates_broken_xml() {
        let show = parse_feed("https://x.example/f", "<rss><channel><title>T</title><item><title>a<enclosure url='https://x.example/a.mp3'/></item>");
        assert_eq!(show.map(|s| s.title).as_deref(), Some("T"));
    }

    #[test]
    fn pages_through_long_feeds_and_reverses_oldest_first() {
        let items: String = (1..=70)
            .map(|n| format!("<item><title>回{n}</title><pubDate>{} Oct 2026 00:00:00 GMT</pubDate><enclosure url=\"https://x.example/{n}.mp3\"/></item>", n.min(28)))
            .collect();
        let show = parse_feed("https://x.example/f", &format!("<rss><channel><title>連続</title>{items}</channel></rss>")).unwrap();
        let first = show.to_page(Some(false), PAGE_SIZE, no_positions);
        assert_eq!(first.episodes.len(), 30);
        // 古い順のフィードは新しい順に並べ直す
        assert_eq!(first.episodes[0].song.title, "回70");
        let second = show.more(first.continuation.as_deref().unwrap(), no_positions);
        assert_eq!(second.items[0].song.title, "回40");
        let third = show.more(second.continuation.as_deref().unwrap(), no_positions);
        assert_eq!(third.items.len(), 10);
        assert_eq!(third.continuation, None);
        assert_eq!(FeedShow::loaded_after("feed:30"), Some(60));
    }

    #[test]
    fn external_ids_need_https_without_spaces() {
        assert!(audio_url_of("ext:https://example.com/ep2.mp3?x=1&y=2").is_some());
        assert!(audio_url_of("ext:http://example.com/ep2.mp3").is_none());
        assert!(audio_url_of("ext:https://example.com/a b.mp3").is_none());
        assert!(audio_url_of("dQw4w9WgXcQ").is_none());
        assert_eq!(feed_url_of("rss:https://example.com/feed"), Some("https://example.com/feed"));
        assert_eq!(audio_mime(None, "https://x.example/a.M4A?x=1"), "audio/mp4");
        assert_eq!(audio_mime(Some("audio/ogg"), "https://x.example/a.mp3"), "audio/ogg");
        assert_eq!(audio_mime(Some("application/octet-stream"), "https://x.example/a"), "audio/mpeg");
    }

    #[test]
    fn decodes_entities_and_numeric_references() {
        assert_eq!(decode_entities("a &amp; b &#x41;&#66; &unknown; & c"), "a & b AB &unknown; & c");
        assert_eq!(parse_duration("1:02:03"), Some(3723));
        assert_eq!(parse_duration("2525.5"), Some(2525));
        assert_eq!(parse_duration("x"), None);
        assert_eq!(date_text(0), "1970/01/01");
    }
}
