//! 曲名・アーティスト名・アルバム名を、YouTube が言語に合わせて書き換える前の表記（曲の言語のまま）にする（設定の「曲名を元の表記で」。Android の OriginalTitles と同じ）。
//! YouTube Music は、日本語で頼むと洋楽をカタカナに（テイラー・スウィフト）、日本語以外で頼むと邦楽の一部をローマ字に（Kenshi Yonezu）して返す。
//! どの言語で頼んでも両方が元の表記になる頼み方は無い（2026-11 に hl・gl を変えて確かめた）ので、日本語と英語の2つの応答を、
//! 行・カード・見出しごとに ID（videoId・browseId）で突き合わせ、元の表記の方を選ぶ。
//!
//! 選び方（手がかりの強い順）:
//! - 英語の応答に日本の文字が残る（「夜の踊り子 - Yoru No Odoriko」）・アーティスト名の漢字・ひらがながローマ字になる → 日本の曲（日本語の方）
//! - カタカナが英字になる → 洋楽（英語の方）。カタカナ名の邦楽（サカナクション）もあるので、日本の曲の手がかりが無いときだけ
//! - 曲名の漢字がローマ字になるだけでは決めない（洋楽にも日本語の副題がある。「シェイク・イット・オフ～気にしてなんかいられないっ！！」）
//!
//! 日本の曲と分かったアーティストは覚え（[Merged::learned]）、手がかりの無い行にも使う。YouTube が作ったプレイリスト・棚の見出し・ジャンル名は画面の言語のまま。

use std::collections::{HashMap, HashSet};

use serde_json::{Map, Value};

/// 画面の言語（hl）に対して、もう1つ頼む言語。
pub fn other_language(hl: &str) -> &'static str {
    if is_japanese(hl) { EN } else { JA }
}

pub fn is_japanese(hl: &str) -> bool {
    hl.split('-').next().unwrap_or("").eq_ignore_ascii_case("ja")
}

pub struct Merged {
    pub json: Value,
    /// 新しく分かったアーティスト（browseId → 日本の曲なら `ja`、洋楽なら `en`）。
    pub learned: HashMap<String, String>,
}

/// primary（画面の言語 primary_language の応答）を、other（もう1つの言語の同じ頼みの応答）と突き合わせて元の表記にする。
/// known は前に覚えたアーティスト（browseId → `ja`・`en`）。
pub fn merge(primary: Value, other: &Value, primary_language: &str, known: &HashMap<String, String>) -> Merged {
    let primary_is_ja = is_japanese(primary_language);
    // もう1つの言語の応答の、行・カードの題と行き先のある文字（ID ごと）。言語で並びや中身が少し変わるので、同じ位置ではなく ID で引く
    let mut other_titles: HashMap<String, Value> = HashMap::new();
    for (name, obj) in groups(other) {
        let Some(key) = group_key(name, obj) else { continue };
        if let Some(runs) = title_runs(name, obj) {
            other_titles.entry(key).or_insert_with(|| runs.clone());
        }
    }
    let other_linked: HashMap<String, String> = {
        let mut out = HashMap::new();
        for (id, text) in linked(other, true) {
            out.entry(id).or_insert(text);
        }
        out
    };

    struct Pairing {
        name: String,
        key: String,
        artists: HashSet<String>,
        hints: HashSet<Hint>,
        artist_hints: HashSet<Hint>,
    }

    let mut page_artists: HashSet<String> = HashSet::new();
    let mut pairings: Vec<Pairing> = Vec::new();
    for (name, obj) in groups(&primary) {
        let Some(key) = group_key(name, obj).filter(|k| is_music_key(k)) else { continue };
        // (画面の言語の文字, もう1つの言語の文字, アーティストか)
        let mut fields: Vec<(String, String, bool)> = Vec::new();
        if let (Some(title), Some(other_title)) = (title_runs(name, obj), other_titles.get(&key)) {
            fields.push((text_of(title), text_of(other_title), is_artist_key(&key)));
        }
        let mut artists = HashSet::new();
        for (id, value) in linked_in(obj, false) {
            if is_artist_key(&id) {
                artists.insert(id.trim_start_matches("b:").to_string());
            }
            if let Some(o) = other_linked.get(&id) {
                fields.push((value, o.clone(), is_artist_key(&id)));
            }
        }
        if is_artist_key(&key) {
            artists.insert(key.trim_start_matches("b:").to_string());
        }
        if HEADERS.contains(&name) {
            page_artists.extend(artists.iter().cloned());
        }
        let hint_of = |f: &(String, String, bool)| if primary_is_ja { hint(&f.0, &f.1, f.2) } else { hint(&f.1, &f.0, f.2) };
        let hints = fields.iter().filter_map(hint_of).collect();
        let artist_hints = fields.iter().filter(|f| f.2).filter_map(hint_of).collect();
        pairings.push(Pairing { name: name.to_string(), key, artists, hints, artist_hints });
    }
    let mut learned = known.clone();
    let mut newly_learned = HashMap::new();
    for p in &mut pairings {
        // アルバムの曲の行はアーティストを書かない（見出しのアーティストの曲）
        if p.artists.is_empty() && p.name == LIST_ITEM {
            p.artists.extend(page_artists.iter().cloned());
        }
        if p.hints.contains(&Hint::StrongJa) {
            for a in &p.artists {
                learned.insert(a.clone(), JA.into());
                newly_learned.insert(a.clone(), JA.to_string());
            }
        }
    }
    // カタカナが英字になったアーティストは洋楽として覚える（キューの動画の行のように、題しか違わない行に使う）。日本の曲と分かっていれば覚えない
    for p in &pairings {
        if p.hints.contains(&Hint::StrongJa) || !p.artist_hints.contains(&Hint::WeakEn) {
            continue;
        }
        for a in &p.artists {
            if learned.get(a).map(String::as_str) != Some(JA) {
                learned.insert(a.clone(), EN.into());
                newly_learned.insert(a.clone(), EN.to_string());
            }
        }
    }
    let mut replace = HashSet::new();
    for p in &pairings {
        let learned_is = |lang: &str| p.artists.iter().any(|a| learned.get(a).map(String::as_str) == Some(lang));
        let origin = if p.hints.contains(&Hint::StrongJa) || learned_is(JA) {
            JA
        } else if p.artist_hints.contains(&Hint::WeakEn) || (p.hints.len() == 1 && p.hints.contains(&Hint::WeakEn)) || learned_is(EN) {
            EN
        } else {
            continue;
        };
        if (origin == JA) != primary_is_ja {
            replace.insert(p.key.clone());
        }
    }
    if replace.is_empty() {
        return Merged { json: primary, learned: newly_learned };
    }
    let replacement = Replacement { keys: replace, titles: other_titles, linked: other_linked };
    Merged { json: rewrite(&primary, None, &replacement), learned: newly_learned }
}

