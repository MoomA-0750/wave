//! 再生の流れ（主スレッド）: キュー・今の曲・次と前・曲の終わりで次へ・キューの終わりでラジオを継ぎ足す。
//! 画面は on_change に登録して、変わるたびに描き直す。
//! キューの曲には項目の ID を振る（端末の連携で、ほかの端末と同じ曲を指すのに使う）。

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

/// キューの曲がどこから来たか（ほかの端末と同じ区別）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Origin {
    /// 選んだ一覧・アルバムの曲。
    Source,
    /// ラジオで継ぎ足した曲。
    Suggestion,
    /// 「次に再生」「キューに追加」で足した曲。
    UserAdded,
}

#[derive(Clone, Debug)]
pub struct QueueItem {
    pub song: Song,
    pub item_id: String,
    pub origin: Origin,
}

impl QueueItem {
    pub fn new(song: Song, origin: Origin) -> Self {
        Self { song, item_id: new_item_id(), origin }
    }
}

/// 項目の ID（UUID v4 の形）。
pub fn new_item_id() -> String {
    let mut b = [0u8; 16];
    getrandom::fill(&mut b).expect("the OS random source");
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h = hex::encode(b);
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

/// 手元の操作のうち、ほかの端末を操作している間は、その端末への命令にするもの。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Transport {
    Toggle,
    Next,
    Previous,
}

/// 繰り返し（ほかの端末と同じ3つ）。
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Repeat {
    #[default]
    Off,
    /// キュー全体を繰り返す（最後の曲の次は最初の曲）。
    All,
    /// 今の曲を繰り返す（曲の終わりで同じ曲を頭から）。
    One,
}

/// 本人が手元で再生を始めた・止めたことの知らせ（端末の連携が、家の再生を引き受けるのに使う）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UserAction {
    /// 曲を選んだ（キューを置き換えた・飛んだ）。
    Selected,
    /// 再生ボタンで再開した。
    Resumed,
    Paused,
}

/// ラジオ・一覧の出どころ（ほかの端末に渡すキューに入れる）。
#[derive(Clone, Debug, Default)]
pub struct QueueSource {
    /// 一覧（プレイリスト・アルバム）の ID。
    pub playlist_id: Option<String>,
    /// ラジオを続けているか（続けていれば最初の曲が種）。
    pub radio: bool,
    pub radio_playlist_id: Option<String>,
    pub continuation: Option<String>,
}

type UserListeners = RefCell<Vec<Box<dyn Fn(UserAction)>>>;
type Interceptor = Box<dyn Fn(Transport) -> bool>;
/// 曲と「次に再生」か。true を返したら手元のキューには入れない。
type QueueInterceptor = Box<dyn Fn(&Song, bool) -> bool>;

