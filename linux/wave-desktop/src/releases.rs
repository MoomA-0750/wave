//! 登録したアーティストの新作（Windows の Releases・Android と同じ）。動いている間、起動の1分後と6時間おきに確かめ（設定の「新作の通知」が切なら、その回は飛ばす）、
//! よく聴くアーティスト（高く評価した曲が3曲以上）の新作を GNOME の通知で知らせる。最近の新作はホームに出す。
//! 覚えたものは ~/.local/share/wave/release-watch.json。

use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;
use std::time::Duration;

use gio::prelude::*;
use wave_core::music::releases::{NewRelease, ReleaseWatchState};
use wave_core::music::repository::{LIKED_SONGS, MusicRepository};

use crate::runtime;

const FIRST_DELAY: Duration = Duration::from_secs(60);
const INTERVAL: Duration = Duration::from_secs(6 * 60 * 60);
const MAX_ARTISTS: usize = 300;
const MAX_NOTIFICATIONS: usize = 10;
/// 新作を通知するのに要る、そのアーティストの高く評価した曲の数。
const MIN_LIKED_TO_NOTIFY: usize = 3;
const MAX_LIKED_PAGES: usize = 50;
/// 1回の確かめで出元を確かめる新作の数（前に覚えた分も少しずつ確かめる）。
const MAX_SUSPICION_CHECKS: usize = 20;

pub struct Releases {
    repo: MusicRepository,
    recent: RefCell<Vec<NewRelease>>,
    checking: Cell<bool>,
    /// 設定の「新作の通知」が入か（確かめるたびに見る。切り替えがすぐ効くように）。
    enabled: Box<dyn Fn() -> bool>,
}

fn path() -> std::path::PathBuf {
    glib::user_data_dir().join("wave").join("release-watch.json")
}

