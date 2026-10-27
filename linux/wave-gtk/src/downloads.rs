//! ダウンロードの管理（主スレッド）。目録を変えたら保存し、持ち主のいないファイルを消し、取っていない曲を1曲ずつ順に取る。
//! 置き場は ~/.local/share/wave/downloads。取り終えた曲は Player が通信せずにファイルから鳴らす。

use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use gtk::gio;
use gtk::prelude::*;
use wave_core::download::downloader::{self, DownloadOutcome};
use wave_core::download::manifest::{DownloadCollection, DownloadManifest};
use wave_core::download::smart::{self, PlayCounts};
use wave_core::download::store::DownloadStore;
use wave_core::music::models::Song;
use wave_core::music::repository::{LIKED_SONGS, MusicRepository};
use wave_core::stream::resolver::StreamResolver;

use crate::playback::Playback;
use crate::runtime;
use crate::settings::Settings;

/// 曲のダウンロードの状態（行の印・メニューに使う）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SongState {
    None,
    Waiting,
    Downloading,
    Done,
}

pub struct Downloads {
    store: Arc<DownloadStore>,
    resolver: StreamResolver,
    repo: MusicRepository,
    pub settings: RefCell<Settings>,
    /// この端末での再生回数（「いつも聴いている」に使う）。
    counts: RefCell<PlayCounts>,
    refreshing_smart: Cell<bool>,
    /// 選び直しの途中に、もう一度選び直すよう頼まれた（曲数を変えたなど）。
    smart_again: Cell<bool>,
    network: gio::NetworkMonitor,
    manifest: RefCell<DownloadManifest>,
    /// 持ち主のいる曲（裏で取っている途中に、消されたかを見る）。
    wanted: Arc<Mutex<HashSet<String>>>,
    /// 今取っている曲と、取れたバイト数・全体の長さ。
    pub progress: Arc<Mutex<Option<(String, u64, u64)>>>,
    /// この起動の間は取り直さない曲（その曲だけ取れない）。
    unavailable: RefCell<HashSet<String>>,
    working: Cell<bool>,
    /// 変わったときに呼ぶもの。false を返したもの（見張っていた部品が消えた）は外す。
    listeners: RefCell<Vec<Box<dyn Fn() -> bool>>>,
}

fn counts_path() -> std::path::PathBuf {
    gtk::glib::user_data_dir().join("wave").join("play_counts.json")
}

pub fn directory() -> std::path::PathBuf {
    // 確かめ用: WAVE_DOWNLOADS_DIR があればそこに置く（本人のホームに曲を置かない）
    if let Some(dir) = std::env::var_os("WAVE_DOWNLOADS_DIR") {
        return dir.into();
    }
    gtk::glib::user_data_dir().join("wave").join("downloads")
}

impl Downloads {
    pub fn new(resolver: StreamResolver, repo: MusicRepository) -> Rc<Self> {
        let store = Arc::new(DownloadStore::new(directory()));
        let counts = std::fs::read_to_string(counts_path()).map(|t| PlayCounts::decode(&t)).unwrap_or_default();
        let manifest = store.read_manifest();
        let wanted = manifest.wanted_songs().into_iter().map(|s| s.video_id).collect();
        let downloads = Rc::new(Self {
            store,
            resolver,
            repo,
            settings: RefCell::new(Settings::load()),
            counts: RefCell::new(counts),
            refreshing_smart: Cell::new(false),
            smart_again: Cell::new(false),
            network: gio::NetworkMonitor::default(),
            manifest: RefCell::new(manifest),
            wanted: Arc::new(Mutex::new(wanted)),
            progress: Arc::default(),
            unavailable: RefCell::default(),
            working: Cell::new(false),
            listeners: RefCell::default(),
        });
        // 前の起動で取り終えていない曲を続ける。通信が変わったら（従量制でなくなった・つながった）取り直す
        downloads.work();
        let weak = Rc::downgrade(&downloads);
        downloads.network.connect_network_changed(move |_, _| {
            if let Some(d) = weak.upgrade() {
                d.work();
                d.changed();
            }
        });
        // スマートダウンロードは起動のときと、1時間ごとに選び直す頃かを見る（選び直すのは1日1回）
        let weak = Rc::downgrade(&downloads);
        gtk::glib::timeout_add_seconds_local(60 * 60, move || match weak.upgrade() {
            Some(d) => {
                d.refresh_smart(false);
                gtk::glib::ControlFlow::Continue
            }
            None => gtk::glib::ControlFlow::Break,
        });
        downloads.refresh_smart(false);
        downloads
    }

