//! RSS で登録した番組と、エピソードの聴いたところ（端末ごと、ADR-0012。Windows の FeedLibraryState・FeedLibrary と同じ）。
//! ファイルには Android・Windows と同じ形の JSON で置く（encode・decode）。端末の間は LibraryBody で合わせる（ADR-0013）。
//! 状態の書き換えは新しい値を返す（元の値は変えない）。

use std::sync::Mutex;

use indexmap::IndexMap;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::feed::{EpisodePosition, FeedShow, FeedSubscription, is_allowed_feed_url};
use crate::connect::models::{LibraryBody, LibraryFeed, LibraryPosition};

/// 覚える聴いたところの数（多くなりすぎたら古いものから捨てる）。
pub const MAX_POSITIONS: usize = 1000;

/// 登録と聴いたところ。== は Windows の SameAs と同じ（登録は並びも、辞書は中身だけを比べる）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FeedLibraryState {
    /// 新しく登録したものが先。
    pub subscriptions: Vec<FeedSubscription>,
    /// 音声の URL ごと。
    pub positions: IndexMap<String, EpisodePosition>,
    /// 外した番組と、外した時刻（ほかの端末へ「外した」を伝える印、ADR-0013）。
    pub removed: IndexMap<String, i64>,
}

impl FeedLibraryState {
    pub fn is_subscribed(&self, feed_url: &str) -> bool {
        self.subscriptions.iter().any(|s| s.feed_url == feed_url)
    }

    pub fn position(&self, audio_url: &str) -> Option<EpisodePosition> {
        self.positions.get(audio_url).copied()
    }

    /// 登録する（新しいものが先。同じフィードは先頭へ動かす。added_at_ms が登録した時刻）。外した印は消す。
    pub fn subscribe(&self, subscription: FeedSubscription) -> Self {
        let mut next = self.clone();
        next.subscriptions.retain(|s| s.feed_url != subscription.feed_url);
        next.removed.shift_remove(&subscription.feed_url);
        next.subscriptions.insert(0, subscription);
        next
    }

    /// 外す（at_ms は外した時刻。印として残し、ほかの端末に伝える）。
    pub fn unsubscribe(&self, feed_url: &str, at_ms: i64) -> Self {
        let mut next = self.clone();
        next.subscriptions.retain(|s| s.feed_url != feed_url);
        next.removed.insert(feed_url.to_string(), at_ms);
        next
    }

    /// 番組の名前・画像が変わっていたら、登録の表示を合わせる（登録の順と時刻は変えない）。
    pub fn refresh(&self, subscription: &FeedSubscription) -> Self {
        let mut next = self.clone();
        for s in next.subscriptions.iter_mut().filter(|s| s.feed_url == subscription.feed_url) {
            *s = FeedSubscription { added_at_ms: s.added_at_ms, ..subscription.clone() };
        }
        next
    }

    /// 位置を覚える。多くなりすぎたら古いものから捨てる。
    pub fn remember(&self, audio_url: &str, position: EpisodePosition) -> Self {
        let mut next = self.clone();
        next.positions.insert(audio_url.to_string(), position);
        next.positions = trimmed(next.positions);
        next
    }

    /// 端末の間で合わせる形（ADR-0013。Kotlin の toBody と同じ）。
    pub fn to_body(&self) -> LibraryBody {
        let mut body = LibraryBody::default();
        for (url, at) in &self.removed {
            body.feeds.insert(url.clone(), LibraryFeed { title: String::new(), author: None, thumbnail_url: None, added_at_ms: 0, removed: true, updated_at_ms: *at });
        }
        for s in &self.subscriptions {
            let feed = LibraryFeed { title: s.title.clone(), author: s.author.clone(), thumbnail_url: s.thumbnail_url.clone(), added_at_ms: s.added_at_ms, removed: false, updated_at_ms: s.added_at_ms };
            body.feeds.insert(s.feed_url.clone(), feed);
        }
        for (url, p) in &self.positions {
            let position = LibraryPosition { position_seconds: p.position_seconds, duration_seconds: p.duration_seconds, finished: p.finished, updated_at_ms: p.updated_at_ms };
            body.positions.insert(url.clone(), position);
        }
        body
    }