fn read() -> ReleaseWatchState {
    std::fs::read_to_string(path()).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

impl Releases {
    /// 覚えている最近の新作を読み、定期の確かめを始める（Mac と同じく、確かめは常に回し、毎回 enabled を見る）。
    pub fn start(repo: MusicRepository, enabled: impl Fn() -> bool + 'static) -> Rc<Self> {
        let releases = Rc::new(Self { repo, recent: RefCell::new(read().expire(now_ms()).recent), checking: Cell::new(false), enabled: Box::new(enabled) });
        let weak = Rc::downgrade(&releases);
        // 確かめ用: WAVE_DEMO_RELEASES があれば起動してすぐ確かめる
        let first = if std::env::var_os("WAVE_DEMO_RELEASES").is_some() { Duration::from_secs(1) } else { FIRST_DELAY };
        glib::timeout_add_local_once(first, move || {
            let Some(this) = weak.upgrade() else { return };
            this.check();
            let weak = Rc::downgrade(&this);
            glib::timeout_add_local(INTERVAL, move || match weak.upgrade() {
                Some(this) => {
                    this.check();
                    glib::ControlFlow::Continue
                }
                None => glib::ControlFlow::Break,
            });
        });
        releases
    }

    /// 最近の新作（新しい順）。
    pub fn recent(&self) -> Vec<NewRelease> {
        self.recent.borrow().clone()
    }

    /// ログアウトしたとき: 覚えたものを消す。
    pub fn clear(&self) {
        let _ = std::fs::remove_file(path());
        self.recent.borrow_mut().clear();
    }

    /// 登録したアーティストのページを順に見て、新作を探す（同時に1本だけ）。
    pub fn check(self: &Rc<Self>) {
        if self.checking.get() || !self.repo.client().is_logged_in() || !(self.enabled)() {
            return;
        }
        self.checking.set(true);
        let this = self.clone();
        glib::spawn_future_local(async move {
            let repo = this.repo.clone();
            let outcome = runtime::run(async move {
                let (artists, complete) = repo.subscribed_artists().await.ok()?;
                let now = now_ms();
                let year = glib::DateTime::now_local().ok().map(|d| d.year());
                let mut state = read().expire(now);
                // 登録をやめたアーティストの記録は、一覧を最後まで読めたときだけ捨てる（途中で失敗すると欠けて返るため）
                if complete && !artists.is_empty() {
                    state = state.retain(&artists.iter().map(|a| a.browse_id.clone()).collect::<HashSet<_>>());
                }
                let mut found = Vec::new();
                for artist in artists.iter().take(MAX_ARTISTS) {
                    if let Ok(page) = repo.artist(&artist.browse_id).await {
                        let (fresh, next) = state.observe(&artist.browse_id, &page, now, year);
                        state = next;
                        found.extend(fresh);
                    }
                    // まとめて叩かない
                    tokio::time::sleep(Duration::from_millis(300)).await;
                }
                // まだ確かめていない新作の出元を確かめる（1曲目の YouTube の説明文と再生回数。読めなかったものは次の回に）
                for release in state.unchecked().into_iter().take(MAX_SUSPICION_CHECKS) {
                    let Some(album_id) = release.album_browse_id.clone() else { continue };
                    let Some(video_id) = repo.album(&album_id).await.ok().and_then(|a| a.songs.first().map(|s| s.video_id.clone())) else { continue };
                    let Ok(facts) = repo.video_facts(&video_id).await else { continue };
                    let reasons = wave_core::music::suspicion::reasons(&release.artist_name, &facts, now);
                    if !reasons.is_empty() {
                        eprintln!("release suspect album={album_id} reasons={reasons:?}");
                    }
                    state = state.with_suspicion(&album_id, reasons);
                    tokio::time::sleep(Duration::from_millis(300)).await;
                }
                // 出元が怪しい新作は棚に印を付けて残すが、通知は鳴らさない
                let suspect = |r: &NewRelease| state.recent.iter().any(|s| s.album_browse_id == r.album_browse_id && s.suspect.as_ref().is_some_and(|x| !x.is_empty()));
                let found: Vec<NewRelease> = found.into_iter().filter(|r| !suspect(r)).collect();
                let liked = if found.is_empty() { Vec::new() } else { liked_songs(&repo).await };
                Some((state, found, liked, artists.len()))
            })
            .await;
            this.checking.set(false);
            let Some((state, found, liked, artists)) = outcome else { return };
            let written = std::fs::create_dir_all(path().parent().expect("data path has a parent"))
                .and_then(|_| std::fs::write(path(), serde_json::to_string(&state).expect("plain data")));
            if let Err(e) = written {
                eprintln!("release watch save failed: {}", e.kind());
            }
            eprintln!("release watch artists={artists} new={} recent={}", found.len(), state.recent.len());
            // ホームの棚は次にホームを読んだときから
            *this.recent.borrow_mut() = state.recent;
            // 鳴らす通知は、よく聴くアーティスト（高く評価した曲が3曲以上）の新作だけ。ほかはホームの棚に並べるだけ
            let favorites: Vec<&NewRelease> = found
                .iter()
                .filter(|r| liked.iter().filter(|s| s.artists.iter().any(|a| a.browse_id.as_deref() == Some(r.artist_id.as_str()) || a.name == r.artist_name)).count() >= MIN_LIKED_TO_NOTIFY)
                .collect();
            eprintln!("release notify found={} favorites={}", found.len(), favorites.len());
            for release in favorites.into_iter().take(MAX_NOTIFICATIONS) {
                notify(release);
            }
        });
    }
}

/// 高く評価した曲（続きのページまで）。
async fn liked_songs(repo: &MusicRepository) -> Vec<wave_core::music::models::Song> {
    let Ok(first) = repo.playlist(LIKED_SONGS, None).await else { return Vec::new() };
    let (mut songs, mut token) = (first.songs, first.continuation);
    for _ in 0..MAX_LIKED_PAGES {
        let Some(t) = token.clone() else { break };
        let Ok(next) = repo.playlist(LIKED_SONGS, Some(&t)).await else { break };
        songs.extend(next.songs);
        token = next.continuation.filter(|n| *n != t);
    }
    songs
}

/// GNOME の通知。押すとそのアルバム（無ければアーティスト）を開く（app.open-album・app.open-artist）。
fn notify(release: &NewRelease) {
    let Some(app) = gio::Application::default() else { return };
    let notification = gio::Notification::new(&format!("{} の新作", release.artist_name));
    notification.set_body(Some(&release.title));
    match &release.album_browse_id {
        Some(album) => notification.set_default_action_and_target_value("app.open-album", Some(&album.to_variant())),
        None => notification.set_default_action_and_target_value("app.open-artist", Some(&release.artist_id.to_variant())),
    }
    app.send_notification(Some(&format!("release-{}", release.key())), &notification);
}