struct Replacement {
    keys: HashSet<String>,
    titles: HashMap<String, Value>,
    linked: HashMap<String, String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum Hint {
    StrongJa,
    WeakJa,
    WeakEn,
}

/// ja・en の2つの表記の違いから、元の言語の手がかり。
fn hint(ja: &str, en: &str, artist: bool) -> Option<Hint> {
    if ja.trim() == en.trim() {
        return None;
    }
    let j = Scripts::of(ja);
    let e = Scripts::of(en);
    if e.japanese || e.katakana {
        Some(Hint::StrongJa)
    } else if j.japanese && e.latin {
        Some(if artist { Hint::StrongJa } else { Hint::WeakJa })
    } else if j.katakana && e.latin {
        Some(Hint::WeakEn)
    } else {
        None
    }
}

struct Scripts {
    /// 漢字・ひらがな。
    japanese: bool,
    katakana: bool,
    latin: bool,
}

impl Scripts {
    fn of(s: &str) -> Self {
        let (mut japanese, mut katakana, mut latin) = (false, false, false);
        for c in s.chars() {
            match c as u32 {
                // 0x20000.. は CJK の拡張（Kotlin では UTF-16 の上位サロゲート 0xD840..0xD87F で見ている範囲）
                0x3041..=0x309F | 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF | 0x20000..=0x2FFFF | 0x3005 | 0x3006 => japanese = true,
                0x30A0..=0x30FF | 0x31F0..=0x31FF | 0xFF66..=0xFF9F => katakana = true,
                code => {
                    if c.is_alphabetic() && (code < 0x2000 || (0xFF21..=0xFF5A).contains(&code)) {
                        latin = true;
                    }
                }
            }
        }
        Self { japanese, katakana, latin }
    }
}

const JA: &str = "ja";
const EN: &str = "en";
const LIST_ITEM: &str = "musicResponsiveListItemRenderer";
const ITEMS: [&str; 5] = [LIST_ITEM, "musicTwoRowItemRenderer", "playlistPanelVideoRenderer", "musicCardShelfRenderer", "musicMultiRowListItemRenderer"];
/// アーティストのページの見出し（アーティスト名）。
const ARTIST_HEADERS: [&str; 2] = ["musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer"];
/// アルバム・プレイリストのページの見出し。アーティストへのつながりがあるもの（アルバム）だけを扱う。
const ALBUM_HEADERS: [&str; 2] = ["musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer"];
const HEADERS: [&str; 4] = [ARTIST_HEADERS[0], ARTIST_HEADERS[1], ALBUM_HEADERS[0], ALBUM_HEADERS[1]];
const ALBUM_PAGE: &str = "h:album";

fn is_group(name: &str) -> bool {
    ITEMS.contains(&name) || HEADERS.contains(&name)
}

fn groups(root: &Value) -> Vec<(&str, &Map<String, Value>)> {
    fn walk<'a>(e: &'a Value, out: &mut Vec<(&'a str, &'a Map<String, Value>)>) {
        match e {
            Value::Object(map) => {
                for (k, v) in map {
                    if let Value::Object(obj) = v
                        && is_group(k)
                    {
                        out.push((k.as_str(), obj));
                    }
                    walk(v, out);
                }
            }
            Value::Array(list) => list.iter().for_each(|v| walk(v, out)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    walk(root, &mut out);
    out
}

/// 文字の続き（run）・行の行き先の ID（`v:videoId`・`b:browseId`）。
fn endpoint_id(o: &Value) -> Option<String> {
    endpoint_id_of(o.get("navigationEndpoint"))
}

fn endpoint_id_of(ne: Option<&Value>) -> Option<String> {
    if let Some(id) = ne.and_then(|n| n.get("watchEndpoint")).and_then(|w| w.get("videoId")).and_then(Value::as_str).filter(|s| !s.is_empty()) {
        return Some(format!("v:{id}"));
    }
    if let Some(id) = ne.and_then(|n| n.get("browseEndpoint")).and_then(|b| b.get("browseId")).and_then(Value::as_str).filter(|s| !s.is_empty()) {
        return Some(format!("b:{id}"));
    }
    None
}

fn is_artist_key(key: &str) -> bool {
    key.starts_with("b:UC")
}

/// 曲・動画・アルバム・アーティスト（YouTube のプレイリスト・気分の棚などは画面の言語のまま）。
fn is_music_key(key: &str) -> bool {
    key.starts_with("v:") || key.starts_with("b:MPRE") || is_artist_key(key) || key == ALBUM_PAGE
}

fn title_runs<'a>(name: &str, g: &'a Map<String, Value>) -> Option<&'a Value> {
    let runs = if name == LIST_ITEM {
        g.get("flexColumns")?.get(0)?.get("musicResponsiveListItemFlexColumnRenderer")?.get("text")?.get("runs")?
    } else {
        g.get("title")?.get("runs")?
    };
    runs.is_array().then_some(runs)
}

fn text_of(runs: &Value) -> String {
    runs.as_array().into_iter().flatten().map(|r| r.get("text").and_then(Value::as_str).unwrap_or("")).collect()
}

fn group_key(name: &str, g: &Map<String, Value>) -> Option<String> {
    if ARTIST_HEADERS.contains(&name) {
        return first_string_in(g, "channelId").map(|id| format!("b:{id}"));
    }
    if ALBUM_HEADERS.contains(&name) {
        let has_artist = runs_in_map(g, false).iter().any(|r| page_type(r) == Some("MUSIC_PAGE_TYPE_ARTIST"));
        return has_artist.then(|| ALBUM_PAGE.to_string());
    }
    let video = g.get("playlistItemData").and_then(|p| p.get("videoId")).and_then(Value::as_str).or_else(|| g.get("videoId").and_then(Value::as_str));
    if let Some(id) = video.filter(|s| !s.is_empty()) {
        return Some(format!("v:{id}"));
    }
    if let Some(id) = endpoint_id_of(g.get("navigationEndpoint")) {
        return Some(id);
    }
    title_runs(name, g).and_then(|runs| runs.get(0)).and_then(endpoint_id)
}

fn page_type(run: &Value) -> Option<&str> {
    run.pointer("/navigationEndpoint/browseEndpoint/browseEndpointContextSupportedConfigs/browseEndpointContextMusicConfig/pageType").and_then(Value::as_str)
}

fn first_string_in<'a>(g: &'a Map<String, Value>, key: &str) -> Option<&'a str> {
    g.get(key).and_then(Value::as_str).or_else(|| g.values().find_map(|v| first_string(v, key)))
}

