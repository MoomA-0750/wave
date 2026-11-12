//! 次の起動に持ち越すキュー（Windows の QueueStore・Android の SavedQueue と同じ考え）。~/.local/share/wave/queue.json。
//! 曲の情報だけで、ログイン情報は入れない。保存したときのログインの取得時刻が今のログインと違えば戻さない（別のアカウントのキューを出さない）。
//! 書き込みは一時ファイルに書いてから置き換える。

use std::cell::{Cell, RefCell};
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

thread_local! {
    /// ログインし直す前のログインの取得時刻とアカウント（DATASYNC_ID）。ログに出さない。
    static BEFORE_RELOGIN: RefCell<Option<(i64, String)>> = const { RefCell::new(None) };
}

/// ログインが切れて入り直す前に呼ぶ: 今のログインの取得時刻とアカウントを覚えておき、
/// 同じアカウントで入り直したら、前のログインで書いたキューも戻す（ログアウトでは呼ばない）。
pub fn remember_before_relogin(obtained_at: Option<i64>, data_sync_id: Option<String>) {
    let value = obtained_at.zip(data_sync_id);
    BEFORE_RELOGIN.with(|b| *b.borrow_mut() = value);
}

/// 保存したキューを、今のログインで戻してよいか: 同じログインか、入り直す前のログインで書いたもので、アカウントが同じとき。
fn same_login(saved_obtained_at: i64, obtained_at: i64, data_sync_id: Option<&str>, before: Option<&(i64, String)>) -> bool {
    saved_obtained_at == obtained_at || before.is_some_and(|(t, id)| *t == saved_obtained_at && Some(id.as_str()) == data_sync_id)
}

/// 前回のキューを戻し、以後は変わるたびに書く。obtained_at は今のログインの取得時刻（ログインしていなければ None で、戻さず書かない）。
/// data_sync_id は今のアカウント（入り直す前と同じアカウントかを見る）。
pub fn attach(playback: &Rc<Playback>, obtained_at: Option<i64>, data_sync_id: Option<String>) {
    let Some(obtained_at) = obtained_at else { return };
    let before = BEFORE_RELOGIN.with(|b| b.borrow_mut().take());
    // 入り直す前のログインで書いたキューを戻したら、すぐ今のログインで書き直す（次の起動でも戻せるように）
    if restore(playback, obtained_at, data_sync_id.as_deref(), before.as_ref()) == Some(false) {
        save(playback, obtained_at);
    }
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

/// 戻したら Some（今のログインで書いたものなら true、入り直す前のログインで書いたものなら false）。
fn restore(playback: &Rc<Playback>, obtained_at: i64, data_sync_id: Option<&str>, before: Option<&(i64, String)>) -> Option<bool> {
    let saved = std::fs::read_to_string(path()).ok().and_then(|t| serde_json::from_str::<SavedQueue>(&t).ok())?;
    if saved.version != VERSION || !same_login(saved.obtained_at_epoch_ms, obtained_at, data_sync_id, before) {
        return None;
    }
    let current_login = saved.obtained_at_epoch_ms == obtained_at;
    let items: Vec<QueueItem> = saved.items.into_iter().map(|i| QueueItem { song: i.song, item_id: i.item_id, origin: origin_of(&i.origin) }).collect();
    let index = items.iter().position(|i| i.item_id == saved.current_item_id)?;
    let source = QueueSource { playlist_id: saved.source_id, radio: saved.radio, radio_playlist_id: saved.radio_playlist_id, continuation: saved.continuation };
    let repeat = match saved.repeat.as_str() {
        "all" => Repeat::All,
        "one" => Repeat::One,
        _ => Repeat::Off,
    };
    playback.restore(items, index, saved.position_ms, source, repeat, saved.unshuffled);
    eprintln!("queue restored");
    Some(current_login)
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

#[cfg(test)]
mod tests {
    use super::same_login;

    #[test]
    fn keeps_the_queue_only_for_the_same_login_or_the_same_account_after_relogin() {
        let before = (100, "acct".to_string());
        assert!(same_login(200, 200, None, None));
        assert!(!same_login(100, 200, Some("acct"), None));
        assert!(same_login(100, 200, Some("acct"), Some(&before)));
        assert!(!same_login(100, 200, Some("other"), Some(&before)));
        assert!(!same_login(100, 200, None, Some(&before)));
        assert!(!same_login(50, 200, Some("acct"), Some(&before)));
    }
}
