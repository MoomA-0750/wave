//! ダウンロードの管理（主スレッド）。目録を変えたら保存し、持ち主のいないファイルを消し、取っていない曲を1曲ずつ順に取る。
//! 置き場は ~/.local/share/wave/downloads。取り終えた曲は Player が通信せずにファイルから鳴らす。

use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use wave_core::download::downloader::{self, DownloadOutcome};
use wave_core::download::manifest::{DownloadCollection, DownloadManifest};
use wave_core::download::store::DownloadStore;
use wave_core::music::models::Song;
use wave_core::stream::resolver::StreamResolver;

use crate::runtime;

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

pub fn directory() -> std::path::PathBuf {
    // 確かめ用: WAVE_DOWNLOADS_DIR があればそこに置く（本人のホームに曲を置かない）
    if let Some(dir) = std::env::var_os("WAVE_DOWNLOADS_DIR") {
        return dir.into();
    }
    gtk::glib::user_data_dir().join("wave").join("downloads")
}

impl Downloads {
    pub fn new(resolver: StreamResolver) -> Rc<Self> {
        let store = Arc::new(DownloadStore::new(directory()));
        let manifest = store.read_manifest();
        let wanted = manifest.wanted_songs().into_iter().map(|s| s.video_id).collect();
        let downloads = Rc::new(Self {
            store,
            resolver,
            manifest: RefCell::new(manifest),
            wanted: Arc::new(Mutex::new(wanted)),
            progress: Arc::default(),
            unavailable: RefCell::default(),
            working: Cell::new(false),
            listeners: RefCell::default(),
        });
        // 前の起動で取り終えていない曲を続ける
        downloads.work();
        downloads
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