fn first_string<'a>(e: &'a Value, key: &str) -> Option<&'a str> {
    match e {
        Value::Object(map) => first_string_in(map, key),
        Value::Array(list) => list.iter().find_map(|v| first_string(v, key)),
        _ => None,
    }
}

/// 群の中の run（nested でなければ入れ子の群の中は除く）。
fn runs_in_map(g: &Map<String, Value>, nested: bool) -> Vec<&Value> {
    fn walk_map<'a>(map: &'a Map<String, Value>, nested: bool, out: &mut Vec<&'a Value>) {
        for (k, v) in map {
            if is_group(k) && !nested {
                continue;
            }
            match v {
                Value::Array(runs) if k == "runs" => out.extend(runs.iter()),
                _ => walk(v, nested, out),
            }
        }
    }
    fn walk<'a>(e: &'a Value, nested: bool, out: &mut Vec<&'a Value>) {
        match e {
            Value::Object(map) => walk_map(map, nested, out),
            Value::Array(list) => list.iter().for_each(|v| walk(v, nested, out)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    walk_map(g, nested, &mut out);
    out
}

/// 応答の全体の run（入れ子の群の中も）の ID → 文字。
fn linked(root: &Value, nested: bool) -> Vec<(String, String)> {
    match root {
        Value::Object(map) => linked_in(map, nested),
        _ => Vec::new(),
    }
}

/// 群の中（nested なら入れ子の群の中も）の、行き先のある run の ID → 文字（出てくる順、同じ ID は最初のもの）。
fn linked_in(g: &Map<String, Value>, nested: bool) -> Vec<(String, String)> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for run in runs_in_map(g, nested) {
        let Some(id) = endpoint_id(run) else { continue };
        if seen.insert(id.clone()) {
            out.push((id, run.get("text").and_then(Value::as_str).unwrap_or("").to_string()));
        }
    }
    out
}