    /// 今の通信で取ってよいか（設定の通信の種類と、つながっているか・従量制か）。
    pub fn network_allows(&self) -> bool {
        smart::allows(self.settings.borrow().download_network, self.network.is_network_available(), self.network.is_network_metered())
    }

    pub fn update_settings(self: &Rc<Self>, change: impl FnOnce(&mut Settings)) {
        let before = self.settings.borrow().clone();
        change(&mut self.settings.borrow_mut());
        let after = self.settings.borrow().clone();
        after.save();
        if !after.smart_downloads && before.smart_downloads {
            // 切ったら、スマートで選んだ曲を外す
            let next = self.manifest.borrow().with_smart(Vec::new(), 0);
            self.apply(next);
        } else if after.smart_downloads && (!before.smart_downloads || after.smart_download_count != before.smart_download_count) {
            self.refresh_smart(true);
        }
        self.work();
        self.changed();
    }

    /// 曲を聴いた（30秒か長さの半分）。再生回数に足す。
    pub fn record_listen(&self, song: &Song) {
        let next = self.counts.borrow().record(song, now_ms());
        if let Err(e) = std::fs::create_dir_all(counts_path().parent().unwrap()).and_then(|_| std::fs::write(counts_path(), next.encode())) {
            eprintln!("play counts save failed: {}", e.kind());
        }
        *self.counts.borrow_mut() = next;
    }

    /// 再生を見張って、聴いた曲を数える（同じ曲は1回の再生で1回まで）。
    pub fn track_listens(self: &Rc<Self>, playback: &Rc<Playback>) {
        let (weak, playback) = (Rc::downgrade(self), playback.clone());
        let counted: RefCell<Option<String>> = RefCell::default();
        let listened = Cell::new(0u64);
        let last: RefCell<Option<String>> = RefCell::default();
        gtk::glib::timeout_add_seconds_local(1, move || {
            let Some(downloads) = weak.upgrade() else { return gtk::glib::ControlFlow::Break };
            let current = playback.current();
            let id = current.as_ref().map(|s| s.video_id.clone());
            if *last.borrow() != id {
                *last.borrow_mut() = id.clone();
                *counted.borrow_mut() = None;
                listened.set(0);
            }
            if let Some(song) = current
                && playback.player().is_playing()
            {
                listened.set(listened.get() + 1000);
                let threshold = smart::listen_threshold_ms(playback.player().duration_ms());
                if listened.get() >= threshold && counted.borrow().is_none() {
                    *counted.borrow_mut() = id;
                    downloads.record_listen(&song);
                }
            }
            gtk::glib::ControlFlow::Continue
        });
    }

