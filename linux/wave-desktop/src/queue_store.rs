//! 次の起動に持ち越すキュー（Windows の QueueStore・Android の SavedQueue と同じ考え）。~/.local/share/wave/queue.json。
//! 曲の情報だけで、ログイン情報は入れない。保存したときのログインの取得時刻が今のログインと違えば戻さない（別のアカウントのキューを出さない）。
//! 書き込みは一時ファイルに書いてから置き換える。

use std::cell::Cell;
use std::rc::Rc;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use wave_core::music::models::Song;

use crate::playback::{Origin, Playback, QueueItem, QueueSource, Repeat};

const VERSION: i32 = 1;
/// 変わってから書くまで待つ時間（続けて変わるときにまとめる）。
const SAVE_DELAY: Duration = Duration::from_secs(2);
/// 鳴っている間、位置を書き直す間隔。
const POSITION_EVERY: Duration = Duration::from_secs(5);

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SavedItem {
    song: Song,
    item_id: String,
    origin: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SavedQueue {
    version: i32,
    obtained_at_epoch_ms: i64,
    items: Vec<SavedItem>,
    current_item_id: String,
    position_ms: u64,
    repeat: String,
    source_id: Option<String>,
    radio: bool,
    radio_playlist_id: Option<String>,
    continuation: Option<String>,
    /// シャッフル中なら、切ったときに戻す並び（項目の ID）。
    unshuffled: Option<Vec<String>>,
}

fn path() -> std::path::PathBuf {
    glib::user_data_dir().join("wave").join("queue.json")
}

/// 前回のキューを戻し、以後は変わるたびに書く。obtained_at は今のログインの取得時刻（ログインしていなければ None で、戻さず書かない）。
pub fn attach(playback: &Rc<Playback>, obtained_at: Option<i64>) {
    let Some(obtained_at) = obtained_at else { return };
    restore(playback, obtained_at);
    let pending = Rc::new(Cell::new(false));
    let weak = Rc::downgrade(playback);
    let schedule = {
        let pending = pending.clone();
        move || {
            if pending.replace(true) {
                return;
            }
            let (weak, pending) = (weak.clone(), pending.clone());
            glib::timeout_add_local_once(SAVE_DELAY, move || {
                pending.set(false);
                if let Some(playback) = weak.upgrade() {
                    save(&playback, obtained_at);
                }
            });
        }
    };
    playback.on_change(schedule);
    let weak = Rc::downgrade(playback);
    glib::timeout_add_local(POSITION_EVERY, move || {
        let Some(playback) = weak.upgrade() else { return glib::ControlFlow::Break };
        if playback.player().is_playing() {
            save(&playback, obtained_at);
        }
        glib::ControlFlow::Continue
    });
}

/// ログアウトしたとき: 持ち越すキューを消す。
pub fn clear() {
    let _ = std::fs::remove_file(path());
}

fn restore(playback: &Rc<Playback>, obtained_at: i64) {
    let Some(saved) = std::fs::read_to_string(path()).ok().and_then(|t| serde_json::from_str::<SavedQueue>(&t).ok()) else { return };
    if saved.version != VERSION || saved.obtained_at_epoch_ms != obtained_at {
        return;
    }
    let items: Vec<QueueItem> = saved.items.into_iter().map(|i| QueueItem { song: i.song, item_id: i.item_id, origin: origin_of(&i.origin) }).collect();
    let Some(index) = items.iter().position(|i| i.item_id == saved.current_item_id) else { return };
    let source = QueueSource { playlist_id: saved.source_id, radio: saved.radio, radio_playlist_id: saved.radio_playlist_id, continuation: saved.continuation };
    let repeat = match saved.repeat.as_str() {
        "all" => Repeat::All,
        "one" => Repeat::One,
        _ => Repeat::Off,
    };
    playback.restore(items, index, saved.position_ms, source, repeat, saved.unshuffled);
    eprintln!("queue restored");
}

fn save(playback: &Playback, obtained_at: i64) {
    let (items, index) = playback.queue_items();
    let Some(current) = index.and_then(|i| items.get(i)).map(|i| i.item_id.clone()) else {
        clear();
        return;
    };
    let source = playback.source();
    let saved = SavedQueue {
        version: VERSION,
        obtained_at_epoch_ms: obtained_at,
        items: items.into_iter().map(|i| SavedItem { song: i.song, item_id: i.item_id, origin: origin_name(i.origin).to_string() }).collect(),
        current_item_id: current,
        // ライブ配信には戻る位置が無い
        position_ms: if playback.current().is_some_and(|s| s.is_live) { 0 } else { playback.position_ms() },
        repeat: match playback.repeat() {
            Repeat::Off => "off",
            Repeat::All => "all",
            Repeat::One => "one",
        }
        .to_string(),
        source_id: source.playlist_id,
        radio: source.radio,
        radio_playlist_id: source.radio_playlist_id,
        continuation: source.continuation,
        unshuffled: playback.unshuffled_order(),
    };
    let path = path();
    let temp = path.with_extension("tmp");
    let written = std::fs::create_dir_all(path.parent().expect("data path has a parent"))
        .and_then(|_| std::fs::write(&temp, serde_json::to_string(&saved).expect("plain data")))
        .and_then(|_| std::fs::rename(&temp, &path));
    if let Err(e) = written {
        eprintln!("queue save failed: {}", e.kind());
    }
}

fn origin_name(origin: Origin) -> &'static str {
    match origin {
        Origin::Source => "source",
        Origin::Suggestion => "suggestion",
        Origin::UserAdded => "user",
    }
}

fn origin_of(name: &str) -> Origin {
    match name {
        "suggestion" => Origin::Suggestion,
        "user" => Origin::UserAdded,
        _ => Origin::Source,
    }
}