/// replace の群を、もう1つの言語の題・行き先のある文字に置き換える。scope は今いる置き換える群の、もう1つの言語の文字。
fn rewrite(e: &Value, scope: Option<&HashMap<String, String>>, replace: &Replacement) -> Value {
    match e {
        Value::Object(map) => {
            let has_text = map.get("text").is_some_and(|t| !t.is_object() && !t.is_array());
            let id = if scope.is_some() && has_text { endpoint_id(e) } else { None };
            let replacement = id.filter(|id| is_music_key(id)).and_then(|id| scope.and_then(|s| s.get(&id)));
            let mut out = Map::new();
            for (k, v) in map {
                let value = match (replacement, v) {
                    (Some(text), _) if k == "text" => Value::String(text.clone()),
                    (_, Value::Object(obj)) if is_group(k) => group(k, obj, replace),
                    _ => rewrite(v, scope, replace),
                };
                out.insert(k.clone(), value);
            }
            Value::Object(out)
        }
        Value::Array(list) => Value::Array(list.iter().map(|v| rewrite(v, scope, replace)).collect()),
        _ => e.clone(),
    }
}

fn group(name: &str, g: &Map<String, Value>, replace: &Replacement) -> Value {
    let as_value = Value::Object(g.clone());
    let Some(key) = group_key(name, g).filter(|k| replace.keys.contains(k)) else { return rewrite(&as_value, None, replace) };
    let titled = match replace.titles.get(&key) {
        Some(runs) if title_runs(name, g).is_some() => with_title(name, as_value, runs),
        _ => as_value,
    };
    rewrite(&titled, Some(&replace.linked), replace)
}