    /// スマートダウンロードを選び直す（入っていて、ログインしていて、通信できて、1日たったとき。force なら日を待たない）。
    /// 1曲も選べなければ前の曲を残す。
    pub fn refresh_smart(self: &Rc<Self>, force: bool) {
        if self.refreshing_smart.get() {
            // 途中なら、終わってからもう一度（変えた曲数で選び直す）
            if force {
                self.smart_again.set(true);
            }
            return;
        }
        if !self.settings.borrow().smart_downloads || !self.repo.client().is_logged_in() || !self.network.is_network_available() {
            return;
        }
        if !force && !smart::is_due(self.manifest.borrow().smart_refreshed_at_ms, now_ms()) {
            return;
        }
        self.refreshing_smart.set(true);
        let count = smart::normalize_count(self.settings.borrow().smart_download_count);
        let (repo, counts) = (self.repo.clone(), self.counts.borrow().entries.clone());
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            let picked = runtime::run(async move {
                let history = repo.history().await;
                let history_songs = history.clone().unwrap_or_default();
                let liked = repo.playlist(LIKED_SONGS, None).await.map(|p| p.songs).unwrap_or_default();
                let frequent = smart::frequent(&counts, &history_songs);
                let known: HashSet<String> =
                    counts.iter().map(|c| c.song.video_id.clone()).chain(history_songs.iter().map(|s| s.video_id.clone())).chain(liked.iter().map(|s| s.video_id.clone())).collect();
                let mut radios = Vec::new();
                for seed in frequent.iter().take(smart::MAX_SEEDS) {
                    if let Ok(radio) = repo.up_next(Some(&seed.video_id), Some(&format!("RDAMVM{}", seed.video_id)), None).await {
                        radios.push(radio.songs);
                    }
                }
                let likely = smart::likely(&radios, &known);
                let home = repo.home(None, None).await;
                let mut recommended = home.as_ref().map(|h| smart::recommended(&h.shelves)).unwrap_or_default();
                // 曲の棚で足りなければ、ホームの最初のミックスの曲
                if let Ok(h) = &home
                    && recommended.len() < count
                    && let Some(mix) = smart::first_mix(&h.shelves)
                    && let Ok(page) = repo.playlist(&mix.playlist_id, None).await
                {
                    for song in page.songs {
                        if !recommended.iter().any(|r| r.video_id == song.video_id) {
                            recommended.push(song);
                        }
                    }
                }
                if history.is_err() && home.is_err() {
                    return None;
                }
                Some(smart::select(&frequent, &likely, &recommended, count))
            })
            .await;
            this.refreshing_smart.set(false);
            if this.smart_again.replace(false) {
                this.refresh_smart(true);
                return;
            }
            match picked {
                Some(tracks) if !tracks.is_empty() && this.settings.borrow().smart_downloads => {
                    eprintln!("smart refresh picked={}", tracks.len());
                    // 選び直したら、取れなかった曲も取り直してみる
                    this.unavailable.borrow_mut().clear();
                    let next = this.manifest.borrow().with_smart(tracks, now_ms());
                    this.apply(next);
                }
                _ => eprintln!("smart refresh kept previous"),
            }
        });
    }

    pub fn store(&self) -> Arc<DownloadStore> {
        self.store.clone()
    }

    /// widget がある間だけ、変わったら f を呼ぶ（曲の行の印など、数が多く入れ替わるもの）。
    pub fn watch<W: gtk::prelude::ObjectType>(&self, widget: &W, f: impl Fn(&W) + 'static) {
        let weak = gtk::glib::object::WeakRef::new();
        weak.set(Some(widget));
        f(widget);
        self.listeners.borrow_mut().push(Box::new(move || match weak.upgrade() {
            Some(widget) => {
                f(&widget);
                true
            }
            None => false,
        }));
    }

    fn changed(&self) {
        // 呼んでいる間に見張りが足されてもよいよう、取り出してから呼ぶ
        let listeners = std::mem::take(&mut *self.listeners.borrow_mut());
        let kept: Vec<_> = listeners.into_iter().filter(|l| l()).collect();
        let mut current = self.listeners.borrow_mut();
        let added = std::mem::take(&mut *current);
        *current = kept;
        current.extend(added);
    }

    pub fn manifest(&self) -> DownloadManifest {
        self.manifest.borrow().clone()
    }

    pub fn state(&self, video_id: &str) -> SongState {
        if self.store.completed(video_id).is_some() {
            return SongState::Done;
        }
        if !self.manifest.borrow().is_wanted(video_id) {
            return SongState::None;
        }
        match self.progress.lock().unwrap().as_ref() {
            Some((id, _, _)) if id == video_id => SongState::Downloading,
            _ => SongState::Waiting,
        }
    }

    pub fn total_bytes(&self) -> u64 {
        self.store.total_bytes()
    }

    pub fn download_song(self: &Rc<Self>, song: &Song) {
        let next = self.manifest.borrow().with_single(song.clone(), now_ms());
        self.apply(next);
    }

    /// 1曲ずつ取ったものから外す（丸ごと取っているものに入っていれば残る）。
    pub fn remove_song(self: &Rc<Self>, video_id: &str) {
        let next = self.manifest.borrow().without_single(video_id);
        self.apply(next);
    }

    pub fn download_collection(self: &Rc<Self>, kind: &str, id: &str, title: &str, thumbnail: Option<&str>, songs: Vec<Song>) {
        let collection = DownloadCollection { kind: kind.into(), id: id.into(), title: title.into(), thumbnail_url: thumbnail.map(String::from), added_at_ms: now_ms(), songs };
        let next = self.manifest.borrow().with_collection(collection);
        self.apply(next);
    }

    pub fn remove_collection(self: &Rc<Self>, id: &str) {
        let next = self.manifest.borrow().without_collection(id);
        self.apply(next);
    }

    /// 全部消す。
    pub fn clear(self: &Rc<Self>) {
        // スマートダウンロードの入り切りは残す（選び直しは次の1日1回のとき。ほかの端末と同じ）
        *self.manifest.borrow_mut() = DownloadManifest::default();
        self.wanted.lock().unwrap().clear();
        self.store.clear();
        self.changed();
    }

    /// 目録を替えて保存し、持ち主のいないファイルを消してから、足りない曲を取る。
    fn apply(self: &Rc<Self>, next: DownloadManifest) {
        let wanted: HashSet<String> = next.wanted_songs().into_iter().map(|s| s.video_id).collect();
        *self.wanted.lock().unwrap() = wanted.clone();
        if let Err(e) = self.store.write_manifest(&next) {
            eprintln!("download manifest write failed: {e}");
        }
        *self.manifest.borrow_mut() = next;
        self.store.purge(&wanted);
        self.changed();
        self.work();
    }

    /// 取っていない曲を、1曲ずつ順に取る（もう動いていれば何もしない）。
    fn work(self: &Rc<Self>) {
        if self.working.replace(true) {
            return;
        }
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            loop {
                // 設定の通信で取れないあいだは待つ（通信が変わったら work が呼ばれ直す）
                if !this.network_allows() {
                    break;
                }
                let next = this
                    .manifest
                    .borrow()
                    .wanted_songs()
                    .into_iter()
                    .find(|s| this.store.completed(&s.video_id).is_none() && !this.unavailable.borrow().contains(&s.video_id));
                let Some(song) = next else { break };
                let (resolver, store, wanted, progress) = (this.resolver.clone(), this.store.clone(), this.wanted.clone(), this.progress.clone());
                let id = song.video_id.clone();
                let song_key = id.clone();
                *progress.lock().unwrap() = Some((id.clone(), 0, 0));
                this.changed();
                let outcome = runtime::run(async move {
                    let still = {
                        let (wanted, id) = (wanted.clone(), id.clone());
                        move || wanted.lock().unwrap().contains(&id)
                    };
                    let report = {
                        let (progress, id) = (progress.clone(), id.clone());
                        move |got, total| *progress.lock().unwrap() = Some((id.clone(), got, total))
                    };
                    downloader::download_song(&resolver, &store, &song, still, report).await
                })
                .await;
                *this.progress.lock().unwrap() = None;
                match outcome {
                    DownloadOutcome::Done | DownloadOutcome::Cancelled => {}
                    DownloadOutcome::Unavailable | DownloadOutcome::Live => {
                        this.unavailable.borrow_mut().insert(song_key);
                    }
                    // ログインしていない・通信の失敗は、少し待ってからにする（飛ばし続けない）
                    DownloadOutcome::NotLoggedIn | DownloadOutcome::Failed => {
                        this.changed();
                        gtk::glib::timeout_future_seconds(30).await;
                    }
                }
                this.changed();
            }
            this.working.set(false);
            this.changed();
        });
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// 大きさの表示（MB・GB）。
pub fn format_bytes(bytes: u64) -> String {
    let mb = bytes as f64 / 1_000_000.0;
    if mb >= 1000.0 { format!("{:.1} GB", mb / 1000.0) } else { format!("{mb:.0} MB") }
}