    /// ほかの端末と合わせた結果を取り込む。番組・エピソードごとに、手元より新しいものだけを当てる。
    pub fn apply(&self, body: &LibraryBody) -> Self {
        let mut next = self.clone();
        for (url, feed) in &body.feeds {
            if !is_allowed_feed_url(url) {
                continue;
            }
            let local_at = next.subscriptions.iter().find(|s| &s.feed_url == url).map(|s| s.added_at_ms).or_else(|| next.removed.get(url).copied());
            if local_at.is_some_and(|at| at >= feed.updated_at_ms) {
                continue;
            }
            next = if feed.removed {
                next.unsubscribe(url, feed.updated_at_ms)
            } else {
                next.subscribe(FeedSubscription {
                    feed_url: url.clone(),
                    title: feed.title.clone(),
                    author: feed.author.clone(),
                    thumbnail_url: feed.thumbnail_url.clone().filter(|t| is_allowed_feed_url(t)),
                    added_at_ms: feed.updated_at_ms,
                })
            };
        }
        // 登録の並びは登録した時刻の新しい順
        next.subscriptions.sort_by_key(|s| std::cmp::Reverse(s.added_at_ms));
        for (url, p) in &body.positions {
            if next.positions.get(url).is_some_and(|local| local.updated_at_ms >= p.updated_at_ms) {
                continue;
            }
            next.positions.insert(url.clone(), EpisodePosition { position_seconds: p.position_seconds, duration_seconds: p.duration_seconds, finished: p.finished, updated_at_ms: p.updated_at_ms });
        }
        next.positions = trimmed(next.positions);
        next
    }

    /// Android と同じ形: {"subscriptions":[{feedUrl,title,author,thumbnailUrl,addedAtMs}],"positions":{"URL":{positionSeconds,durationSeconds,finished,updatedAtMs}},"removed":{"URL":外した時刻}}
    /// （既定の値は書かない）。
    pub fn encode(&self) -> String {
        let stored = Stored {
            subscriptions: self
                .subscriptions
                .iter()
                .map(|s| StoredSubscription { feed_url: &s.feed_url, title: &s.title, author: s.author.as_deref(), thumbnail_url: s.thumbnail_url.as_deref(), added_at_ms: s.added_at_ms })
                .collect(),
            positions: self
                .positions
                .iter()
                .map(|(url, p)| (url.as_str(), StoredPosition { position_seconds: p.position_seconds, duration_seconds: p.duration_seconds, finished: p.finished, updated_at_ms: p.updated_at_ms }))
                .collect(),
            removed: self.removed.iter().map(|(url, at)| (url.as_str(), *at)).collect(),
        };
        serde_json::to_string(&stored).expect("plain data serializes")
    }

    /// 読めなければ None（知らない項目は無視する）。
    pub fn decode(text: &str) -> Option<Self> {
        let raw: RawState = serde_json::from_str(text).ok()?;
        let mut state = FeedLibraryState::default();
        if let Value::Array(list) = raw.subscriptions {
            for node in &list {
                let (feed_url, title) = (string(node, "feedUrl")?, string(node, "title")?);
                let added_at_ms = long(node, "addedAtMs").unwrap_or(0);
                state.subscriptions.push(FeedSubscription { feed_url, title, author: string(node, "author"), thumbnail_url: string(node, "thumbnailUrl"), added_at_ms });
            }
        }
        if let Some(ObjectOr::Object(map)) = raw.positions {
            for (url, node) in map {
                let position = long(&node, "positionSeconds")?;
                let finished = node.get("finished") == Some(&Value::Bool(true));
                let duration_seconds = long(&node, "durationSeconds").map(|d| d as i32);
                state.positions.insert(url, EpisodePosition { position_seconds: position as i32, duration_seconds, finished, updated_at_ms: long(&node, "updatedAtMs").unwrap_or(0) });
            }
        }
        if let Some(ObjectOr::Object(map)) = raw.removed {
            for (url, node) in map {
                state.removed.insert(url, node.as_i64()?);
            }
        }
        Some(state)
    }
}

