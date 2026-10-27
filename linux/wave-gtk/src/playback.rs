//! 再生の流れ（主スレッド）: キュー・今の曲・次と前・曲の終わりで次へ・キューの終わりでラジオを継ぎ足す。
//! 画面は on_change に登録して、変わるたびに描き直す。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use wave_core::music::models::Song;
use wave_core::music::repository::MusicRepository;

use crate::player::Player;
use crate::runtime;

/// 前の曲のボタンで、曲の頭へ戻すか前の曲へ行くかの境（これより進んでいれば頭へ戻す）。
const RESTART_THRESHOLD_MS: u64 = 3000;
/// 残りがこれ以下になったらラジオを継ぎ足す。
const RADIO_REFILL_LEFT: usize = 2;

pub struct Playback {
    player: Player,
    repo: MusicRepository,
    queue: RefCell<Vec<Song>>,
    index: Cell<Option<usize>>,
    /// ラジオの続き（RDAMVM の流れ）。無ければ最初の曲から始める。
    radio: RefCell<Option<(Option<String>, Option<String>)>>,
    refilling: Cell<bool>,
    /// 曲を頼んだ回数。読み込みの途中で別の曲が頼まれたら、古い方の結果は捨てる。
    request: Cell<u64>,
    listeners: RefCell<Vec<Box<dyn Fn()>>>,
    _watch: RefCell<Option<gst::bus::BusWatchGuard>>,
}

impl Playback {
    pub fn new(player: Player, repo: MusicRepository) -> Rc<Self> {
        let playback = Rc::new(Self {
            player,
            repo,
            queue: RefCell::default(),
            index: Cell::default(),
            radio: RefCell::default(),
            refilling: Cell::default(),
            request: Cell::default(),
            listeners: RefCell::default(),
            _watch: RefCell::default(),
        });
        let weak = Rc::downgrade(&playback);
        let watch = playback.player.watch(move |failed| {
            if let Some(playback) = weak.upgrade() {
                if failed {
                    eprintln!("song failed, skipping");
                }
                playback.next();
            }
        });
        *playback._watch.borrow_mut() = Some(watch);
        playback
    }

    pub fn player(&self) -> &Player {
        &self.player
    }

    pub fn on_change(&self, listener: impl Fn() + 'static) {
        self.listeners.borrow_mut().push(Box::new(listener));
    }

    fn changed(&self) {
        for listener in self.listeners.borrow().iter() {
            listener();
        }
    }

    pub fn current(&self) -> Option<Song> {
        self.index.get().and_then(|i| self.queue.borrow().get(i).cloned())
    }

    pub fn upcoming(&self) -> Vec<Song> {
        let start = self.index.get().map_or(0, |i| i + 1);
        self.queue.borrow().iter().skip(start).cloned().collect()
    }

    /// キューの全部と、今の曲の位置。
    pub fn queue(&self) -> (Vec<Song>, Option<usize>) {
        (self.queue.borrow().clone(), self.index.get())
    }

    /// ログアウトしたときなど: 止めてキューを空にする。
    pub fn clear(&self) {
        self.request.set(self.request.get() + 1);
        self.player.stop();
        self.queue.borrow_mut().clear();
        self.index.set(None);
        *self.radio.borrow_mut() = None;
        self.changed();
    }

    pub fn has_next(&self) -> bool {
        self.index.get().is_some_and(|i| i + 1 < self.queue.borrow().len())
    }

    /// songs を新しいキューにして、index の曲から鳴らす。1曲だけなら、その曲のラジオを続ける（Android・Windows と同じ）。
    pub fn play_list(self: &Rc<Self>, songs: Vec<Song>, index: usize) {
        if songs.is_empty() {
            return;
        }
        let single = songs.len() == 1;
        *self.radio.borrow_mut() = single.then(|| (None, None));
        *self.queue.borrow_mut() = songs;
        self.play_at(index);
        if single {
            self.refill();
        }
    }

    pub fn play_at(self: &Rc<Self>, index: usize) {
        let Some(song) = self.queue.borrow().get(index).cloned() else { return };
        self.index.set(Some(index));
        let request = self.request.get() + 1;
        self.request.set(request);
        self.changed();
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            let result = this.player.play(&song.video_id).await;
            if this.request.get() != request {
                return;
            }
            if let Err(e) = result {
                eprintln!("play failed: {e}");
                // その曲だけの失敗なら次へ。どの曲でも起きる失敗では止める（飛ばし続けない）
                if e.is_this_song_only() {
                    this.next();
                    return;
                }
            }
            this.changed();
            if this.upcoming().len() <= RADIO_REFILL_LEFT {
                this.refill();
            }
        });
    }

    pub fn next(self: &Rc<Self>) {
        match self.index.get() {
            Some(i) if i + 1 < self.queue.borrow().len() => self.play_at(i + 1),
            _ => {
                self.player.stop();
                self.changed();
            }
        }
    }

    pub fn previous(self: &Rc<Self>) {
        if self.player.position_ms().unwrap_or(0) > RESTART_THRESHOLD_MS {
            self.player.seek_ms(0);
            return;
        }
        match self.index.get() {
            Some(i) if i > 0 => self.play_at(i - 1),
            _ => self.player.seek_ms(0),
        }
    }

    pub fn toggle(&self) {
        self.player.toggle();
        self.changed();
    }

    /// ラジオの曲をキューの後ろに足す（ラジオを続けているときだけ）。
    fn refill(self: &Rc<Self>) {
        let Some((playlist, continuation)) = self.radio.borrow().clone() else { return };
        if self.refilling.replace(true) {
            return;
        }
        let seed = self.queue.borrow().first().map(|s| s.video_id.clone());
        let repo = self.repo.clone();
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            let playlist_id = playlist.clone().or_else(|| seed.as_ref().map(|v| format!("RDAMVM{v}")));
            let video = if continuation.is_none() { seed.clone() } else { None };
            let page = runtime::run(async move { repo.up_next(video.as_deref(), playlist_id.as_deref(), continuation.as_deref()).await }).await;
            this.refilling.set(false);
            let Ok(page) = page else { return };
            // ラジオをやめていたら（ほかのキューに替えていたら）足さない
            if this.radio.borrow().is_none() {
                return;
            }
            let mut queue = this.queue.borrow_mut();
            let fresh: Vec<Song> = page.songs.into_iter().filter(|s| !queue.iter().any(|q| q.video_id == s.video_id)).collect();
            queue.extend(fresh);
            drop(queue);
            *this.radio.borrow_mut() = Some((page.playlist_id.or(playlist), page.continuation));
            this.changed();
        });
    }
}