fn with_title(name: &str, mut g: Value, runs: &Value) -> Value {
    let pointer = if name == LIST_ITEM { "/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text/runs" } else { "/title/runs" };
    if let Some(slot) = g.pointer_mut(pointer) {
        *slot = runs.clone();
    }
    g
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::music::models::HomeItem;
    use crate::music::parsers;

    fn fixture(name: &str) -> Value {
        // 手本の応答は Android の core と共通（core/src/desktopTest/resources/fixtures）。2026-11-07 に取って、使わない欄を落としたもの
        let path = format!("{}/../../core/src/desktopTest/resources/fixtures/{name}.json", env!("CARGO_MANIFEST_DIR"));
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    fn merged_with(name: &str, lang: &str, known: &HashMap<String, String>) -> Merged {
        let other = fixture(&format!("bilingual_{name}_{}", other_language(lang)));
        merge(fixture(&format!("bilingual_{name}_{lang}")), &other, lang, known)
    }

    fn merged(name: &str, lang: &str) -> Merged {
        merged_with(name, lang, &HashMap::new())
    }

    const TAYLOR: &str = "UCPC0L1d253x-KuMNwa05TpA";

    #[test]
    fn western_songs_keep_their_english_names_in_japanese() {
        let songs = parsers::search_songs(&merged("search_songs", "ja").json).songs;
        let artists: Vec<&str> = songs.iter().flat_map(|s| s.artists.iter().map(|a| a.name.as_str())).collect();
        assert!(artists.contains(&"Taylor Swift"));
        assert!(!artists.iter().any(|a| a.contains("テイラー")));
        assert!(songs.iter().any(|s| s.title == "All Too Well"));
        // 英語の画面では、もともと英語なので変えない
        assert_eq!(parsers::search_songs(&fixture("bilingual_search_songs_en")), parsers::search_songs(&merged("search_songs", "en").json));
    }

    #[test]
    fn japanese_songs_keep_their_japanese_names_in_english() {
        let album = parsers::album_page(&merged("album_yonezu", "en").json, "MPREb_NnSQrGZDyMk").unwrap();
        assert_eq!(album.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>(), ["米津玄師"]);
        let titles: Vec<&str> = album.songs.iter().map(|s| s.title.as_str()).collect();
        assert!(titles.contains(&"アイネクライネ"));
        assert!(titles.contains(&"花に嵐"));
        assert!(!titles.iter().any(|t| t.contains(" - ")), "{titles:?}");
        // 日本語の画面では、アルバムの曲はそのまま
        assert_eq!(
            parsers::album_page(&fixture("bilingual_album_yonezu_ja"), "MPREb_NnSQrGZDyMk").unwrap().songs,
            parsers::album_page(&merged("album_yonezu", "ja").json, "MPREb_NnSQrGZDyMk").unwrap().songs,
        );
    }

    #[test]
    fn artist_page_uses_the_artists_own_names() {
        let ja = merged("artist_taylor", "ja");
        let page = parsers::artist_page(&ja.json).unwrap();
        let titles: Vec<&str> = page.top_songs.iter().map(|s| s.title.as_str()).collect();
        assert_eq!(page.name, "Taylor Swift");
        assert!(titles.contains(&"Shake It Off"), "{titles:?}");
        assert!(!titles.iter().any(|t| t.chars().any(|c| ('ァ'..='ヶ').contains(&c))), "{titles:?}");
        assert_eq!(ja.learned.get(TAYLOR).map(String::as_str), Some("en"));
        // 英語の画面で、日本語の副題（「～気にしてなんかいられないっ！！」の漢字）を日本の曲と取り違えない
        let en = parsers::artist_page(&merged("artist_taylor", "en").json).unwrap();
        assert_eq!(parsers::artist_page(&fixture("bilingual_artist_taylor_en")).unwrap().top_songs, en.top_songs);
    }

    #[test]
    fn queue_uses_what_was_learned_about_the_artist() {
        // キューの動画の行はアーティスト名（チャンネル名）が同じで題しか違わないので、覚えたことが無ければ変えない
        let plain = parsers::up_next(&merged("next_taylor", "ja").json).songs.remove(0);
        assert!(plain.title.starts_with("シェイク"), "{}", plain.title);
        let known = HashMap::from([(TAYLOR.to_string(), "en".to_string())]);
        let learned = parsers::up_next(&merged_with("next_taylor", "ja", &known).json).songs.remove(0);
        assert_eq!(learned.title, "Shake It Off");
        assert_eq!(parsers::up_next(&merged("next_taylor", "en").json).songs[0].title, "Shake It Off");
    }

    #[test]
    fn katakana_japanese_artists_and_youtube_playlists_stay() {
        // サカナクション（カタカナ名の邦楽）は、曲名に日本の文字が残るので日本の曲と分かる。YouTube のプレイリスト名・棚の見出しは画面の言語のまま
        let en = parsers::home_page(&merged("home", "en").json);
        let plain = parsers::home_page(&fixture("bilingual_home_en"));
        assert_eq!(plain.shelves.iter().map(|s| &s.title).collect::<Vec<_>>(), en.shelves.iter().map(|s| &s.title).collect::<Vec<_>>());
        // YouTube のプレイリストの補足（並ぶアーティストの名前）は画面の言語のまま
        let songs: Vec<_> = en.shelves.iter().flat_map(|s| s.items.iter()).filter(|i| matches!(i, HomeItem::Song(_))).collect();
        let items = format!("{songs:?}");
        assert!(items.contains("サカナクション"));
        assert!(items.contains("夜の踊り子") && !items.contains("Yoru No Odoriko"));
        assert!(items.contains("米津玄師") && !items.contains("Kenshi Yonezu"));
        assert!(items.contains("Official髭男dism"));
        assert_eq!(parsers::home_page(&fixture("bilingual_home_ja")), parsers::home_page(&merged("home", "ja").json));
    }

    #[test]
    fn other_language_is_the_opposite_of_the_screen() {
        assert_eq!(other_language("ja"), "en");
        assert_eq!(other_language("ja-JP"), "en");
        assert_eq!(other_language("en-GB"), "ja");
    }
}