fn trimmed(positions: IndexMap<String, EpisodePosition>) -> IndexMap<String, EpisodePosition> {
    if positions.len() <= MAX_POSITIONS {
        return positions;
    }
    // 新しい順（同じ時刻は元の並び）
    let mut entries: Vec<_> = positions.into_iter().collect();
    entries.sort_by_key(|(_, p)| std::cmp::Reverse(p.updated_at_ms));
    entries.into_iter().take(MAX_POSITIONS).collect()
}

fn string(node: &Value, key: &str) -> Option<String> {
    node.get(key).and_then(Value::as_str).map(String::from)
}

fn long(node: &Value, key: &str) -> Option<i64> {
    node.get(key).and_then(Value::as_i64)
}

#[derive(Serialize)]
struct Stored<'a> {
    #[serde(skip_serializing_if = "Vec::is_empty")]
    subscriptions: Vec<StoredSubscription<'a>>,
    #[serde(skip_serializing_if = "IndexMap::is_empty")]
    positions: IndexMap<&'a str, StoredPosition>,
    #[serde(skip_serializing_if = "IndexMap::is_empty")]
    removed: IndexMap<&'a str, i64>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StoredSubscription<'a> {
    feed_url: &'a str,
    title: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    author: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    thumbnail_url: Option<&'a str>,
    #[serde(skip_serializing_if = "is_zero")]
    added_at_ms: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StoredPosition {
    position_seconds: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    duration_seconds: Option<i32>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    finished: bool,
    #[serde(skip_serializing_if = "is_zero")]
    updated_at_ms: i64,
}

fn is_zero(n: &i64) -> bool {
    *n == 0
}

/// 読むときの形。辞書は文書の並びのまま読み、オブジェクトでなければ無いものとする。
#[derive(Deserialize)]
struct RawState {
    #[serde(default)]
    subscriptions: Value,
    #[serde(default)]
    positions: Option<ObjectOr>,
    #[serde(default)]
    removed: Option<ObjectOr>,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum ObjectOr {
    Object(IndexMap<String, Value>),
    #[allow(dead_code)]
    Other(Value),
}

type Save = Box<dyn Fn(&FeedLibraryState) + Send + Sync>;
type Clock = Box<dyn Fn() -> i64 + Send + Sync>;

/// FeedLibraryState を持ち、変わるたびに save で書く（Windows・Android の FeedLibrary と同じ）。どのスレッドから呼んでもよい。
/// 書き換えの関数は、変わったら true を返す（画面の作り直しの合図に使う）。
/// 置き場の例: ~/.local/share/wave/podcast-feeds.json（Windows は %LOCALAPPDATA%\wave\podcast-feeds.json）。
pub struct FeedLibrary {
    state: Mutex<FeedLibraryState>,
    now_ms: Clock,
    save: Save,
}

impl FeedLibrary {
    /// now_ms はエポックのミリ秒、save は変わった状態を書く（中身は encode で文字にする）。
    pub fn new(initial: FeedLibraryState, now_ms: impl Fn() -> i64 + Send + Sync + 'static, save: impl Fn(&FeedLibraryState) + Send + Sync + 'static) -> Self {
        Self { state: Mutex::new(initial), now_ms: Box::new(now_ms), save: Box::new(save) }
    }

    pub fn state(&self) -> FeedLibraryState {
        self.lock().clone()
    }

    pub fn is_subscribed(&self, feed_url: &str) -> bool {
        self.lock().is_subscribed(feed_url)
    }

    /// 登録する（登録した時刻は今）。
    pub fn subscribe(&self, show: FeedSubscription) -> bool {
        let now = (self.now_ms)();
        self.change(|s| s.subscribe(FeedSubscription { added_at_ms: now, ..show }))
    }

    pub fn unsubscribe(&self, feed_url: &str) -> bool {
        let now = (self.now_ms)();
        self.change(|s| s.unsubscribe(feed_url, now))
    }

    /// ほかの端末と合わせた結果を取り込む（ADR-0013）。
    pub fn apply(&self, body: &LibraryBody) -> bool {
        self.change(|s| s.apply(body))
    }

    /// 読み直した番組の名前・画像を、登録の表示に当てる（登録していなければ何もしない）。
    pub fn refresh(&self, show: &FeedShow) -> bool {
        self.is_subscribed(&show.feed_url) && self.change(|s| s.refresh(&show.subscription()))
    }

    pub fn position(&self, audio_url: &str) -> Option<EpisodePosition> {
        self.lock().position(audio_url)
    }

    /// 聴いたところを覚える（duration_seconds が分からなければ None）。
    pub fn remember(&self, audio_url: &str, position_seconds: i32, duration_seconds: Option<i32>) -> bool {
        let position = EpisodePosition::of(position_seconds, duration_seconds, (self.now_ms)());
        self.change(|s| s.remember(audio_url, position))
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, FeedLibraryState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn change(&self, block: impl FnOnce(&FeedLibraryState) -> FeedLibraryState) -> bool {
        let mut state = self.lock();
        let after = block(&state);
        if after == *state {
            return false;
        }
        (self.save)(&after);
        *state = after;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn subscription(url: &str, title: &str, added_at_ms: i64) -> FeedSubscription {
        FeedSubscription { added_at_ms, ..FeedSubscription::new(url, title) }
    }

    fn titles(state: &FeedLibraryState) -> Vec<&str> {
        state.subscriptions.iter().map(|s| s.title.as_str()).collect()
    }

    #[test]
    fn library_keeps_newest_first_and_round_trips() {
        let a = FeedSubscription::new("https://a.example/f", "A");
        let b = FeedSubscription::new("https://b.example/f", "B");
        let state = FeedLibraryState::default().subscribe(a.clone()).subscribe(b).subscribe(a.clone()).remember("https://a.example/1.mp3", EpisodePosition::of(10, Some(100), 5));
        assert_eq!(titles(&state), ["A", "B"]);
        assert_eq!(FeedLibraryState::decode(&state.encode()), Some(state.clone()));
        assert_eq!(titles(&state.unsubscribe(&a.feed_url, 0)), ["B"]);
        assert_eq!(state.refresh(&FeedSubscription { title: "A2".into(), ..a }).subscriptions[0].title, "A2");
    }

    #[test]
    fn library_json_has_android_shape() {
        let state = FeedLibraryState::default().subscribe(subscription("https://a.example/f", "A", 7)).remember("https://a.example/1.mp3", EpisodePosition::of(95, Some(100), 9));
        assert_eq!(
            state.encode(),
            r#"{"subscriptions":[{"feedUrl":"https://a.example/f","title":"A","addedAtMs":7}],"positions":{"https://a.example/1.mp3":{"positionSeconds":95,"durationSeconds":100,"finished":true,"updatedAtMs":9}}}"#
        );
        // Android が書いたもの（既定の値を省いた形）も読める
        let android = FeedLibraryState::decode(r#"{"subscriptions":[{"feedUrl":"https://b.example/f","title":"B"}],"positions":{"https://b.example/2.mp3":{"positionSeconds":12}},"unknown":1}"#).unwrap();
        assert_eq!(android.subscriptions[0].title, "B");
        assert_eq!(android.positions["https://b.example/2.mp3"].resume_seconds(), 12);
        assert_eq!(FeedLibraryState::decode("not json"), None);
        assert_eq!(FeedLibraryState::decode("{}"), Some(FeedLibraryState::default()));
    }

    #[test]
    fn forgets_oldest_positions_beyond_the_limit() {
        let mut state = FeedLibraryState::default();
        for i in 0..=MAX_POSITIONS {
            state = state.remember(&format!("https://x.example/{i}.mp3"), EpisodePosition::of(10, Some(100), i as i64 + 1));
        }
        assert_eq!(state.positions.len(), MAX_POSITIONS);
        assert!(!state.positions.contains_key("https://x.example/0.mp3"));
    }

    #[test]
    fn round_trips_through_the_sync_body_and_removal_wins() {
        let a = FeedLibraryState::default().subscribe(subscription("https://a.example/f", "A", 10)).remember("https://a.example/1.mp3", EpisodePosition::of(60, Some(600), 11));
        // もう1台: 何も持っていない → 受け取る
        let b = FeedLibraryState::default().apply(&a.to_body());
        assert_eq!(titles(&b), ["A"]);
        assert_eq!(b.positions["https://a.example/1.mp3"].position_seconds, 60);
        // b で外す（後の時刻）→ a に当てると外れる
        let removed = b.unsubscribe("https://a.example/f", 20);
        let a2 = a.apply(&a.to_body().merge(&removed.to_body()));
        assert!(a2.subscriptions.is_empty());
        assert_eq!(a2.removed["https://a.example/f"], 20);
        // 古い登録の知らせは、新しい「外した」を覆さない
        assert!(a2.apply(&a.to_body()).subscriptions.is_empty());
        // 外した後にもう一度登録すると、印は消える
        let again = a2.subscribe(subscription("https://a.example/f", "A", 30));
        assert!(!again.removed.contains_key("https://a.example/f"));
        assert_eq!(titles(&a2.apply(&again.to_body())), ["A"]);
    }

    #[test]
    fn removed_marks_are_saved_in_the_android_shape() {
        let state = FeedLibraryState::default().subscribe(subscription("https://a.example/f", "A", 7)).unsubscribe("https://a.example/f", 9);
        let json = state.encode();
        assert_eq!(json, r#"{"removed":{"https://a.example/f":9}}"#);
        assert_eq!(FeedLibraryState::decode(&json), Some(state.clone()));
        assert_ne!(state, FeedLibraryState::default());
        // Android の FeedLibraryState.encode が書いた形（removed つき）を読める
        let android = FeedLibraryState::decode(r#"{"subscriptions":[{"feedUrl":"https://b.example/f","title":"B","addedAtMs":3}],"removed":{"https://a.example/f":20}}"#).unwrap();
        assert_eq!(android.removed["https://a.example/f"], 20);
    }

    #[test]
    fn sync_body_has_the_kotlin_json_shape() {
        let state = FeedLibraryState::default()
            .subscribe(subscription("https://a.example/f", "A", 10))
            .unsubscribe("https://b.example/f", 5)
            .remember("https://a.example/1.mp3", EpisodePosition::of(60, None, 11));
        let json = serde_json::to_string(&state.to_body()).unwrap();
        // kotlinx.serialization（encodeDefaults = true）と同じ並び・同じ null の出し方
        assert_eq!(
            json,
            r#"{"v":1,"feeds":{"https://b.example/f":{"title":"","author":null,"thumbnailUrl":null,"addedAtMs":0,"removed":true,"updatedAtMs":5},"https://a.example/f":{"title":"A","author":null,"thumbnailUrl":null,"addedAtMs":10,"removed":false,"updatedAtMs":10}},"positions":{"https://a.example/1.mp3":{"positionSeconds":60,"durationSeconds":null,"finished":false,"updatedAtMs":11}}}"#
        );
    }

    #[test]
    fn library_saves_only_when_something_changes() {
        let saved = std::sync::Arc::new(Mutex::new(Vec::new()));
        let sink = saved.clone();
        let library = FeedLibrary::new(FeedLibraryState::default(), || 42, move |s| sink.lock().unwrap().push(s.encode()));
        assert!(library.subscribe(FeedSubscription::new("https://a.example/f", "A")));
        assert!(!library.subscribe(FeedSubscription::new("https://a.example/f", "A")));
        assert!(library.remember("https://a.example/1.mp3", 30, Some(600)));
        assert_eq!(library.position("https://a.example/1.mp3").map(|p| p.resume_seconds()), Some(30));
        assert_eq!(library.state().subscriptions[0].added_at_ms, 42);
        assert_eq!(saved.lock().unwrap().len(), 2);
    }
}
