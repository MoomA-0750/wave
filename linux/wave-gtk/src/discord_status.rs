//! Discord に「wave を聴いています」を出す（設定でオンにし、Discord のアプリの ID を入れたときだけ。Windows の DiscordStatus と同じ）。
//! ほかの端末の再生を映しているとき（スマホで鳴らしているなど）も、その曲を出す。スマホの Discord には出す手段が無いため。

use std::cell::RefCell;
use std::rc::{Rc, Weak};
use std::time::Duration;

use wave_core::discord::{DiscordPresence, PresenceStatus, PresenceTrack};

use crate::ui::shell::App;

thread_local! {
    static PRESENCE: RefCell<Option<(DiscordPresence, Weak<App>)>> = const { RefCell::new(None) };
}

/// 1秒おきに今の曲を渡し続ける（ログインし直したら、新しい App に付け替える）。
pub fn start(app: &Rc<App>) {
    let started = PRESENCE.with(|p| {
        let mut p = p.borrow_mut();
        let started = p.is_some();
        match p.as_mut() {
            Some((_, weak)) => *weak = Rc::downgrade(app),
            None => *p = Some((DiscordPresence::start(), Rc::downgrade(app))),
        }
        started
    });
    if !started {
        gtk::glib::timeout_add_local(Duration::from_secs(1), || {
            push();
            gtk::glib::ControlFlow::Continue
        });
    }
}

/// 設定が変わったときなどに、すぐ反映する。
pub fn push() {
    PRESENCE.with(|p| {
        let p = p.borrow();
        let Some((presence, weak)) = p.as_ref() else { return };
        let Some(app) = weak.upgrade() else {
            presence.update(None, None);
            return;
        };
        let settings = app.downloads.settings.borrow().clone();
        let client_id = settings.discord_client_id.as_deref().filter(|_| settings.discord_presence);
        presence.update(client_id, client_id.and_then(|_| track(&app)));
    });
}

/// 今の状態の文（設定の画面に出す）。
pub fn status_text() -> &'static str {
    let status = PRESENCE.with(|p| p.borrow().as_ref().map(|(presence, _)| presence.status()));
    match status {
        Some(PresenceStatus::Connected) => "Discord につないでいます",
        Some(PresenceStatus::NotFound) => "Discord が見つかりません（Discord のアプリを起動すると、自動でつなぎます）",
        Some(PresenceStatus::Refused) => "Discord につなげませんでした。Application ID を確かめてください",
        _ => "曲を再生すると Discord につなぎます",
    }
}

fn track(app: &App) -> Option<PresenceTrack> {
    if let Some(mirror) = app.connect.mirror() {
        let song = mirror.song;
        let large = [song.album.as_ref().map(|a| a.title.clone()), Some(format!("{}で再生中", mirror.label))].into_iter().flatten().collect::<Vec<_>>().join(" · ");
        return Some(PresenceTrack {
            artists: song.artists_text(),
            large_text: Some(large),
            thumbnail_url: song.thumbnail_url.clone(),
            position_seconds: mirror.position_ms.max(0) as f64 / 1000.0,
            duration_seconds: song.duration_seconds.unwrap_or(0) as f64,
            playing: mirror.playing,
            video_id: song.video_id,
            title: song.title,
        });
    }
    let song = app.playback.current()?;
    let player = app.playback.player();
    let duration = player.duration_ms().map(|d| d as f64 / 1000.0).filter(|d| *d > 0.0).or(song.duration_seconds.map(|d| d as f64)).unwrap_or(0.0);
    Some(PresenceTrack {
        artists: song.artists_text(),
        large_text: song.album.as_ref().map(|a| a.title.clone()),
        thumbnail_url: song.thumbnail_url.clone(),
        position_seconds: player.position_ms().unwrap_or(0) as f64 / 1000.0,
        duration_seconds: duration,
        playing: player.wants_to_play(),
        video_id: song.video_id,
        title: song.title,
    })
}