pub struct Playback {
    player: Player,
    repo: MusicRepository,
    queue: RefCell<Vec<QueueItem>>,
    index: Cell<Option<usize>>,
    /// ラジオの続き（RDAMVM の流れ）。無ければ最初の曲から始める。
    radio: RefCell<Option<(Option<String>, Option<String>)>>,
    /// 選んだ一覧の ID（ほかの端末に渡す出どころ）。
    source_id: RefCell<Option<String>>,
    refilling: Cell<bool>,
    repeat: Cell<Repeat>,
    /// シャッフルを入れる前の並び（項目の ID）。シャッフル中だけ持つ。
    unshuffled: RefCell<Option<Vec<String>>>,
    /// 前回のキューを戻して、まだ読み込んでいない（再生を押したら、この位置から読み込んで鳴らす）。
    restored_position: Cell<Option<u64>>,
    /// 曲を頼んだ回数。読み込みの途中で別の曲が頼まれたら、古い方の結果は捨てる。
    request: Cell<u64>,
    listeners: RefCell<Vec<Box<dyn Fn()>>>,
    user_listeners: UserListeners,
    /// ほかの端末を操作している間に、手元の再生・次・前を横取りする（true を返したら手元では何もしない）。
    interceptor: RefCell<Option<Interceptor>>,
    /// ほかの端末を操作している間に、「次に再生」「キューに追加」をその端末のキューへの追加にする。
    queue_interceptor: RefCell<Option<QueueInterceptor>>,
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
            source_id: RefCell::default(),
            refilling: Cell::default(),
            repeat: Cell::default(),
            unshuffled: RefCell::default(),
            restored_position: Cell::default(),
            request: Cell::default(),
            listeners: RefCell::default(),
            user_listeners: RefCell::default(),
            interceptor: RefCell::default(),
            queue_interceptor: RefCell::default(),
            _watch: RefCell::default(),
        });
        let weak = Rc::downgrade(&playback);
        let watch = playback.player.watch(move |failed| {
            if let Some(playback) = weak.upgrade() {
                if failed {
                    eprintln!("song failed, skipping");
                }
                // 1曲の繰り返しは、曲の終わりで同じ曲を頭から（失敗したときは次へ）
                match (failed, playback.repeat.get(), playback.index.get()) {
                    (false, Repeat::One, Some(i)) => playback.start_at(i, 0, true),
                    _ => playback.advance(),
                }
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

    /// 本人の手元の操作（曲を選んだ・再開した・止めた）を聞く。
    pub fn on_user(&self, listener: impl Fn(UserAction) + 'static) {
        self.user_listeners.borrow_mut().push(Box::new(listener));
    }

    pub fn set_interceptor(&self, interceptor: Option<Interceptor>) {
        *self.interceptor.borrow_mut() = interceptor;
    }

    pub fn set_queue_interceptor(&self, interceptor: Option<QueueInterceptor>) {
        *self.queue_interceptor.borrow_mut() = interceptor;
    }

    fn queue_intercepted(&self, song: &Song, next: bool) -> bool {
        self.queue_interceptor.borrow().as_ref().is_some_and(|f| f(song, next))
    }

    fn intercepted(&self, transport: Transport) -> bool {
        self.interceptor.borrow().as_ref().is_some_and(|f| f(transport))
    }

    fn user(&self, action: UserAction) {
        for listener in self.user_listeners.borrow().iter() {
            listener(action);
        }
    }

    fn changed(&self) {
        for listener in self.listeners.borrow().iter() {
            listener();
        }
    }

    pub fn current(&self) -> Option<Song> {
        self.current_item().map(|i| i.song)
    }

    pub fn current_item(&self) -> Option<QueueItem> {
        self.index.get().and_then(|i| self.queue.borrow().get(i).cloned())
    }

    pub fn upcoming(&self) -> Vec<Song> {
        let start = self.index.get().map_or(0, |i| i + 1);
        self.queue.borrow().iter().skip(start).map(|i| i.song.clone()).collect()
    }

    /// 今の曲のすぐ後に入れる（何も鳴っていなければ、その曲から流す）。
    pub fn play_next(self: &Rc<Self>, song: Song) {
        if self.queue_intercepted(&song, true) {
            return;
        }
        match self.index.get() {
            Some(i) => {
                self.queue.borrow_mut().insert(i + 1, QueueItem::new(song, Origin::UserAdded));
                self.changed();
            }
            None => self.play_list(vec![song], 0),
        }
    }

    /// キューの最後に入れる（何も鳴っていなければ、その曲から流す）。ラジオの曲より前に入れる作りにはしない（Windows と同じく後ろ）。
    pub fn add_to_queue(self: &Rc<Self>, song: Song) {
        if self.queue_intercepted(&song, false) {
            return;
        }
        if self.index.get().is_none() {
            self.play_list(vec![song], 0);
            return;
        }
        self.queue.borrow_mut().push(QueueItem::new(song, Origin::UserAdded));
        self.changed();
    }

    /// キューの全部と、今の曲の位置。
    pub fn queue(&self) -> (Vec<Song>, Option<usize>) {
        (self.queue.borrow().iter().map(|i| i.song.clone()).collect(), self.index.get())
    }

    /// キューの項目（ID つき）と、今の曲の位置。
    pub fn queue_items(&self) -> (Vec<QueueItem>, Option<usize>) {
        (self.queue.borrow().clone(), self.index.get())
    }

    pub fn source(&self) -> QueueSource {
        let radio = self.radio.borrow().clone();
        QueueSource {
            playlist_id: self.source_id.borrow().clone(),
            radio: radio.is_some(),
            radio_playlist_id: radio.as_ref().and_then(|r| r.0.clone()),
            continuation: radio.and_then(|r| r.1),
        }
    }

    /// ログアウトしたときなど: 止めてキューを空にする。
    pub fn clear(&self) {
        self.request.set(self.request.get() + 1);
        self.player.stop();
        self.queue.borrow_mut().clear();
        self.index.set(None);
        *self.radio.borrow_mut() = None;
        *self.source_id.borrow_mut() = None;
        self.unshuffled.borrow_mut().take();
        self.restored_position.set(None);
        self.changed();
    }

    pub fn has_next(&self) -> bool {
        self.index.get().is_some_and(|i| i + 1 < self.queue.borrow().len() || (self.repeat.get() == Repeat::All && !self.queue.borrow().is_empty()))
    }

    pub fn repeat(&self) -> Repeat {
        self.repeat.get()
    }

    pub fn set_repeat(&self, repeat: Repeat) {
        if self.repeat.replace(repeat) != repeat {
            self.changed();
        }
    }

    /// 繰り返しのボタン: 切 → 全体 → 1曲 → 切。
    pub fn cycle_repeat(&self) {
        self.set_repeat(match self.repeat.get() {
            Repeat::Off => Repeat::All,
            Repeat::All => Repeat::One,
            Repeat::One => Repeat::Off,
        });
    }

    pub fn shuffled(&self) -> bool {
        self.unshuffled.borrow().is_some()
    }

    /// シャッフルを入れると今の曲より後ろを混ぜ、切ると残りを入れる前の並びに戻す（後から足した曲は後ろのまま）。同じなら何もしない。
    pub fn set_shuffle(&self, on: bool) {
        if on == self.shuffled() {
            return;
        }
        let start = self.index.get().map_or(0, |i| i + 1);
        let mut queue = self.queue.borrow_mut();
        if on {
            *self.unshuffled.borrow_mut() = Some(queue.iter().map(|q| q.item_id.clone()).collect());
            if start < queue.len() {
                shuffle(&mut queue[start..]);
            }
        } else {
            let order = self.unshuffled.borrow_mut().take().unwrap_or_default();
            if start < queue.len() {
                let rank = |item: &QueueItem| order.iter().position(|id| *id == item.item_id).unwrap_or(usize::MAX);
                queue[start..].sort_by_key(rank);
            }
        }
        drop(queue);
        self.changed();
    }

    /// songs を新しいキューにして、index の曲から鳴らす。1曲だけなら、その曲のラジオを続ける（Android・Windows と同じ）。
    pub fn play_list(self: &Rc<Self>, songs: Vec<Song>, index: usize) {
        self.play_list_from(songs, index, None);
    }

    /// 一覧（プレイリスト・アルバム）から鳴らす。source_id はほかの端末に渡す出どころ。
    pub fn play_list_from(self: &Rc<Self>, songs: Vec<Song>, index: usize, source_id: Option<String>) {
        if songs.is_empty() {
            return;
        }
        let single = songs.len() == 1;
        *self.radio.borrow_mut() = single.then_some((None, None));
        *self.source_id.borrow_mut() = source_id;
        *self.queue.borrow_mut() = songs.into_iter().map(|s| QueueItem::new(s, Origin::Source)).collect();
        // 新しい一覧はその並びで流す（シャッフルは切る）
        self.unshuffled.borrow_mut().take();
        self.start_at(index, 0, true);
        self.user(UserAction::Selected);
        if single {
            self.refill();
        }
    }

    /// 一覧をシャッフルで流す（どこかの曲から始め、シャッフルを入れる）。
    pub fn play_shuffled(self: &Rc<Self>, songs: Vec<Song>, source_id: Option<String>) {
        if songs.is_empty() {
            return;
        }
        let mut b = [0u8; 8];
        getrandom::fill(&mut b).expect("the OS random source");
        let start = (u64::from_le_bytes(b) % songs.len() as u64) as usize;
        self.play_list_from(songs, start, source_id);
        self.set_shuffle(true);
    }

    /// 本人がキューの曲を選んだ。
    pub fn play_at(self: &Rc<Self>, index: usize) {
        self.start_at(index, 0, true);
        self.user(UserAction::Selected);
    }

    /// ほかの端末から引き取ったキューを、position_ms から鳴らす（playing でなければ止めたまま用意する）。本人の操作としては知らせない。
    pub fn adopt(self: &Rc<Self>, items: Vec<QueueItem>, index: usize, position_ms: u64, playing: bool, source: QueueSource) {
        if items.get(index).is_none() {
            return;
        }
        *self.queue.borrow_mut() = items;
        self.unshuffled.borrow_mut().take();
        *self.source_id.borrow_mut() = source.playlist_id;
        *self.radio.borrow_mut() = source.radio.then_some((source.radio_playlist_id, source.continuation));
        self.start_at(index, position_ms, playing);
    }

    fn start_at(self: &Rc<Self>, index: usize, position_ms: u64, playing: bool) {
        let Some(song) = self.queue.borrow().get(index).map(|i| i.song.clone()) else { return };
        self.restored_position.set(None);
        self.index.set(Some(index));
        let request = self.request.get() + 1;
        self.request.set(request);
        self.changed();
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            let result = this.player.play_from(&song.video_id, position_ms, playing).await;
            if this.request.get() != request {
                return;
            }
            if let Err(e) = result {
                eprintln!("play failed: {e}");
                // その曲だけの失敗なら次へ。どの曲でも起きる失敗では止める（飛ばし続けない）
                if e.is_this_song_only() {
                    this.advance();
                    return;
                }
            }
            this.changed();
            if this.upcoming().len() <= RADIO_REFILL_LEFT {
                this.refill();
            }
        });
    }

    /// 次へ（本人の操作。ほかの端末を操作している間は、その端末への命令になる）。
    pub fn next(self: &Rc<Self>) {
        if self.intercepted(Transport::Next) {
            return;
        }
        self.advance();
        if self.index.get().is_some() {
            self.user(UserAction::Selected);
        }
    }

    /// 曲の終わり・失敗・遠隔の「次へ」。
    pub fn advance(self: &Rc<Self>) {
        match self.index.get() {
            Some(i) if i + 1 < self.queue.borrow().len() => self.start_at(i + 1, 0, true),
            // 全体の繰り返しは、最後の曲の次は最初の曲
            Some(_) if self.repeat.get() == Repeat::All && !self.queue.borrow().is_empty() => self.start_at(0, 0, true),
            _ => {
                self.player.stop();
                self.changed();
            }
        }
    }

    pub fn previous(self: &Rc<Self>) {
        if self.intercepted(Transport::Previous) {
            return;
        }
        self.back();
    }

    /// 前へ（遠隔の「前へ」もここ）。
    pub fn back(self: &Rc<Self>) {
        if self.player.position_ms().unwrap_or(0) > RESTART_THRESHOLD_MS {
            self.player.seek_ms(0);
            self.changed();
            return;
        }
        match self.index.get() {
            Some(i) if i > 0 => self.start_at(i - 1, 0, true),
            _ => {
                self.player.seek_ms(0);
                self.changed();
            }
        }
    }

    pub fn toggle(self: &Rc<Self>) {
        if self.intercepted(Transport::Toggle) {
            return;
        }
        // 前回のキューを戻しただけなら、ここで読み込んで続きから鳴らす
        if let (Some(position), Some(index)) = (self.restored_position.get(), self.index.get()) {
            self.start_at(index, position, true);
            self.user(UserAction::Resumed);
            return;
        }
        let was_playing = self.player.is_playing();
        self.player.toggle();
        self.changed();
        if self.current_item().is_some() {
            self.user(if was_playing { UserAction::Paused } else { UserAction::Resumed });
        }
    }

    /// 遠隔の再生・一時停止（本人の操作としては知らせない）。
    pub fn set_playing(self: &Rc<Self>, playing: bool) {
        if let (true, Some(position), Some(index)) = (playing, self.restored_position.get(), self.index.get()) {
            self.start_at(index, position, true);
            return;
        }
        if self.current_item().is_some() && self.player.is_playing() != playing {
            self.player.toggle();
            self.changed();
        }
    }

    /// 前回のキューを戻す（読み込まずに、今の曲と位置だけ置く。再生を押すと続きから鳴らす）。
    pub fn restore(&self, items: Vec<QueueItem>, index: usize, position_ms: u64, source: QueueSource, repeat: Repeat, unshuffled: Option<Vec<String>>) {
        if items.get(index).is_none() {
            return;
        }
        *self.queue.borrow_mut() = items;
        self.index.set(Some(index));
        *self.source_id.borrow_mut() = source.playlist_id;
        *self.radio.borrow_mut() = source.radio.then_some((source.radio_playlist_id, source.continuation));
        self.repeat.set(repeat);
        *self.unshuffled.borrow_mut() = unshuffled;
        self.restored_position.set(Some(position_ms));
        self.changed();
    }

    /// 次の起動に持ち越す位置（読み込む前なら戻した位置）。
    pub fn position_ms(&self) -> u64 {
        self.restored_position.get().unwrap_or_else(|| self.player.position_ms().unwrap_or(0))
    }

    /// シャッフルを入れる前の並び（保存用）。
    pub fn unshuffled_order(&self) -> Option<Vec<String>> {
        self.unshuffled.borrow().clone()
    }

    /// 遠隔のキューへの追加: after_item_id の曲の直後、無ければ next なら今の曲の直後、そうでなければ自分で足した曲のかたまりの最後へ。同じ ID がもうあれば入れない。
    pub fn insert_items(&self, items: Vec<QueueItem>, next: bool, after_item_id: Option<&str>) -> bool {
        let Some(current) = self.index.get() else { return false };
        let mut queue = self.queue.borrow_mut();
        let fresh: Vec<QueueItem> = items.into_iter().filter(|i| !queue.iter().any(|q| q.item_id == i.item_id)).collect();
        if fresh.is_empty() {
            return false;
        }
        let after = after_item_id.and_then(|id| queue.iter().position(|q| q.item_id == id)).filter(|&p| p >= current);
        let at = match after {
            Some(p) => p + 1,
            None if next => current + 1,
            None => {
                // 今の曲の後ろに続く、足した曲のかたまりの最後
                let mut p = current + 1;
                while queue.get(p).is_some_and(|q| q.origin == Origin::UserAdded) {
                    p += 1;
                }
                p
            }
        };
        let at = at.min(queue.len());
        for (offset, item) in fresh.into_iter().enumerate() {
            queue.insert(at + offset, item);
        }
        drop(queue);
        self.changed();
        true
    }

    /// 遠隔の並べ替え: item_id の曲（今の曲より後ろ）を after_item_id の曲の直後（無ければ今の曲の直後）へ。
    pub fn move_item(&self, item_id: &str, after_item_id: Option<&str>) -> bool {
        let Some(current) = self.index.get() else { return false };
        let mut queue = self.queue.borrow_mut();
        let Some(from) = queue.iter().position(|q| q.item_id == item_id).filter(|&p| p > current) else { return false };
        let item = queue.remove(from);
        let at = match after_item_id {
            Some(id) => match queue.iter().position(|q| q.item_id == id).filter(|&p| p >= current) {
                Some(p) => p + 1,
                None => {
                    queue.insert(from, item);
                    return false;
                }
            },
            None => current + 1,
        };
        queue.insert(at, item);
        drop(queue);
        self.changed();
        true
    }

    /// 遠隔のキューから外す: 今の曲より後ろにある item_ids の曲だけ。
    pub fn remove_items(&self, item_ids: &[String]) -> bool {
        let Some(current) = self.index.get() else { return false };
        let mut queue = self.queue.borrow_mut();
        let before = queue.len();
        let mut position = 0;
        queue.retain(|q| {
            let keep = position <= current || !item_ids.contains(&q.item_id);
            position += 1;
            keep
        });
        let removed = queue.len() != before;
        drop(queue);
        if removed {
            self.changed();
        }
        removed
    }

    /// 遠隔の「次に流れる曲」の消去（今の曲が current_item_id のときだけ）。ラジオも止める。
    pub fn clear_flow(&self, current_item_id: &str) -> bool {
        let Some(current) = self.index.get() else { return false };
        if self.queue.borrow().get(current).map(|q| q.item_id.as_str()) != Some(current_item_id) {
            return false;
        }
        self.queue.borrow_mut().truncate(current + 1);
        *self.radio.borrow_mut() = None;
        self.changed();
        true
    }

    /// ラジオの曲をキューの後ろに足す（ラジオを続けているときだけ）。
    fn refill(self: &Rc<Self>) {
        let Some((playlist, continuation)) = self.radio.borrow().clone() else { return };
        if self.refilling.replace(true) {
            return;
        }
        let seed = self.queue.borrow().first().map(|s| s.song.video_id.clone());
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
            let fresh: Vec<QueueItem> =
                page.songs.into_iter().filter(|s| !queue.iter().any(|q| q.song.video_id == s.video_id)).map(|s| QueueItem::new(s, Origin::Suggestion)).collect();
            queue.extend(fresh);
            drop(queue);
            *this.radio.borrow_mut() = Some((page.playlist_id.or(playlist), page.continuation));
            this.changed();
        });
    }
}

/// 並びを混ぜる（Fisher–Yates）。
fn shuffle<T>(items: &mut [T]) {
    for i in (1..items.len()).rev() {
        let mut bytes = [0u8; 8];
        getrandom::fill(&mut bytes).expect("the OS random source");
        let j = (u64::from_le_bytes(bytes) % (i as u64 + 1)) as usize;
        items.swap(i, j);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn item_ids_look_like_uuid_v4() {
        let id = new_item_id();
        assert_eq!(id.len(), 36);
        assert_eq!(&id[14..15], "4");
        assert_ne!(id, new_item_id());
    }

    #[test]
    fn shuffle_keeps_every_item() {
        let mut items: Vec<u32> = (0..50).collect();
        shuffle(&mut items);
        let mut sorted = items.clone();
        sorted.sort();
        assert_eq!(sorted, (0..50).collect::<Vec<_>>());
    }
}
