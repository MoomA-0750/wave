//! 窓の並び（Mac と同じ）: 上の帯に戻る・進む、中央にホーム・検索の欄・Claude、右端にアカウント。左にマイライブラリ、中央にページ、
//! 右に再生中・キュー・Claude の欄（窓が狭いときは出さない）、下に再生の操作の帯。
//! 全画面の再生中の画面（full_view）は上の2段を覆い、下の帯は出したまま。映像だけの層は下の帯も覆う。部品は libadwaita のもの。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use wave_core::music::models::{MusicVideo, Song};
use wave_core::music::repository::MusicRepository;

use super::{images, pages};
use crate::playback::{Origin, Playback, QueueItem};
use crate::runtime;

/// 右の欄に出すもの。
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum RightView {
    #[default]
    Now,
    Queue,
    Ai,
}

/// 映像の様子（「動画」にしているときの読み込みと、今の位置に映像があるか）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VideoState {
    /// 動画にしていない（か、この曲に映像が無い）。ジャケットを出す。
    Off,
    Loading,
    Failed,
    Showing,
    /// 読めたが、今の位置に当たる映像が無い。
    Gap,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
enum Load {
    #[default]
    Idle,
    Loading,
    Failed,
    Ready,
}

/// 主の窓の表示の状態（Mac の MainWindowModel の分）。
#[derive(Clone, Copy, Debug, Default)]
struct View {
    right_open: bool,
    right_view: RightView,
    /// Claude を開く前の右の欄（開いていたか・中身）。Claude を閉じたらここへ戻す。
    before_ai: (bool, RightView),
    /// 窓の幅の段（0: 1400 以上、1: 1180〜、2: 1000〜、3: 880〜、4: それ未満）。
    width_level: u8,
    /// 全画面の再生中の画面。
    full: bool,
    /// 全画面の再生中の画面で、映像だけを窓いっぱいに出している。
    video_only: bool,
    /// 映像だけの表示を、右の欄・小窓の「拡大」から直に開いた（やめると元の画面に戻す）。
    video_only_from_expand: bool,
    /// 「曲｜動画」の動画を選んでいる。
    show_video: bool,
    pip_open: bool,
    /// キューの欄で履歴を出している。
    queue_history: bool,
}

/// 状態に合わせて出し入れする、窓の大きな部品。
#[derive(Default)]
struct Parts {
    toolbar: Option<adw::ToolbarView>,
    content: Option<gtk::Stack>,
    side: Option<gtk::Widget>,
    side_stack: Option<gtk::Stack>,
    video_layer: Option<gtk::Widget>,
    search: Option<gtk::SearchEntry>,
    pip: Option<gtk::Window>,
}

pub struct App {
    pub repo: MusicRepository,
    pub lyrics: wave_core::lyrics::service::LyricsService,
    pub playback: Rc<Playback>,
    nav: adw::NavigationView,
    toasts: adw::ToastOverlay,
    /// 曲に重ねる MV の流し手（gtk4paintablesink が無ければ None）。
    pub video: Option<crate::video::VideoPlayer>,
    /// ダウンロード（取った曲・丸ごと取ったもの）。
    pub downloads: Rc<crate::downloads::Downloads>,
    /// 端末の連携。
    pub connect: Rc<crate::connect::Connect>,
    /// 登録したアーティストの新作。
    pub releases: Rc<crate::releases::Releases>,
    /// RSS の番組（登録と聴いたところ）。
    pub podcasts: Rc<crate::podcasts::Podcasts>,
    /// Claude との会話。
    pub ai: Rc<crate::ai_chat::AiChat>,
    /// 自分で編集できるプレイリスト（曲のメニューの「プレイリストに追加」に並べる）。
    pub editable_playlists: RefCell<Vec<wave_core::music::models::PlaylistSummary>>,
    view: Cell<View>,
    parts: RefCell<Parts>,
    view_listeners: RefCell<Vec<Rc<dyn Fn()>>>,
    /// 今の曲の MV と、それを探した曲。
    mv: RefCell<Option<MusicVideo>>,
    mv_for: RefCell<Option<String>>,
    video_load: Cell<Load>,
    video_gap: Cell<bool>,
    /// 戻ったページ（「進む」で開き直す。新しいページを開いたら消す）。
    forward: RefCell<Vec<adw::NavigationPage>>,
    going_forward: Cell<bool>,
    volume_before_mute: Cell<f64>,
}

impl App {
    /// 窓（ダイアログの親）。
    pub fn window(&self) -> Option<gtk::Window> {
        self.nav.root().and_downcast::<gtk::Window>()
    }

    /// 検索の履歴に足す。
    pub fn remember_search(&self, entry: crate::settings::SearchHistoryEntry) {
        self.downloads.update_settings(|s| s.add_search(entry));
    }

    /// 画面の下に短い知らせを出す。
    pub fn toast(&self, text: &str) {
        self.toasts.add_toast(adw::Toast::new(text));
    }

    /// ページを積む。同じタグのページが下に残っていると AdwNavigationView が積むのを断るので、
    /// そのときは番号を付けて重ならないようにする（アルバム → アーティスト → 別のアルバム、設定を2回開く、など）。
    /// 新しいページを開いたら「進む」の行き先は消す（ブラウザと同じ）。
    pub fn push(&self, page: adw::NavigationPage) {
        if !self.going_forward.get() {
            self.forward.borrow_mut().clear();
        }
        if let Some(tag) = page.tag() {
            let base = tag.split('#').next().unwrap_or("").to_string();
            page.set_tag(Some(&base));
            let mut n = 2;
            while self.nav.find_page(&page_tag(&base, n - 1)).is_some() {
                page.set_tag(Some(&page_tag(&base, n)));
                n += 1;
            }
        }
        self.nav.push(&page);
    }

    /// 今いちばん上のページの種類（タグの番号を除いた名前）。
    pub fn visible_kind(&self) -> Option<String> {
        visible_kind(&self.nav)
    }

    /// 戻る（上の帯の ←）。
    pub fn go_back(&self) {
        if self.nav.navigation_stack().n_items() > 1 {
            self.nav.pop();
        }
    }

    /// 進む（上の帯の →）: 戻ったページを開き直す。
    pub fn go_forward(&self) {
        let page = self.forward.borrow_mut().pop();
        if let Some(page) = page {
            self.going_forward.set(true);
            self.push(page);
            self.going_forward.set(false);
        }
    }


    /// 今映している曲（ほかの端末で鳴っていればその曲、そうでなければ手元の今の曲）。
    pub fn shown_song(&self) -> Option<Song> {
        match self.connect.mirror() {
            Some(mirror) => Some(mirror.song),
            None => self.playback.current(),
        }
    }

    /// 今の位置（ほかの端末を映している間はその端末の位置）。
    pub fn position_ms(&self) -> u64 {
        match self.connect.mirror() {
            Some(mirror) => mirror.position_ms.max(0) as u64,
            None => self.playback.position_ms(),
        }
    }

    /// その位置へ飛ぶ（ほかの端末を映している間はその端末へ頼む）。
    pub fn seek_ms(&self, position_ms: u64) {
        if self.connect.mirror().is_some() {
            self.connect.remote_seek(position_ms as i64);
        } else {
            self.playback.player().seek_ms(position_ms);
        }
    }
}

// MARK: 表示の状態（右の欄・全画面・映像）
impl App {
    /// 表示の状態が変わったら呼ぶものを足す（再生の様子の変化では呼ばない。それは playback.on_change で）。
    pub fn on_view(&self, listener: impl Fn() + 'static) {
        self.view_listeners.borrow_mut().push(Rc::new(listener));
    }

    fn update_view(&self, change: impl FnOnce(&mut View)) {
        let mut view = self.view.get();
        change(&mut view);
        self.view.set(view);
        self.view_changed();
    }

    fn view_changed(&self) {
        self.apply_layout();
        let listeners: Vec<Rc<dyn Fn()>> = self.view_listeners.borrow().clone();
        for listener in listeners {
            listener();
        }
    }

    fn apply_layout(&self) {
        let view = self.view.get();
        let layer = self.video_layer_shown();
        let parts = self.parts.borrow();
        if let Some(stack) = &parts.side_stack {
            let name = match view.right_view {
                RightView::Now => "now",
                RightView::Queue => "queue",
                RightView::Ai => "ai",
            };
            if stack.visible_child_name().as_deref() != Some(name) {
                stack.set_visible_child_name(name);
            }
        }
        if let Some(side) = &parts.side {
            side.set_visible(self.show_right());
        }
        if let Some(content) = &parts.content {
            content.set_visible_child_name(if view.full { "full" } else { "main" });
        }
        if let Some(toolbar) = &parts.toolbar {
            toolbar.set_reveal_top_bars(!view.full);
            toolbar.set_reveal_bottom_bars(!layer);
        }
        if let Some(layer_widget) = &parts.video_layer {
            layer_widget.set_visible(layer);
        }
    }

    pub fn right_view(&self) -> RightView {
        self.view.get().right_view
    }

    /// 右の欄を出しているか。窓の幅が 1180 未満なら出さない（Claude は上のボタンから呼ぶので 880 以上なら出す。Mac と同じ）。
    pub fn show_right(&self) -> bool {
        let view = self.view.get();
        view.right_open && (view.width_level <= 1 || (view.right_view == RightView::Ai && view.width_level <= 3))
    }

    /// 窓の幅の段（0: 1400 以上、1: 1180〜、2: 1000〜、3: 880〜、4: それ未満）。
    fn width_level(&self) -> u8 {
        self.view.get().width_level
    }

    /// 右の欄を開け閉めし、中身を再生中かキューにする。開いているかは設定に覚える（次に開いたときも同じ）。
    pub fn set_right_pane(&self, open: bool, queue: bool) {
        if self.downloads.settings.borrow().right_pane_open != open {
            self.downloads.update_settings(|s| s.right_pane_open = open);
        }
        self.update_view(|v| {
            v.right_open = open;
            v.right_view = if queue { RightView::Queue } else { RightView::Now };
        });
    }

    /// 下の帯のキューのボタン（もう一度押すと右の欄ごと閉じる）。
    pub fn toggle_queue(&self) {
        let view = self.view.get();
        let showing = view.right_open && view.right_view == RightView::Queue;
        self.set_right_pane(!showing, !showing);
    }

    /// 下の帯の「再生中の画面」のボタン・ジャケット。
    pub fn toggle_pane(&self) {
        let view = self.view.get();
        let showing = view.right_open && view.right_view == RightView::Now;
        self.set_right_pane(!showing, false);
    }

    /// キュー（history なら履歴）を開く。
    pub fn show_queue(&self, history: bool) {
        self.update_view(|v| v.queue_history = history);
        self.set_right_pane(true, true);
    }

    /// 上の帯の Claude のボタン（Ctrl+J）。開いていれば、開く前の右の欄に戻す。
    pub fn toggle_ai(&self) {
        let view = self.view.get();
        if view.right_open && view.right_view == RightView::Ai {
            self.close_ai();
        } else {
            self.show_ai();
        }
    }

    pub fn show_ai(&self) {
        let view = self.view.get();
        if !(view.right_open && view.right_view == RightView::Ai) {
            let before = (view.right_open, view.right_view);
            self.update_view(|v| v.before_ai = before);
        }
        self.set_right_pane(true, false);
        self.update_view(|v| v.right_view = RightView::Ai);
    }

    /// Claude を閉じる: 開く前の状態（閉じていた・再生中・キュー）に戻す。Claude の欄の × からも呼ぶ。
    pub fn close_ai(&self) {
        let (open, view) = self.view.get().before_ai;
        self.set_right_pane(open, view == RightView::Queue);
    }

    pub fn is_full(&self) -> bool {
        self.view.get().full
    }

    /// 全画面の再生中の画面を出す・やめる（窓も全画面にする・戻す。Mac と同じ）。
    pub fn set_full_screen(&self, on: bool) {
        self.update_view(|v| {
            v.full = on;
            if !on {
                v.video_only = false;
                v.video_only_from_expand = false;
            }
        });
        self.set_window_fullscreen(on);
    }

    /// 窓を全画面にする・戻す（中の表示は変えない）。
    pub fn set_window_fullscreen(&self, on: bool) {
        if let Some(window) = self.window() {
            if on { window.fullscreen() } else { window.unfullscreen() }
        }
    }

    /// 映像を大きく見る（右の欄の映像・小窓の「拡大」）: 窓の全画面にはせず、窓の中で映像だけをいっぱいに出す。
    pub fn expand_video(self: &Rc<Self>) {
        if let Some(window) = self.window() {
            window.present();
        }
        self.update_view(|v| {
            v.show_video = true;
            v.full = true;
            v.video_only = true;
            v.video_only_from_expand = true;
        });
        self.ensure_video();
    }

    /// 映像だけを窓いっぱいに出す・やめる。「拡大」から開いたときは、やめると元の画面に戻す。
    pub fn toggle_video_only(&self) {
        let view = self.view.get();
        if view.video_only {
            if view.video_only_from_expand {
                self.set_full_screen(false);
            } else {
                self.update_view(|v| v.video_only = false);
            }
        } else {
            self.update_view(|v| {
                v.video_only = true;
                v.full = true;
            });
        }
    }

    /// 全画面からページを開くときは、全画面を閉じてから開く。
    pub fn open_from_full(&self, open: impl FnOnce()) {
        if self.is_full() {
            self.set_full_screen(false);
        }
        open();
    }

    fn video_layer_shown(&self) -> bool {
        let view = self.view.get();
        view.full && view.video_only && view.show_video && self.has_video()
    }

    /// 今の曲に MV がある（ほかの端末を映している間は出さない）。
    pub fn has_video(&self) -> bool {
        self.video.is_some() && self.mv.borrow().is_some() && self.connect.mirror().is_none()
    }

    /// 「曲｜動画」で動画を選んでいる。
    pub fn show_video(&self) -> bool {
        self.view.get().show_video
    }

    pub fn pip_open(&self) -> bool {
        self.view.get().pip_open
    }

    pub fn video_state(&self) -> VideoState {
        if !(self.show_video() && self.has_video()) {
            return VideoState::Off;
        }
        match self.video_load.get() {
            Load::Idle | Load::Loading => VideoState::Loading,
            Load::Failed => VideoState::Failed,
            Load::Ready if self.video_gap.get() => VideoState::Gap,
            Load::Ready => VideoState::Showing,
        }
    }

    /// 「曲｜動画」を切り替える。
    pub fn set_show_video(self: &Rc<Self>, on: bool) {
        if self.show_video() == on {
            return;
        }
        self.update_view(|v| v.show_video = on);
        if on {
            self.ensure_video();
        } else {
            self.stop_video();
            self.view_changed();
        }
    }

    /// 動画にしていて MV があれば読み込む（読み中・読めたなら何もしない）。
    fn ensure_video(self: &Rc<Self>) {
        let Some(mv) = self.mv.borrow().clone() else { return };
        if self.video.is_none() || !self.show_video() || matches!(self.video_load.get(), Load::Loading | Load::Ready) {
            return;
        }
        self.video_load.set(Load::Loading);
        self.video_gap.set(true);
        self.view_changed();
        let app = self.clone();
        gtk::glib::spawn_future_local(async move {
            let Some(video) = app.video.as_ref() else { return };
            let ok = video.load(&mv).await;
            // 読んでいる間に曲が変わっていたら捨てる
            if app.mv.borrow().as_ref().map(|m| m.video_id.as_str()) != Some(mv.video_id.as_str()) {
                return;
            }
            app.video_load.set(if ok { Load::Ready } else { Load::Failed });
            app.view_changed();
        });
    }

    fn stop_video(&self) {
        if let Some(video) = self.video.as_ref() {
            video.stop();
        }
        self.video_load.set(Load::Idle);
        self.video_gap.set(true);
    }

    /// 映像を読めなかったときの「もう一度」。
    pub fn retry_video(self: &Rc<Self>) {
        self.stop_video();
        self.ensure_video();
    }

    /// 映像の小窓を出す・閉じる。
    pub fn toggle_pip(self: &Rc<Self>) {
        if self.pip_open() { self.close_pip() } else { self.open_pip() }
    }

    /// 映像の小窓を出す（右の欄は動画にし、窓の中で映像だけを出していたらそちらは閉じる。同じ映像を2か所に大きく出さない）。
    pub fn open_pip(self: &Rc<Self>) {
        if self.video.is_none() {
            return;
        }
        self.set_show_video(true);
        if self.view.get().video_only {
            self.toggle_video_only();
        }
        let existing = self.parts.borrow().pip.clone();
        let window = existing.unwrap_or_else(|| {
            let window = mini_window(self);
            self.parts.borrow_mut().pip = Some(window.clone());
            window
        });
        self.update_view(|v| v.pip_open = true);
        window.present();
    }

    pub fn close_pip(&self) {
        let window = self.parts.borrow_mut().pip.take();
        self.update_view(|v| v.pip_open = false);
        if let Some(window) = window {
            window.destroy();
        }
    }

    /// 小窓を閉じて、主の窓で映像を大きく出す（小窓の「wave に戻す」・ダブルクリック）。
    pub fn pip_back_to_app(self: &Rc<Self>) {
        self.close_pip();
        self.expand_video();
    }
}

/// 右の欄の幅。
const SIDE_WIDTH: i32 = 340;
/// 右の欄のジャケット・映像の大きさ。
const SIDE_ART: i32 = 308;

/// 種類 kind の n 枚目のページのタグ（1枚目は種類の名前のまま）。
fn page_tag(kind: &str, n: usize) -> String {
    if n <= 1 { kind.to_string() } else { format!("{kind}#{n}") }
}

fn visible_kind(nav: &adw::NavigationView) -> Option<String> {
    nav.visible_page().and_then(|p| p.tag()).map(|t| t.split('#').next().unwrap_or("").to_string())
}

const STYLE: &str = "
.artwork { border-radius: 8px; }
.artwork-frame { background-color: alpha(currentColor, 0.08); }
.artwork.round { border-radius: 9999px; }
.pane { background-color: @view_bg_color; border-radius: 12px; }
.card-button { padding: 6px; border-radius: 12px; }
.play-large { min-width: 48px; min-height: 48px; }
.player-bar { padding: 8px 16px; }
.song-list { background: transparent; }
.video { background-color: black; }
.video-layer { background-color: black; }
.video-frame { background-color: black; }
.video-frame.side { border-radius: 8px; }
.video-note { color: white; }
.ai-user { background-color: alpha(@accent_bg_color, 0.15); border-radius: 12px; padding: 6px 10px; }
.like-button.liked { color: @accent_color; }
.np-card { background-color: alpha(currentColor, 0.05); border-radius: 8px; padding: 16px; }
.text-link { color: @accent_color; padding: 0 4px; min-height: 0; }
.accent-text { color: @accent_color; }
.queue-list { background: transparent; }
.queue-row { padding: 4px 2px; border-radius: 6px; }
.queue-row.drop-above { box-shadow: inset 0 2px @accent_color; }
.queue-row.drop-below { box-shadow: inset 0 -2px @accent_color; }
.queue-current { color: @accent_color; }
.device-row { padding: 6px 8px; border-radius: 6px; }
.device-row.off { opacity: 0.45; }
.live-badge { background-color: #e5322d; color: white; font-weight: 800; font-size: 11px; border-radius: 4px; padding: 1px 8px; }
.follow-line { font-weight: 700; opacity: 0.45; }
.follow-line.follow-current { opacity: 1; }
.follow-small .follow-line { font-size: 16px; }
.follow-small .follow-current { color: @accent_color; }
.follow-big .follow-line { font-size: 30px; }
.follow-plain { font-size: 16px; line-height: 1.5; }
.follow-big .follow-plain { font-size: 22px; }
.full-view { background-color: #1d1d1d; color: white; }
.full-shade { background-image: linear-gradient(to bottom, alpha(black, 0.2), alpha(black, 0.8)); }
.full-title { font-size: 44px; font-weight: 700; }
.full-artist { font-size: 22px; opacity: 0.75; }
.full-view button.flat { color: white; }
.media-switch { background-color: alpha(black, 0.6); color: white; }
.media-switch > toggle:checked { background-color: white; color: black; }
";

/// 窓の中身（上の帯を含む）を作る。
#[allow(clippy::too_many_arguments)]
pub fn build(
    repo: MusicRepository,
    lyrics: wave_core::lyrics::service::LyricsService,
    playback: Rc<Playback>,
    video: Option<crate::video::VideoPlayer>,
    downloads: Rc<crate::downloads::Downloads>,
    connect: Rc<crate::connect::Connect>,
    releases: Rc<crate::releases::Releases>,
    podcasts: Rc<crate::podcasts::Podcasts>,
    // ログアウトは設定のアカウントのカード（crate::log_out）から
    _on_logout: Rc<dyn Fn()>,
) -> (gtk::Widget, Rc<App>) {
    let css = gtk::CssProvider::new();
    css.load_from_string(STYLE);
    gtk::style_context_add_provider_for_display(&gtk::gdk::Display::default().expect("display"), &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);

    let nav = adw::NavigationView::new();
    let toasts = adw::ToastOverlay::new();
    let right_open = downloads.settings.borrow().right_pane_open;
    let volume = playback.player().volume();
    let app = Rc::new(App {
        repo,
        lyrics,
        playback,
        nav: nav.clone(),
        toasts: toasts.clone(),
        video,
        downloads,
        connect,
        releases,
        podcasts,
        ai: Default::default(),
        editable_playlists: Default::default(),
        view: Cell::new(View { right_open, ..Default::default() }),
        parts: Default::default(),
        view_listeners: Default::default(),
        mv: Default::default(),
        mv_for: Default::default(),
        video_load: Default::default(),
        video_gap: Cell::new(true),
        forward: Default::default(),
        going_forward: Default::default(),
        volume_before_mute: Cell::new(if volume > 0.0 { volume } else { 0.7 }),
    });
    {
        // 再生の知らせ（飛ばした・止めた理由）を画面の下に出す
        let weak = Rc::downgrade(&app);
        app.playback.on_notice(move |text| {
            if let Some(app) = weak.upgrade() {
                app.toast(text);
            }
        });
    }
    {
        let app = app.clone();
        gtk::glib::spawn_future_local(async move {
            app.ai.load().await;
            // 確かめ用: WAVE_DEMO_AI_ASK の頼みを、曲が流れ始めるのを待ってから送る
            if let Ok(ask) = std::env::var("WAVE_DEMO_AI_ASK") {
                gtk::glib::timeout_future_seconds(6).await;
                app.ai.send(&app, &ask);
            }
        });
    }
    {
        // 端末の連携の知らせはトーストで出す
        let weak = Rc::downgrade(&app);
        app.connect.on_notice(move |text| {
            if let Some(app) = weak.upgrade() {
                app.toast(text);
            }
        });
    }
    nav.add(&pages::home(&app));
    {
        // 戻ったページは「進む」で開き直せるように覚える
        let weak = Rc::downgrade(&app);
        nav.connect_popped(move |_, page| {
            if let Some(app) = weak.upgrade() {
                let mut forward = app.forward.borrow_mut();
                forward.push(page.clone());
                if forward.len() > 30 {
                    forward.remove(0);
                }
            }
        });
    }

    let toolbar = adw::ToolbarView::new();
    let bar = header(&app);
    bar.pack_end(&account_button(&app));
    toolbar.add_top_bar(&bar);
    let body = gtk::Box::builder().spacing(8).margin_start(8).margin_end(8).build();
    body.append(&pane(&super::library::sidebar(&app), Some(300)));
    let center = pane(&nav, None);
    center.set_hexpand(true);
    body.append(&center);
    // 右の欄は再生中・キュー・Claude を切り替える（Mac と同じ）
    let side_stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).build();
    side_stack.add_named(&now_playing(&app), Some("now"));
    side_stack.add_named(&queue(&app), Some("queue"));
    side_stack.add_named(&super::ai::panel(&app, {
        // Claude の欄の × は、開く前の右の欄に戻す
        let weak = Rc::downgrade(&app);
        move || {
            if let Some(app) = weak.upgrade() {
                app.close_ai();
            }
        }
    }), Some("ai"));
    // 中身ごとに幅を変えず、いつも同じ幅にする（Claude の長い文に合わせて広がらないように）
    side_stack.set_hhomogeneous(false);
    let side_width = adw::Clamp::builder().maximum_size(SIDE_WIDTH).tightening_threshold(SIDE_WIDTH).child(&side_stack).build();
    let side = pane(&side_width, Some(SIDE_WIDTH));
    body.append(&side);
    // 全画面の再生中の画面は、上の帯とページの段を覆う（下の帯は出したまま）
    let content = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).build();
    content.add_named(&body, Some("main"));
    content.add_named(&super::full_view::build(&app), Some("full"));
    toasts.set_child(Some(&content));
    toolbar.set_content(Some(&toasts));
    toolbar.add_bottom_bar(&player_bar(&app));
    // 映像だけを窓いっぱいに出す層（下の帯も覆う）
    let root = gtk::Overlay::builder().child(&toolbar).build();
    let layer = video_layer(&app);
    if let Some(layer) = &layer {
        root.add_overlay(layer);
    }
    {
        let mut parts = app.parts.borrow_mut();
        parts.toolbar = Some(toolbar.clone());
        parts.content = Some(content.clone());
        parts.side = Some(side.clone());
        parts.side_stack = Some(side_stack.clone());
        parts.video_layer = layer;
    }
    watch_video(&app);
    install_keys(&app, root.upcast_ref());

    // 窓の幅の段（右の欄・下の帯の端末名と音量のバーを、狭いときに隠す）
    let bin = adw::BreakpointBin::builder().child(&root).width_request(720).height_request(480).build();
    let breakpoints: Vec<adw::Breakpoint> = [
        "min-width: 1400px",
        "min-width: 1180px and max-width: 1399px",
        "min-width: 1000px and max-width: 1179px",
        "min-width: 880px and max-width: 999px",
        "max-width: 879px",
    ]
    .iter()
    .map(|condition| {
        let breakpoint = adw::Breakpoint::new(adw::BreakpointCondition::parse(condition).expect("breakpoint condition"));
        bin.add_breakpoint(breakpoint.clone());
        breakpoint
    })
    .collect();
    {
        let weak = Rc::downgrade(&app);
        bin.connect_current_breakpoint_notify(move |bin| {
            let Some(app) = weak.upgrade() else { return };
            let level = bin.current_breakpoint().and_then(|c| breakpoints.iter().position(|b| *b == c)).unwrap_or(0) as u8;
            if app.width_level() != level {
                app.update_view(|v| v.width_level = level);
            }
        });
    }
    app.view_changed();

    // 確かめ用: 起動したら右の欄をキュー（WAVE_DEMO_SIDE=queue）・履歴（=history）・Claude（=ai）にする
    match std::env::var("WAVE_DEMO_SIDE").as_deref() {
        Ok("queue") => app.show_queue(false),
        Ok("history") => app.show_queue(true),
        Ok("ai") => app.show_ai(),
        _ => {}
    }
    // 確かめ用: WAVE_DEMO_FULL=1 で全画面の再生中の画面、=video で映像だけ（WAVE_DEMO_VIDEO と一緒に）、=pip で映像の小窓を出す
    if let Ok(mode) = std::env::var("WAVE_DEMO_FULL") {
        let app = app.clone();
        gtk::glib::timeout_add_local_once(std::time::Duration::from_secs(if mode == "1" { 2 } else { 7 }), move || match mode.as_str() {
            "video" => app.expand_video(),
            "pip" => app.open_pip(),
            _ => app.set_full_screen(true),
        });
    }
    (bin.upcast(), app)
}

/// 今の曲の MV を探し、動画にしていれば読み込む。曲が変わったら「曲」の表示に戻す（小窓を出している間は動画のまま）。
/// 動画の間は、映像を音の位置に合わせ続ける。
fn watch_video(app: &Rc<App>) {
    {
        let weak = Rc::downgrade(app);
        app.playback.on_change(move || {
            let Some(app) = weak.upgrade() else { return };
            let id = app.playback.current().map(|s| s.video_id);
            if *app.mv_for.borrow() == id {
                return;
            }
            *app.mv_for.borrow_mut() = id.clone();
            *app.mv.borrow_mut() = None;
            app.stop_video();
            if !app.pip_open() {
                let mut view = app.view.get();
                view.show_video = false;
                app.view.set(view);
            }
            app.view_changed();
            let Some(id) = id else { return };
            if app.video.is_none() {
                return;
            }
            gtk::glib::spawn_future_local(async move {
                let (repo, wanted) = (app.repo.clone(), id.clone());
                let found = runtime::run(async move { repo.music_video(&wanted).await }).await.ok().flatten();
                if app.mv_for.borrow().as_deref() != Some(id.as_str()) {
                    return;
                }
                let has = found.is_some();
                *app.mv.borrow_mut() = found;
                // 確かめ用: WAVE_DEMO_VIDEO があれば動画にする
                if has && std::env::var_os("WAVE_DEMO_VIDEO").is_some() && !app.show_video() {
                    app.set_show_video(true);
                } else {
                    app.ensure_video();
                    app.view_changed();
                }
            });
        });
    }
    let weak = Rc::downgrade(app);
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(300), move || {
        let Some(app) = weak.upgrade() else { return gtk::glib::ControlFlow::Break };
        if !(app.show_video() && app.has_video()) || app.video_load.get() != Load::Ready {
            return gtk::glib::ControlFlow::Continue;
        }
        let (Some(video), Some(mv)) = (app.video.as_ref(), app.mv.borrow().clone()) else { return gtk::glib::ControlFlow::Continue };
        let player = app.playback.player();
        let showing = video.sync(&mv, player.position_ms().unwrap_or(0), player.is_playing());
        if app.video_gap.get() == showing {
            app.video_gap.set(!showing);
            app.view_changed();
        }
        gtk::glib::ControlFlow::Continue
    });
}

/// 窓のキーボードの近道（Mac と同じ）: Space で再生・一時停止（文字の入力中は除く）、Ctrl+← / Ctrl+→ で前・次の曲、
/// Ctrl+K・Ctrl+L で検索の欄、Ctrl+J で Claude、F11 で全画面、Esc で全画面・映像だけを終える。
/// 窓が閉じるときは映像の小窓も閉じる。
fn install_keys(app: &Rc<App>, root: &gtk::Widget) {
    let keys = gtk::EventControllerKey::new();
    keys.set_propagation_phase(gtk::PropagationPhase::Capture);
    {
        let weak = Rc::downgrade(app);
        keys.connect_key_pressed(move |controller, key, _, state| {
            use gtk::gdk::Key;
            let Some(app) = weak.upgrade() else { return gtk::glib::Propagation::Proceed };
            let ctrl = state.contains(gtk::gdk::ModifierType::CONTROL_MASK);
            let alt = state.contains(gtk::gdk::ModifierType::ALT_MASK);
            let typing = controller
                .widget()
                .and_downcast::<gtk::Window>()
                .and_then(|w| gtk::prelude::GtkWindowExt::focus(&w))
                .is_some_and(|f| f.is::<gtk::Editable>() || f.is::<gtk::TextView>());
            let handled = match key.to_lower() {
                Key::space if !ctrl && !alt && !typing => {
                    app.playback.toggle();
                    true
                }
                Key::Right if ctrl && !typing => {
                    app.playback.next();
                    true
                }
                Key::Left if ctrl && !typing => {
                    app.playback.previous();
                    true
                }
                Key::F11 => {
                    app.set_full_screen(!app.is_full());
                    true
                }
                Key::Escape if app.is_full() && !typing => {
                    // 映像だけのときは、まず全画面の再生中の画面に戻す（「拡大」から開いたなら元の画面へ）
                    if app.view.get().video_only { app.toggle_video_only() } else { app.set_full_screen(false) }
                    true
                }
                Key::k | Key::l if ctrl => {
                    let search = app.parts.borrow().search.clone();
                    app.open_from_full(|| {
                        if let Some(search) = search {
                            search.grab_focus();
                        }
                    });
                    true
                }
                Key::j if ctrl => {
                    app.toggle_ai();
                    true
                }
                _ => false,
            };
            if handled { gtk::glib::Propagation::Stop } else { gtk::glib::Propagation::Proceed }
        });
    }
    // 層そのものは焦点を持たないので、窓で受ける。ログアウトで中身が替わったら外す
    let handlers: Rc<RefCell<Vec<gtk::glib::SignalHandlerId>>> = Rc::default();
    {
        let (keys, handlers, weak) = (keys.clone(), handlers.clone(), Rc::downgrade(app));
        root.connect_realize(move |root| {
            let Some(window) = root.root().and_downcast::<gtk::Window>() else { return };
            window.add_controller(keys.clone());
            let w = weak.clone();
            // 窓の全画面が変わったら、映像の右上のボタンの絵を合わせる
            handlers.borrow_mut().push(window.connect_fullscreened_notify(move |_| {
                if let Some(app) = w.upgrade() {
                    app.view_changed();
                }
            }));
            let w = weak.clone();
            handlers.borrow_mut().push(window.connect_close_request(move |_| {
                if let Some(app) = w.upgrade() {
                    app.close_pip();
                }
                gtk::glib::Propagation::Proceed
            }));
        });
    }
    root.connect_unrealize(move |root| {
        let Some(window) = root.root().and_downcast::<gtk::Window>() else { return };
        window.remove_controller(&keys);
        for handler in handlers.borrow_mut().drain(..) {
            window.disconnect(handler);
        }
    });
}

/// 映像だけを窓いっぱいに出す層。右上に小窓・全画面（窓を全画面にする・戻す）・×（映像だけをやめる）。Esc でも戻る。ダブルクリックで全画面。
fn video_layer(app: &Rc<App>) -> Option<gtk::Widget> {
    app.video.as_ref()?;
    let media = video_or_cover(app, Place::Layer);
    let pip = pip_button(app);
    let fullscreen = gtk::Button::builder().icon_name("view-fullscreen-symbolic").tooltip_text("全画面").css_classes(["osd", "circular"]).build();
    let close = gtk::Button::builder().icon_name("window-close-symbolic").tooltip_text("映像だけをやめる (Esc)").css_classes(["osd", "circular"]).build();
    let buttons = gtk::Box::builder().spacing(8).halign(gtk::Align::End).valign(gtk::Align::Start).margin_end(16).margin_top(16).build();
    buttons.append(&pip);
    buttons.append(&fullscreen);
    buttons.append(&close);
    let layer = gtk::Overlay::builder().child(&media).visible(false).css_classes(["video-layer"]).build();
    layer.add_overlay(&buttons);
    let toggle_fullscreen = {
        let app = Rc::downgrade(app);
        move || {
            if let Some(window) = app.upgrade().and_then(|a| a.window()) {
                if window.is_fullscreen() { window.unfullscreen() } else { window.fullscreen() }
            }
        }
    };
    let t = toggle_fullscreen.clone();
    fullscreen.connect_clicked(move |_| t());
    let double = gtk::GestureClick::new();
    double.connect_pressed(move |_, n, _, _| {
        if n == 2 {
            toggle_fullscreen();
        }
    });
    media.add_controller(double);
    {
        let app = app.clone();
        close.connect_clicked(move |_| app.toggle_video_only());
    }
    {
        let weak = Rc::downgrade(app);
        app.on_view(move || {
            let Some(app) = weak.upgrade() else { return };
            let on = app.window().is_some_and(|w| w.is_fullscreen());
            fullscreen.set_icon_name(if on { "view-restore-symbolic" } else { "view-fullscreen-symbolic" });
            fullscreen.set_tooltip_text(Some(if on { "全画面をやめる" } else { "全画面" }));
        });
    }
    Some(layer.upcast())
}

/// 映像の小窓: 小さな別の窓に映像（無い曲はジャケット）を出し、指を乗せたときだけ前・再生・次と「wave に戻す」を出す（Mac と同じ）。
/// ダブルクリックでも wave に戻す。閉じても音は続ける。常に手前にするのは GNOME の窓のメニューで（Wayland ではアプリから手前に固定できない）。
fn mini_window(app: &Rc<App>) -> gtk::Window {
    let media = video_or_cover(app, Place::Pip);
    let previous = gtk::Button::builder().icon_name("media-skip-backward-symbolic").tooltip_text("前へ").css_classes(["osd", "circular"]).build();
    let play = gtk::Button::builder().icon_name("media-playback-pause-symbolic").tooltip_text("再生・一時停止").css_classes(["osd", "circular"]).build();
    let next = gtk::Button::builder().icon_name("media-skip-forward-symbolic").tooltip_text("次へ").css_classes(["osd", "circular"]).build();
    let back = gtk::Button::builder().icon_name("go-up-symbolic").tooltip_text("wave に戻して大きく見る").halign(gtk::Align::End).valign(gtk::Align::Start).margin_top(6).margin_end(6).visible(false).css_classes(["osd", "circular"]).build();
    let controls = gtk::Box::builder().spacing(18).halign(gtk::Align::Center).valign(gtk::Align::Center).visible(false).build();
    for b in [&previous, &play, &next] {
        controls.append(b);
    }
    let overlay = gtk::Overlay::builder().child(&media).css_classes(["video-layer"]).build();
    overlay.add_overlay(&controls);
    overlay.add_overlay(&back);
    let window = gtk::Window::builder().title("wave の小窓").default_width(400).default_height(225).child(&overlay).build();
    // 指を乗せたときだけ操作を出す
    let motion = gtk::EventControllerMotion::new();
    let (c, b) = (controls.clone(), back.clone());
    motion.connect_enter(move |_, _, _| {
        c.set_visible(true);
        b.set_visible(true);
    });
    let (c, b) = (controls.clone(), back.clone());
    motion.connect_leave(move |_| {
        c.set_visible(false);
        b.set_visible(false);
    });
    overlay.add_controller(motion);
    let playback = app.playback.clone();
    previous.connect_clicked(move |_| playback.previous());
    let playback = app.playback.clone();
    play.connect_clicked(move |_| playback.toggle());
    let playback = app.playback.clone();
    next.connect_clicked(move |_| playback.next());
    {
        let app = app.clone();
        back.connect_clicked(move |_| app.pip_back_to_app());
    }
    let double = gtk::GestureClick::new();
    {
        let app = app.clone();
        double.connect_pressed(move |_, n, _, _| {
            if n == 2 {
                app.pip_back_to_app();
            }
        });
    }
    media.add_controller(double);
    {
        let weak = Rc::downgrade(app);
        window.connect_close_request(move |_| {
            if let Some(app) = weak.upgrade() {
                app.parts.borrow_mut().pip.take();
                app.update_view(|v| v.pip_open = false);
            }
            gtk::glib::Propagation::Proceed
        });
    }
    let (playback, p) = (app.playback.clone(), play.downgrade());
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(500), move || {
        let Some(p) = p.upgrade() else { return gtk::glib::ControlFlow::Break };
        let playing = playback.player().is_playing();
        p.set_icon_name(if playing { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" });
        p.set_tooltip_text(Some(if playing { "一時停止" } else { "再生" }));
        gtk::glib::ControlFlow::Continue
    });
    window
}

/// 映像の小窓を出す・閉じるボタン（絵も替える）。
fn pip_button(app: &Rc<App>) -> gtk::Button {
    let button = gtk::Button::builder().icon_name("view-restore-symbolic").tooltip_text("小窓で見る").css_classes(["osd", "circular"]).build();
    {
        let app = app.clone();
        button.connect_clicked(move |_| app.toggle_pip());
    }
    let (weak, b) = (Rc::downgrade(app), button.clone());
    app.on_view(move || {
        let Some(app) = weak.upgrade() else { return };
        let open = app.pip_open();
        b.set_icon_name(if open { "view-conceal-symbolic" } else { "view-restore-symbolic" });
        b.set_tooltip_text(Some(if open { "小窓を閉じる" } else { "小窓で見る" }));
    });
    button
}

/// ジャケットか映像を出す場所。
#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum Place {
    /// 右の欄（正方形）。右下に小窓と「映像を大きく見る」。
    Side,
    /// 全画面の再生中の画面（ジャケットは左下に寄せた正方形、映像は枠いっぱい）。右下に小窓と「映像だけ」。
    Full,
    /// 映像だけの層。
    Layer,
    /// 映像の小窓。
    Pip,
}

/// ジャケットか、動画を選んでいれば MV（音の無い映像を音に合わせる。Mac の VideoOrCover）。
/// 読めないとき・映像の無い区間は、映像の枠に説明の文を出す（読めないときは「もう一度」も）。
pub(super) fn video_or_cover(app: &Rc<App>, place: Place) -> gtk::Widget {
    let stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).hexpand(place != Place::Side).vexpand(place != Place::Side).build();
    let load_art: Box<dyn Fn(Option<&str>)> = match place {
        Place::Side => {
            let art = images::square(SIDE_ART);
            stack.add_named(&art.root, Some("art"));
            Box::new(move |url| art.load(url, SIDE_ART as u32 * 2))
        }
        Place::Full => {
            let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).css_classes(["artwork"]).build();
            picture.set_overflow(gtk::Overflow::Hidden);
            let frame = gtk::AspectFrame::builder().ratio(1.0).obey_child(false).xalign(0.0).yalign(1.0).child(&picture).build();
            stack.add_named(&frame, Some("art"));
            Box::new(move |url| images::load(&picture, url, 1200))
        }
        Place::Layer | Place::Pip => {
            let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Contain).can_shrink(true).build();
            stack.add_named(&picture, Some("art"));
            Box::new(move |url| images::load(&picture, url, 1200))
        }
    };
    let note = gtk::Label::builder().wrap(true).justify(gtk::Justification::Center).css_classes(["caption", "video-note"]).build();
    let retry = gtk::Button::builder().label("もう一度").halign(gtk::Align::Center).css_classes(["pill", "osd"]).build();
    if let Some(video) = app.video.as_ref() {
        let picture = gtk::Picture::builder().paintable(video.paintable()).content_fit(gtk::ContentFit::Contain).can_shrink(true).hexpand(true).vexpand(true).build();
        if place == Place::Side {
            picture.set_size_request(SIDE_ART, SIDE_ART);
        }
        let notes = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).halign(gtk::Align::Center).valign(gtk::Align::Center).margin_start(16).margin_end(16).build();
        notes.append(&note);
        notes.append(&retry);
        let video_box = gtk::Overlay::builder().child(&picture).css_classes(["video-frame"]).build();
        if place == Place::Side {
            video_box.add_css_class("side");
            video_box.set_halign(gtk::Align::Start);
        }
        video_box.set_overflow(gtk::Overflow::Hidden);
        video_box.add_overlay(&notes);
        {
            let app = app.clone();
            retry.connect_clicked(move |_| app.retry_video());
        }
        if matches!(place, Place::Side | Place::Full) {
            let pip = pip_button(app);
            let (icon, tip) = if place == Place::Side { ("view-fullscreen-symbolic", "映像を大きく見る") } else { ("view-fullscreen-symbolic", "映像だけを窓いっぱいに") };
            let expand = gtk::Button::builder().icon_name(icon).tooltip_text(tip).css_classes(["osd", "circular"]).build();
            let corner = gtk::Box::builder().spacing(6).halign(gtk::Align::End).valign(gtk::Align::End).margin_end(8).margin_bottom(8).build();
            corner.append(&pip);
            corner.append(&expand);
            video_box.add_overlay(&corner);
            let app = app.clone();
            if place == Place::Side {
                expand.connect_clicked(move |_| app.expand_video());
            } else {
                let a = app.clone();
                expand.connect_clicked(move |_| a.toggle_video_only());
                // 映像のダブルクリックでも映像だけにする
                let double = gtk::GestureClick::new();
                double.connect_pressed(move |_, n, _, _| {
                    if n == 2 {
                        app.toggle_video_only();
                    }
                });
                picture.add_controller(double);
            }
        }
        stack.add_named(&video_box, Some("video"));
    }
    let shown_url: Rc<RefCell<Option<Option<String>>>> = Rc::default();
    let sync = {
        let (weak, stack) = (Rc::downgrade(app), stack.clone());
        move || {
            let Some(app) = weak.upgrade() else { return };
            let url = app.shown_song().and_then(|s| s.thumbnail_url);
            if shown_url.borrow().as_ref() != Some(&url) {
                load_art(url.as_deref());
                *shown_url.borrow_mut() = Some(url);
            }
            let state = app.video_state();
            stack.set_visible_child_name(if state == VideoState::Off { "art" } else { "video" });
            note.set_visible(state != VideoState::Showing);
            note.set_label(match state {
                VideoState::Loading => "動画を読み込んでいます…",
                VideoState::Failed => "動画を読み込めませんでした",
                VideoState::Gap => "この部分には動画がありません",
                _ => "",
            });
            retry.set_visible(state == VideoState::Failed);
        }
    };
    let sync = Rc::new(sync);
    sync();
    let s = sync.clone();
    app.on_view(move || s());
    let s = sync.clone();
    app.playback.on_change(move || s());
    app.connect.on_change(move || sync());
    stack.upcast()
}

/// 「曲｜動画」の切り替え（MV のある曲だけ出す）。
pub(super) fn song_video_switch(app: &Rc<App>) -> adw::ToggleGroup {
    let switch = adw::ToggleGroup::builder().visible(false).css_classes(["round", "media-switch"]).build();
    switch.add(adw::Toggle::builder().label("曲").name("song").build());
    switch.add(adw::Toggle::builder().label("動画").name("video").build());
    switch.set_active_name(Some("song"));
    let syncing = Rc::new(Cell::new(false));
    {
        let (app, syncing) = (app.clone(), syncing.clone());
        switch.connect_active_name_notify(move |switch| {
            if !syncing.get() {
                app.set_show_video(switch.active_name().as_deref() == Some("video"));
            }
        });
    }
    let sync = {
        let (weak, switch) = (Rc::downgrade(app), switch.clone());
        Rc::new(move || {
            let Some(app) = weak.upgrade() else { return };
            switch.set_visible(app.has_video());
            let name = if app.show_video() { "video" } else { "song" };
            if switch.active_name().as_deref() != Some(name) {
                syncing.set(true);
                switch.set_active_name(Some(name));
                syncing.set(false);
            }
        })
    };
    sync();
    let s = sync.clone();
    app.on_view(move || s());
    let s = sync.clone();
    app.playback.on_change(move || s());
    app.connect.on_change(move || sync());
    switch
}

/// 曲名の文字を押すとアルバムへ（全画面からは、全画面を閉じてから）。
pub(super) fn album_link(app: &Rc<App>, label: &gtk::Label) {
    let click = gtk::GestureClick::new();
    let app = app.clone();
    click.connect_released(move |_, _, _, _| {
        if let Some((id, title)) = app.shown_song().and_then(|s| s.album).and_then(|a| Some((a.browse_id?, a.title))) {
            app.open_from_full(|| app.push(pages::album(&app, &id, &title)));
        }
    });
    label.add_controller(click);
    label.set_cursor_from_name(Some("pointer"));
}

/// アーティストの文字を押すとそのページへ。複数なら選ぶメニューを出す（Mac と同じ）。
pub(super) fn artist_link(app: &Rc<App>, label: &gtk::Label) {
    let click = gtk::GestureClick::new();
    let (app, weak_label) = (app.clone(), label.downgrade());
    click.connect_released(move |_, _, _, _| {
        let artists: Vec<(String, String)> = app.shown_song().map(|s| s.artists.into_iter().filter_map(|a| Some((a.browse_id?, a.name))).collect()).unwrap_or_default();
        match artists.as_slice() {
            [] => {}
            [(id, name)] => app.open_from_full(|| app.push(pages::artist(&app, id, name))),
            _ => {
                let Some(label) = weak_label.upgrade() else { return };
                let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).build();
                let popover = gtk::Popover::builder().child(&column).build();
                for (id, name) in artists {
                    let content = gtk::Box::builder().spacing(8).build();
                    content.append(&gtk::Image::from_icon_name("avatar-default-symbolic"));
                    content.append(&gtk::Label::new(Some(&name)));
                    let button = gtk::Button::builder().child(&content).css_classes(["flat"]).build();
                    let (app, popover) = (app.clone(), popover.clone());
                    button.connect_clicked(move |_| {
                        popover.popdown();
                        app.open_from_full(|| app.push(pages::artist(&app, &id, &name)));
                    });
                    column.append(&button);
                }
                popover.set_parent(&label);
                popover.connect_closed(|p| {
                    let p = p.clone();
                    gtk::glib::idle_add_local_once(move || p.unparent());
                });
                popover.popup();
            }
        }
    });
    label.add_controller(click);
    label.set_cursor_from_name(Some("pointer"));
}

impl App {
    /// 確かめ用（WAVE_DEMO_QUERY）: その語で検索のページを開き、1曲目から流す。
    /// WAVE_DEMO_SIDE=lyrics なら歌詞のページも開く。WAVE_DEMO_PAGE が artist・album なら、続けてその曲のアーティスト・アルバムのページを開く。
    pub fn demo(self: &Rc<Self>, query: &str) {
        self.push(pages::search(self, query));
        let (app, query) = (self.clone(), query.to_string());
        gtk::glib::spawn_future_local(async move {
            let repo = app.repo.clone();
            if let Ok(page) = runtime::run(async move { repo.search_songs(&query, None).await }).await {
                let first = page.songs.first().cloned();
                let mut songs = page.songs;
                // 確かめ用: WAVE_DEMO_UNPLAYABLE=N なら、鳴らせない曲を N 曲先頭に入れる（飛ばす・続けば止める）
                if let Some(n) = std::env::var("WAVE_DEMO_UNPLAYABLE").ok().and_then(|n| n.parse::<usize>().ok())
                    && let Some(template) = songs.first().cloned()
                {
                    for i in 0..n {
                        songs.insert(0, wave_core::music::models::Song { video_id: format!("zzzzzzzzzz{i}"), title: format!("鳴らせない曲 {i}"), ..template.clone() });
                    }
                }
                app.playback.play_list(songs, 0);
                // 確かめ用: WAVE_DEMO_QUEUE_ADD=N なら、3曲目から N 曲を「次に再生」で足す（キューの「キューの次の曲」の見出し）
                if let Some(n) = std::env::var("WAVE_DEMO_QUEUE_ADD").ok().and_then(|n| n.parse::<usize>().ok()) {
                    let (items, _) = app.playback.queue();
                    for song in items.into_iter().skip(2).take(n).rev() {
                        app.playback.play_next(song);
                    }
                }
                // 確かめ用: WAVE_DEMO_DOWNLOAD があれば1曲目を取り、ダウンロードのページを開く
                if std::env::var("WAVE_DEMO_DOWNLOAD").is_ok()
                    && let Some(song) = first.clone()
                {
                    app.downloads.download_song(&song);
                    app.push(pages::downloads(&app));
                }
                if std::env::var("WAVE_DEMO_SIDE").as_deref() == Ok("lyrics") {
                    app.push(super::lyrics::page(&app));
                }
                match (std::env::var("WAVE_DEMO_PAGE").as_deref(), first) {
                    (Ok("artist"), Some(song)) => {
                        if let Some((id, name)) = song.artists.into_iter().find_map(|a| Some((a.browse_id?, a.name))) {
                            app.push(pages::artist(&app, &id, &name));
                        }
                    }
                    (Ok("album"), Some(song)) => {
                        if let Some((id, title)) = song.album.and_then(|a| Some((a.browse_id?, a.title))) {
                            app.push(pages::album(&app, &id, &title));
                        }
                    }
                    (Ok("settings"), _) => app.push(super::settings::page(&app)),
                    _ => {}
                }
            }
        });
    }
}

fn pane(child: &impl IsA<gtk::Widget>, width: Option<i32>) -> gtk::Widget {
    let frame = adw::Bin::builder().child(child).css_classes(["pane"]).build();
    frame.set_overflow(gtk::Overflow::Hidden);
    if let Some(width) = width {
        frame.set_width_request(width);
        // 中の部品（入力の欄など）が横に広がりたがっても、欄の幅は変えない（明示しないと子の hexpand が伝わる）
        frame.set_hexpand(false);
    }
    frame.upcast()
}

/// 上の帯: 左に戻る・進む、中央（窓の左右の中央）にホーム・検索の欄・Claude（Mac と同じ）。
/// 戻れるときは戻るだけ、進めるときは2つとも出す（戻れなければ戻るは薄く）。
fn header(app: &Rc<App>) -> adw::HeaderBar {
    let back = gtk::Button::builder().icon_name("go-previous-symbolic").tooltip_text("戻る").visible(false).build();
    let forward = gtk::Button::builder().icon_name("go-next-symbolic").tooltip_text("進む").visible(false).build();
    let home = gtk::Button::builder().icon_name("go-home-symbolic").tooltip_text("ホーム").css_classes(["circular"]).build();
    let search = gtk::SearchEntry::builder().placeholder_text("何を聴きたいですか？").width_request(440).build();
    let center = gtk::Box::builder().spacing(8).build();
    center.append(&home);
    center.append(&search);
    // Claude は検索の欄の右（Mac と同じ）。右の欄に出し、もう一度押すと開く前に戻す
    let claude = gtk::ToggleButton::builder().icon_name("chat-message-new-symbolic").tooltip_text("Claude に聞く (Ctrl+J)").css_classes(["circular", "flat"]).build();
    center.append(&claude);
    {
        let app = app.clone();
        claude.connect_clicked(move |_| app.toggle_ai());
    }
    {
        let (weak, claude) = (Rc::downgrade(app), claude.clone());
        app.on_view(move || {
            let Some(app) = weak.upgrade() else { return };
            let on = app.show_right() && app.right_view() == RightView::Ai;
            if claude.is_active() != on {
                claude.set_active(on);
            }
        });
    }
    app.parts.borrow_mut().search = Some(search.clone());
    let bar = adw::HeaderBar::builder().title_widget(&center).build();
    bar.pack_start(&back);
    bar.pack_start(&forward);

    {
        let app = app.clone();
        back.connect_clicked(move |_| app.go_back());
    }
    {
        let app = app.clone();
        forward.connect_clicked(move |_| app.go_forward());
    }
    let nav = app.nav.clone();
    home.connect_clicked(move |_| {
        nav.pop_to_tag("home");
    });
    let update = {
        let (weak, back, forward) = (Rc::downgrade(app), back.clone(), forward.clone());
        Rc::new(move || {
            let Some(app) = weak.upgrade() else { return };
            let can_back = app.nav.navigation_stack().n_items() > 1;
            let can_forward = !app.forward.borrow().is_empty();
            back.set_visible(can_back || can_forward);
            back.set_sensitive(can_back);
            forward.set_visible(can_forward);
        })
    };
    let u = update.clone();
    app.nav.connect_visible_page_notify(move |_| u());
    app.nav.connect_popped(move |_, _| update());
    let suggestions = suggestions_popover(app, &search);
    // 確かめ用: WAVE_DEMO_FOCUS_SEARCH があれば、開いて少ししたら検索の欄に入る（検索の履歴が出る）
    if std::env::var_os("WAVE_DEMO_FOCUS_SEARCH").is_some() {
        let search = search.clone();
        gtk::glib::timeout_add_local_once(std::time::Duration::from_secs(3), move || {
            search.grab_focus();
        });
    }
    let app = app.clone();
    search.connect_activate(move |entry| {
        suggestions.popdown();
        let query = entry.text().trim().to_string();
        if query.is_empty() {
            return;
        }
        // 検索のページは1枚だけ積む（打ち直したら置き換える）
        if app.visible_kind().as_deref() == Some("search") {
            app.nav.pop();
        }
        app.push(pages::search(&app, &query));
    });
    bar
}

/// 検索の履歴の行（画像付き。押すと開く・右の × で消す・最後に「すべて消す」）。
fn history_rows(app: &Rc<App>, popover: &gtk::Popover, list: &gtk::ListBox, entry: &gtk::SearchEntry) {
    while let Some(row) = list.first_child() {
        list.remove(&row);
    }
    let history = app.downloads.settings.borrow().search_history.clone();
    if history.is_empty() {
        popover.popdown();
        return;
    }
    list.append(&gtk::Label::builder().label("最近の検索").xalign(0.0).margin_start(12).margin_top(6).css_classes(["heading"]).build());
    for item in history.into_iter().take(10) {
        let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&item.title)).subtitle(gtk::glib::markup_escape_text(item.subtitle.as_deref().unwrap_or(""))).activatable(true).build();
        if item.kind == "query" {
            row.add_prefix(&gtk::Image::from_icon_name("document-open-recent-symbolic"));
        } else {
            let art = images::square(36);
            if item.kind == "artist" {
                art.add_css_class("round");
            }
            art.load(item.thumbnail_url.as_deref(), 72);
            row.add_prefix(&art.root);
        }
        let remove = gtk::Button::builder().icon_name("window-close-symbolic").tooltip_text("履歴から消す").valign(gtk::Align::Center).css_classes(["flat", "circular"]).build();
        row.add_suffix(&remove);
        {
            let (app, key, popover, list, entry) = (app.clone(), item.key(), popover.clone(), list.clone(), entry.clone());
            remove.connect_clicked(move |_| {
                app.downloads.update_settings(|s| s.search_history.retain(|e| e.key() != key));
                history_rows(&app, &popover, &list, &entry);
            });
        }
        let (app, popover, entry) = (app.clone(), popover.clone(), entry.clone());
        row.connect_activated(move |_| {
            popover.popdown();
            app.remember_search(item.clone());
            match (item.kind.as_str(), &item.id, &item.song) {
                ("album", Some(id), _) => app.push(pages::album(&app, id, &item.title)),
                ("artist", Some(id), _) => app.push(pages::artist(&app, id, &item.title)),
                ("song", _, Some(song)) => app.playback.play_list(vec![song.clone()], 0),
                _ => {
                    entry.set_text(&item.title);
                    entry.emit_activate();
                }
            }
        });
        list.append(&row);
    }
    let clear = gtk::Button::builder().label("すべて消す").halign(gtk::Align::End).margin_end(8).margin_bottom(6).css_classes(["flat"]).build();
    {
        let (app, popover) = (app.clone(), popover.clone());
        clear.connect_clicked(move |_| {
            app.downloads.update_settings(|s| s.search_history.clear());
            popover.popdown();
        });
    }
    list.append(&clear);
    popover.popup();
}

/// 入力の欄（中の文字の部品も含めて）に手が乗っているか。
fn focused(entry: &gtk::SearchEntry) -> bool {
    entry.root().and_then(|r| r.focus()).is_some_and(|f| f == *entry.upcast_ref::<gtk::Widget>() || f.is_ancestor(entry))
}

/// 打ち込み中の候補（語・アーティスト・アルバム・曲）。語を押すとその語で検索し、アーティスト・アルバムはそのページ、曲は鳴らす。
fn suggestions_popover(app: &Rc<App>, entry: &gtk::SearchEntry) -> gtk::Popover {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).width_request(440).css_classes(["navigation-sidebar"]).build();
    // 打ち続けられるよう、候補を出しても入力の欄から手を離させない
    let popover = gtk::Popover::builder().child(&list).autohide(false).has_arrow(false).position(gtk::PositionType::Bottom).can_focus(false).build();
    popover.set_parent(entry);
    let request = Rc::new(Cell::new(0u64));
    // 検索した語（打ち直すまで候補を出し直さない。候補を押して欄の文字を替えたときも）
    let searched: Rc<RefCell<String>> = Rc::default();
    {
        let (request, searched, popover) = (request.clone(), searched.clone(), popover.clone());
        let app = app.clone();
        entry.connect_activate(move |entry| {
            request.set(request.get() + 1);
            let text = entry.text().trim().to_string();
            if !text.is_empty() {
                app.remember_search(crate::settings::SearchHistoryEntry::query(&text));
            }
            *searched.borrow_mut() = text;
            popover.popdown();
        });
    }
    // 空のまま欄に入ったら、検索の履歴を出す
    let show_history: Rc<dyn Fn()> = {
        let (app, popover, list, entry) = (app.clone(), popover.clone(), list.clone(), entry.clone());
        Rc::new(move || history_rows(&app, &popover, &list, &entry))
    };
    let (app_c, popover_c, list_c, history_c) = (app.clone(), popover.clone(), list.clone(), show_history.clone());
    entry.connect_search_changed(move |entry| {
        let show_history = &history_c;
        let input = entry.text().trim().to_string();
        let id = request.get() + 1;
        request.set(id);
        if input.is_empty() && focused(entry) {
            show_history();
            return;
        }
        if input.is_empty() || !focused(entry) || *searched.borrow() == input {
            popover_c.popdown();
            return;
        }
        let (app, popover, list, request, entry) = (app_c.clone(), popover_c.clone(), list_c.clone(), request.clone(), entry.clone());
        gtk::glib::spawn_future_local(async move {
            let repo = app.repo.clone();
            let Ok(found) = runtime::run(async move { repo.suggestions(&input).await }).await else { return };
            // 打ち直した後に古い候補を出さない
            if request.get() != id || !focused(&entry) {
                return;
            }
            while let Some(row) = list.first_child() {
                list.remove(&row);
            }
            let close = |popover: &gtk::Popover, entry: &gtk::SearchEntry| {
                popover.popdown();
                entry.set_text("");
            };
            for query in found.queries.into_iter().take(6) {
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&query)).activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name("system-search-symbolic"));
                let (entry, popover) = (entry.clone(), popover.clone());
                row.connect_activated(move |_| {
                    popover.popdown();
                    entry.set_text(&query);
                    entry.emit_activate();
                });
                list.append(&row);
            }
            for artist in found.artists.into_iter().take(2) {
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&artist.name)).subtitle("アーティスト").activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name("avatar-default-symbolic"));
                let (app, entry, popover) = (app.clone(), entry.clone(), popover.clone());
                row.connect_activated(move |_| {
                    close(&popover, &entry);
                    app.remember_search(crate::settings::SearchHistoryEntry::artist(&artist.browse_id, &artist.name, artist.thumbnail_url.as_deref()));
                    app.push(pages::artist(&app, &artist.browse_id, &artist.name));
                });
                list.append(&row);
            }
            for album in found.albums.into_iter().take(2) {
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&album.title)).subtitle(gtk::glib::markup_escape_text(album.subtitle.as_deref().unwrap_or("アルバム"))).activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name("media-optical-symbolic"));
                let (app, entry, popover) = (app.clone(), entry.clone(), popover.clone());
                row.connect_activated(move |_| {
                    close(&popover, &entry);
                    app.remember_search(crate::settings::SearchHistoryEntry::album(&album.browse_id, &album.title, album.subtitle.as_deref(), album.thumbnail_url.as_deref()));
                    app.push(pages::album(&app, &album.browse_id, &album.title));
                });
                list.append(&row);
            }
            for song in found.songs.into_iter().take(3) {
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&song.title)).subtitle(gtk::glib::markup_escape_text(&song.artists_text())).activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name("audio-x-generic-symbolic"));
                let (app, entry, popover) = (app.clone(), entry.clone(), popover.clone());
                row.connect_activated(move |_| {
                    close(&popover, &entry);
                    app.remember_search(crate::settings::SearchHistoryEntry::song(&song));
                    app.playback.play_list(vec![song.clone()], 0);
                });
                list.append(&row);
            }
            if list.first_child().is_some() {
                popover.popup();
            } else {
                popover.popdown();
            }
        });
    });
    // 欄から離れたら・Esc で閉じる。空のまま入ったら履歴を出す
    let focus = gtk::EventControllerFocus::new();
    {
        let (entry, show_history) = (entry.clone(), show_history.clone());
        focus.connect_enter(move |_| {
            if entry.text().trim().is_empty() {
                show_history();
            }
        });
    }
    let popover_f = popover.clone();
    focus.connect_leave(move |_| {
        let popover = popover_f.clone();
        // 候補を押したときは、押し終わってから閉じる
        gtk::glib::timeout_add_local_once(std::time::Duration::from_millis(200), move || popover.popdown());
    });
    entry.add_controller(focus);
    let popover_s = popover.clone();
    entry.connect_stop_search(move |_| popover_s.popdown());
    popover
}

/// 右の欄の再生中（Mac の NowPlayingView）: 一番上にアルバム名か「再生中」、ジャケットか映像（上に「曲｜動画」）、
/// 曲名（押すとアルバム）と評価のボタン、アーティスト（押すとそのページ。複数なら選ぶ）、次の曲（押すとその曲へ）、流れる歌詞（押すと歌詞のページ）。
fn now_playing(app: &Rc<App>) -> gtk::Widget {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).margin_top(16).margin_bottom(16).margin_start(16).margin_end(16).build();
    let heading = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["heading"]).build();
    let media = gtk::Overlay::builder().child(&video_or_cover(app, Place::Side)).halign(gtk::Align::Start).build();
    let switch = song_video_switch(app);
    switch.set_halign(gtk::Align::Center);
    switch.set_valign(gtk::Align::Start);
    switch.set_margin_top(10);
    media.add_overlay(&switch);
    let title = gtk::Label::builder().xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["title-2"]).build();
    let artist = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["dim-label"]).build();
    album_link(app, &title);
    artist_link(app, &artist);
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).hexpand(true).build();
    text.append(&title);
    text.append(&artist);
    let like = gtk::Button::builder().icon_name("list-add-symbolic").tooltip_text("高く評価した曲に追加").valign(gtk::Align::Start).css_classes(["flat", "circular", "like-button"]).build();
    like_behaviour(app, &like);
    let title_row = gtk::Box::builder().spacing(8).build();
    title_row.append(&text);
    title_row.append(&like);

    // 次の曲（見出しは常に出し、右に「キューを開く」）
    let next_heading = gtk::Label::builder().label("次の曲").xalign(0.0).hexpand(true).css_classes(["heading"]).build();
    let open_queue = gtk::Button::builder().label("キューを開く").css_classes(["flat", "text-link"]).build();
    {
        let app = app.clone();
        open_queue.connect_clicked(move |_| app.show_queue(false));
    }
    let next_header = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    next_header.append(&next_heading);
    next_header.append(&open_queue);
    let next_art = images::square(44);
    let next_title = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build();
    let next_artist = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["caption", "dim-label"]).build();
    let next_text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).valign(gtk::Align::Center).build();
    next_text.append(&next_title);
    next_text.append(&next_artist);
    let next_row = gtk::Box::builder().spacing(12).build();
    next_row.append(&next_art.root);
    next_row.append(&next_text);
    {
        let app = app.clone();
        let click = gtk::GestureClick::new();
        click.connect_released(move |_, _, _, _| {
            if app.connect.mirror().is_none()
                && let (_, Some(index)) = app.playback.queue()
            {
                app.playback.play_at(index + 1);
            }
        });
        next_row.add_controller(click);
        next_row.set_cursor_from_name(Some("pointer"));
    }
    let next_card = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).css_classes(["np-card"]).build();
    next_card.append(&next_header);
    next_card.append(&next_row);

    // 歌詞（無い曲では箱ごと出さない）。押すと歌詞のページ
    let lyrics_card = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).visible(false).css_classes(["np-card"]).build();
    lyrics_card.append(&gtk::Label::builder().label("歌詞").xalign(0.0).css_classes(["heading"]).build());
    let card = lyrics_card.clone();
    let follower = super::full_view::lyrics_follower(app, false, move |found| card.set_visible(found));
    follower.set_size_request(-1, 190);
    lyrics_card.append(&follower);
    {
        let app = app.clone();
        let click = gtk::GestureClick::new();
        click.connect_released(move |_, _, _, _| {
            if app.visible_kind().as_deref() != Some("lyrics") {
                app.push(super::lyrics::page(&app));
            }
        });
        lyrics_card.add_controller(click);
        lyrics_card.set_cursor_from_name(Some("pointer"));
    }

    column.append(&heading);
    column.append(&media);
    column.append(&title_row);
    column.append(&next_card);
    column.append(&lyrics_card);

    let shown = Rc::new(RefCell::new(None::<(Option<String>, Option<String>)>));
    let refresh = {
        let app = app.clone();
        move || {
            // ほかの端末で鳴っていれば、それを出す
            let (song, next) = match app.connect.mirror() {
                Some(mirror) => (Some(mirror.song), mirror.next),
                None => (app.playback.current(), app.playback.upcoming().into_iter().next()),
            };
            // 連携の知らせは毎秒来るので、曲と次の曲が同じなら描き直さない
            let key = (song.as_ref().map(|s| s.video_id.clone()), next.as_ref().map(|s| s.video_id.clone()));
            if shown.borrow().as_ref() == Some(&key) {
                return;
            }
            *shown.borrow_mut() = Some(key);
            let album = song.as_ref().and_then(|s| s.album.as_ref()).map(|a| a.title.clone()).filter(|t| !t.is_empty());
            heading.set_label(album.as_deref().unwrap_or("再生中"));
            title.set_label(song.as_ref().map_or("何も再生していません", |s| s.title.as_str()));
            artist.set_label(&song.as_ref().map(|s| s.artists_text()).unwrap_or_default());
            next_row.set_visible(next.is_some());
            if let Some(next) = next {
                next_art.load(next.thumbnail_url.as_deref(), 88);
                next_title.set_label(&next.title);
                next_artist.set_label(&next.artists_text());
            }
        }
    };
    let refresh = Rc::new(refresh);
    refresh();
    let r = refresh.clone();
    app.playback.on_change(move || r());
    app.connect.on_change(move || refresh());
    gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).build().upcast()
}

/// 上の帯の右端: アカウントの写真。押すと名前・最近再生した曲・端末の連携（中継を設定しているとき）・設定（Mac と同じ）。
/// ログアウトは設定のアカウントのカードから（確認つき）。
fn account_button(app: &Rc<App>) -> gtk::MenuButton {
    let avatar = adw::Avatar::builder().size(30).show_initials(true).build();
    let name = gtk::Label::builder().css_classes(["heading"]).margin_bottom(6).build();
    let history = gtk::Button::builder().label("最近再生した曲").css_classes(["flat"]).build();
    let settings = gtk::Button::builder().label("設定").css_classes(["flat"]).build();
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).margin_top(6).margin_bottom(6).margin_start(6).margin_end(6).build();
    column.append(&name);
    column.append(&gtk::Separator::new(gtk::Orientation::Horizontal));
    column.append(&history);
    // 端末の連携（ログイン中かつ中継が設定済みのときだけ。押すと小窓を閉じてページを開く）
    if let Some(devices) = super::devices::menu_button(app) {
        column.append(&devices);
    }
    column.append(&settings);
    let popover = gtk::Popover::builder().child(&column).build();
    let button = gtk::MenuButton::builder().child(&avatar).popover(&popover).tooltip_text("アカウント").css_classes(["flat", "circular"]).build();
    {
        let (app, popover) = (app.clone(), popover.clone());
        settings.connect_clicked(move |_| {
            popover.popdown();
            app.push(super::settings::page(&app));
        });
    }
    {
        let (app, popover) = (app.clone(), popover.clone());
        history.connect_clicked(move |_| {
            popover.popdown();
            app.push(super::pages::history(&app));
        });
    }
    let repo = app.repo.clone();
    gtk::glib::spawn_future_local(async move {
        let Ok(account) = runtime::run(async move { repo.account().await }).await else { return };
        let text = account.name.unwrap_or_else(|| "ログイン中".to_string());
        avatar.set_text(Some(&text));
        name.set_label(&text);
        if let Some(url) = account.photo_url {
            images::texture(&url, 88, move |texture| avatar.set_custom_image(Some(&texture)));
        }
    });
    button
}

/// キューの1行（Mac の QueueEntry と同じ）。section はかたまりの最初の行にだけ付く見出し。
#[derive(Clone, Debug)]
struct QueueEntry {
    index: usize,
    item_id: String,
    song: Song,
    is_current: bool,
    section: Option<&'static str>,
    /// 「次に流れる曲」の見出しの行（見出しに「消去」を出す）。
    flow_header: bool,
}

/// 今の曲のすぐ後ろに続く、自分で足した曲（「次に再生」など）のかたまりの終わり（その次の位置）。
fn user_queue_end(items: &[QueueItem], index: usize) -> usize {
    let mut p = index + 1;
    while items.get(p).is_some_and(|q| q.origin == Origin::UserAdded) {
        p += 1;
    }
    p
}

/// キューの欄の並び（Mac と同じ）: 一番上に今の曲、その下に自分で足した曲、その下に元から流れる予定の曲。
/// history なら、もう流れた曲を新しい順に（押すとその曲に戻る）。
fn queue_entries(items: &[QueueItem], index: Option<usize>, history: bool) -> Vec<QueueEntry> {
    let Some(index) = index.filter(|&i| i < items.len()) else { return Vec::new() };
    let entry = |i: usize, section: Option<&'static str>, flow_header: bool| QueueEntry {
        index: i,
        item_id: items[i].item_id.clone(),
        song: items[i].song.clone(),
        is_current: i == index && !history,
        section,
        flow_header,
    };
    if history {
        return (0..index).rev().map(|i| entry(i, None, false)).collect();
    }
    let user_end = user_queue_end(items, index);
    (index..items.len())
        .map(|i| {
            let section = if i == index {
                Some("再生中")
            } else if i == index + 1 && i < user_end {
                Some("キューの次の曲")
            } else if i == user_end {
                Some("次に流れる曲")
            } else {
                None
            };
            entry(i, section, i == user_end && i > index)
        })
        .collect()
}

/// 自分で足した曲のかたまりを外す（Mac の「キューを消去」）。
fn clear_user_queue(app: &Rc<App>) {
    let (items, Some(index)) = app.playback.queue_items() else { return };
    let end = user_queue_end(&items, index);
    let ids: Vec<String> = items.get(index + 1..end).unwrap_or_default().iter().map(|q| q.item_id.clone()).collect();
    app.playback.remove_items(&ids);
}

/// 元の流れの曲をまとめて外し、ラジオも止める（自分で足した曲は残す。Mac の「次に流れる曲」の「消去」）。
fn clear_flow(app: &Rc<App>) {
    let (items, Some(index)) = app.playback.queue_items() else { return };
    let Some(current) = items.get(index) else { return };
    let keep: Vec<QueueItem> = items[index + 1..].iter().filter(|q| q.origin == Origin::UserAdded).cloned().collect();
    if app.playback.clear_flow(&current.item_id) && !keep.is_empty() {
        app.playback.insert_items(keep, true, None);
    }
}

/// 右の欄のキュー（Mac の QueueView）: 上に「キュー｜履歴」・キューを消去・×。今の曲はアクセント色、押すとその曲へ、
/// 右クリックで曲のメニュー（キューから削除も）、ドラッグで並べ替え。
fn queue(app: &Rc<App>) -> gtk::Widget {
    let switch = adw::ToggleGroup::builder().css_classes(["round"]).build();
    switch.add(adw::Toggle::builder().label("キュー").name("queue").build());
    switch.add(adw::Toggle::builder().label("履歴").name("history").build());
    switch.set_active_name(Some("queue"));
    let clear = gtk::Button::builder().label("キューを消去").css_classes(["flat", "text-link"]).visible(false).build();
    let close = gtk::Button::builder().icon_name("window-close-symbolic").tooltip_text("閉じる").css_classes(["flat", "circular"]).build();
    let spacer = gtk::Box::builder().hexpand(true).build();
    let top = gtk::Box::builder().spacing(4).margin_top(12).margin_start(16).margin_end(12).margin_bottom(4).build();
    top.append(&switch);
    top.append(&spacer);
    top.append(&clear);
    top.append(&close);
    {
        let app = app.clone();
        switch.connect_active_name_notify(move |switch| {
            let history = switch.active_name().as_deref() == Some("history");
            if app.view.get().queue_history != history {
                app.update_view(|v| v.queue_history = history);
            }
        });
    }
    {
        let app = app.clone();
        clear.connect_clicked(move |_| clear_user_queue(&app));
    }
    {
        let app = app.clone();
        close.connect_clicked(move |_| app.set_right_pane(true, false));
    }
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).margin_start(10).margin_end(10).margin_bottom(12).css_classes(["queue-list"]).build();
    let empty_title = gtk::Label::builder().css_classes(["heading"]).build();
    let empty_text = gtk::Label::builder().wrap(true).justify(gtk::Justification::Center).css_classes(["caption", "dim-label"]).build();
    let empty = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).valign(gtk::Align::Center).margin_start(24).margin_end(24).build();
    empty.append(&empty_title);
    empty.append(&empty_text);
    let stack = gtk::Stack::builder().vexpand(true).build();
    stack.add_named(&gtk::ScrolledWindow::builder().child(&list).hscrollbar_policy(gtk::PolicyType::Never).build(), Some("list"));
    stack.add_named(&empty, Some("empty"));
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    column.append(&top);
    column.append(&stack);

    {
        let app = app.clone();
        list.connect_row_activated(move |_, row| {
            // 行の名前に「キューの位置」を入れておく（見出しの行には無い）
            if let Ok(index) = row.widget_name().parse::<usize>() {
                app.playback.play_at(index);
            }
        });
    }
    let shown: Rc<RefCell<Option<String>>> = Rc::default();
    let refresh = {
        let weak = Rc::downgrade(app);
        move || {
            let Some(app) = weak.upgrade() else { return };
            let history = app.view.get().queue_history;
            let name = if history { "history" } else { "queue" };
            if switch.active_name().as_deref() != Some(name) {
                switch.set_active_name(Some(name));
            }
            let mirror = app.connect.mirror();
            let (items, index) = app.playback.queue_items();
            // 曲・並び・切り替えが同じなら描き直さない（連携の知らせは毎秒来る）
            let key = match &mirror {
                Some(m) => format!("m:{history}:{}:{:?}", m.song.video_id, m.next.as_ref().map(|s| &s.video_id)),
                None => format!("l:{history}:{index:?}:{}", items.iter().map(|q| q.item_id.as_str()).collect::<Vec<_>>().join(",")),
            };
            if shown.borrow().as_deref() == Some(key.as_str()) {
                return;
            }
            *shown.borrow_mut() = Some(key);
            while let Some(row) = list.first_child() {
                list.remove(&row);
            }
            let remote = mirror.is_some();
            // ほかの端末を映している間は、その端末の今の曲と次の曲だけを出す（操作はしない）
            let entries = match mirror {
                Some(m) if !history => {
                    let mut e = vec![QueueEntry { index: usize::MAX, item_id: String::new(), song: m.song, is_current: true, section: Some("再生中"), flow_header: false }];
                    if let Some(next) = m.next {
                        e.push(QueueEntry { index: usize::MAX, item_id: String::new(), song: next, is_current: false, section: Some("次に流れる曲"), flow_header: false });
                    }
                    e
                }
                Some(_) => Vec::new(),
                None => queue_entries(&items, index, history),
            };
            let user_added = !remote && !history && index.is_some_and(|i| user_queue_end(&items, i) > i + 1);
            clear.set_visible(user_added);
            if entries.is_empty() {
                empty_title.set_label(if history { "まだ流れた曲はありません" } else { "キューは空です" });
                empty_text.set_label(if history { "流れた曲は、ここに新しい順に並びます" } else { "曲を再生すると、ここに次の曲が並びます" });
                stack.set_visible_child_name("empty");
                return;
            }
            stack.set_visible_child_name("list");
            let mut previous_id: Option<String> = None;
            for entry in &entries {
                if let Some(section) = entry.section {
                    list.append(&queue_header(&app, section, entry.flow_header && !remote));
                }
                list.append(&queue_row(&app, entry, history || remote, previous_id.clone()));
                previous_id = Some(entry.item_id.clone());
            }
        }
    };
    let refresh = Rc::new(refresh);
    refresh();
    let r = refresh.clone();
    app.on_view(move || r());
    let r = refresh.clone();
    app.playback.on_change(move || r());
    app.connect.on_change(move || refresh());
    column.upcast()
}

/// キューの見出しの行（「次に流れる曲」には「消去」）。
fn queue_header(app: &Rc<App>, title: &str, flow: bool) -> gtk::ListBoxRow {
    let line = gtk::Box::builder().spacing(8).margin_top(10).margin_bottom(2).margin_start(4).build();
    line.append(&gtk::Label::builder().label(title).xalign(0.0).hexpand(true).css_classes(["heading"]).build());
    if flow {
        let clear = gtk::Button::builder().label("消去").tooltip_text("元の流れの曲をまとめて外す（自分で足した曲は残す）").css_classes(["flat", "text-link"]).build();
        let app = app.clone();
        clear.connect_clicked(move |_| clear_flow(&app));
        line.append(&clear);
    }
    gtk::ListBoxRow::builder().child(&line).activatable(false).selectable(false).build()
}

/// キューの曲の行。fixed なら並べ替えもメニューも付けない（履歴・ほかの端末）。previous_id は表示で1つ上の曲（ドラッグで上半分に落としたら、その後ろへ）。
fn queue_row(app: &Rc<App>, entry: &QueueEntry, fixed: bool, previous_id: Option<String>) -> gtk::ListBoxRow {
    let art = images::square(44);
    art.load(entry.song.thumbnail_url.as_deref(), 88);
    let title = gtk::Label::builder().label(&entry.song.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build();
    if entry.is_current {
        title.add_css_class("queue-current");
    }
    let artist = gtk::Label::builder().label(entry.song.artists_text()).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["caption", "dim-label"]).build();
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).valign(gtk::Align::Center).hexpand(true).build();
    text.append(&title);
    text.append(&artist);
    let line = gtk::Box::builder().spacing(12).css_classes(["queue-row"]).build();
    line.append(&art.root);
    line.append(&text);
    let clickable = entry.index != usize::MAX && !entry.is_current;
    let row = gtk::ListBoxRow::builder().child(&line).activatable(clickable).selectable(false).build();
    if clickable {
        row.set_widget_name(&entry.index.to_string());
    }
    if entry.index == usize::MAX {
        return row;
    }
    // 右クリックで曲のメニュー（今の曲と履歴には「キューから削除」を出さない）
    {
        let (app, song, line) = (app.clone(), entry.song.clone(), line.clone());
        let removable = (!entry.is_current && !fixed).then(|| entry.item_id.clone());
        let click = gtk::GestureClick::builder().button(3).build();
        click.connect_pressed(move |gesture, _, x, y| {
            gesture.set_state(gtk::EventSequenceState::Claimed);
            super::song_menu::popup_queue(&app, &song, removable.clone(), &line, x, y);
        });
        row.add_controller(click);
    }
    if fixed {
        return row;
    }
    // ドラッグで並べ替え（今の曲は動かせない。Mac・Android と同じ）
    if !entry.is_current {
        let source = gtk::DragSource::builder().actions(gtk::gdk::DragAction::MOVE).build();
        source.set_content(Some(&gtk::gdk::ContentProvider::for_value(&entry.item_id.to_value())));
        let r = row.downgrade();
        source.connect_drag_begin(move |source, _| {
            if let Some(row) = r.upgrade() {
                source.set_icon(Some(&gtk::WidgetPaintable::new(Some(&row))), 0, 0);
            }
        });
        row.add_controller(source);
    }
    let target = gtk::DropTarget::new(String::static_type(), gtk::gdk::DragAction::MOVE);
    let upper = |row: &gtk::ListBoxRow, y: f64| y < f64::from(row.height()) / 2.0;
    {
        let (r, l) = (row.downgrade(), line.clone());
        let is_current = entry.is_current;
        target.connect_motion(move |_, _, y| {
            if let Some(row) = r.upgrade() {
                let above = upper(&row, y) && !is_current;
                if above { l.add_css_class("drop-above") } else { l.remove_css_class("drop-above") }
                if above { l.remove_css_class("drop-below") } else { l.add_css_class("drop-below") }
            }
            gtk::gdk::DragAction::MOVE
        });
    }
    {
        let l = line.clone();
        target.connect_leave(move |_| {
            l.remove_css_class("drop-above");
            l.remove_css_class("drop-below");
        });
    }
    {
        let (app, r, l, own_id, is_current) = (app.clone(), row.downgrade(), line.clone(), entry.item_id.clone(), entry.is_current);
        target.connect_drop(move |_, value, _, y| {
            l.remove_css_class("drop-above");
            l.remove_css_class("drop-below");
            let (Ok(dragged), Some(row)) = (value.get::<String>(), r.upgrade()) else { return false };
            // 上半分なら1つ上の曲の後ろ、下半分ならこの曲の後ろ（今の曲より前には置かない）
            let after = if upper(&row, y) && !is_current { previous_id.clone() } else { Some(own_id.clone()) };
            if after.as_deref() == Some(dragged.as_str()) {
                return false;
            }
            app.playback.move_item(&dragged, after.as_deref())
        });
    }
    row.add_controller(target);
    row
}

/// 評価のボタン（Mac と同じ ＋ と ✓）: 曲が変わったら評価を読み直す。＋ を押すと高く評価し（失敗したら元に戻す）、
/// ✓ を押すとプレイリストに追加・高く評価した曲から削除の小さなメニューを開く。評価を読めなかったときは ↻ で読み直す。
fn like_behaviour(app: &Rc<App>, like: &gtk::Button) {
    // None は読めていない（読み中・失敗）
    let state: Rc<Cell<Option<bool>>> = Rc::default();
    let shown: Rc<std::cell::RefCell<Option<String>>> = Rc::default();
    let show = {
        let like = like.clone();
        Rc::new(move |liked: Option<bool>, loading: bool| {
            let (icon, tip) = match liked {
                Some(true) => ("emblem-ok-symbolic", "プレイリストに追加"),
                Some(false) => ("list-add-symbolic", "高く評価した曲に追加"),
                None => ("view-refresh-symbolic", "評価を読み直す"),
            };
            like.set_icon_name(icon);
            like.set_tooltip_text(Some(tip));
            if liked == Some(true) { like.add_css_class("liked") } else { like.remove_css_class("liked") }
            like.set_sensitive(!loading);
        })
    };
    let load = {
        let (app, state, shown, show) = (app.clone(), state.clone(), shown.clone(), show.clone());
        Rc::new(move |id: String| {
            state.set(None);
            show(None, true);
            let (repo, state, shown, show) = (app.repo.clone(), state.clone(), shown.clone(), show.clone());
            gtk::glib::spawn_future_local(async move {
                let wanted = id.clone();
                let status = runtime::run(async move { repo.like_status(&wanted).await }).await;
                if shown.borrow().as_deref() != Some(id.as_str()) {
                    return;
                }
                let liked = status.ok().flatten().map(|s| s == wave_core::music::models::LikeStatus::Like);
                state.set(liked);
                show(liked, false);
            });
        })
    };
    {
        let (app, like, shown, load) = (app.clone(), like.clone(), shown.clone(), load.clone());
        let playback = app.playback.clone();
        playback.on_change(move || {
            let current = app.playback.current();
            // RSS のエピソードは YouTube の曲ではないので、評価もプレイリストへの追加もできない（Mac と同じく出さない）
            like.set_visible(current.as_ref().is_some_and(|s| !wave_core::music::feed::is_external(&s.video_id)));
            let id = current.map(|s| s.video_id);
            if *shown.borrow() == id {
                return;
            }
            *shown.borrow_mut() = id.clone();
            if let Some(id) = id {
                load(id);
            }
        });
    }
    let app = app.clone();
    like.connect_clicked(move |button| {
        let Some(song) = app.playback.current() else { return };
        match state.get() {
            None => load(song.video_id.clone()),
            Some(true) => {
                let (state, show) = (state.clone(), show.clone());
                super::song_menu::popup_saved(&app, &song, button, move || {
                    state.set(Some(false));
                    show(Some(false), false);
                });
            }
            Some(false) => {
                state.set(Some(true));
                show(Some(true), false);
                let (repo, state, show, shown) = (app.repo.clone(), state.clone(), show.clone(), shown.clone());
                gtk::glib::spawn_future_local(async move {
                    let id = song.video_id.clone();
                    if runtime::run(async move { repo.set_liked(&id, true).await }).await.is_err()
                        && shown.borrow().as_deref() == Some(song.video_id.as_str())
                    {
                        state.set(Some(false));
                        show(Some(false), false);
                    }
                });
            }
        }
    });
}

/// シャッフル・リピートのボタンの見た目（前の版の端末を映している間は押せない）。
fn show_modes(shuffle: &gtk::ToggleButton, repeat: &gtk::ToggleButton, shuffled: bool, mode: crate::playback::Repeat, usable: bool) {
    use crate::playback::Repeat;
    shuffle.set_active(shuffled);
    repeat.set_active(mode != Repeat::Off);
    repeat.set_icon_name(if mode == Repeat::One { "media-playlist-repeat-song-symbolic" } else { "media-playlist-repeat-symbolic" });
    shuffle.set_sensitive(usable);
    repeat.set_sensitive(usable);
}

/// 音量の絵（4段。Mac と同じ区切り）。
fn volume_icon(volume: f64) -> &'static str {
    if volume <= 0.0 {
        "audio-volume-muted-symbolic"
    } else if volume < 0.34 {
        "audio-volume-low-symbolic"
    } else if volume < 0.67 {
        "audio-volume-medium-symbolic"
    } else {
        "audio-volume-high-symbolic"
    }
}

/// 端末の小窓の1行（絵・名前・補足）。accent は今鳴っている端末（アクセントの色）、押せないものは薄くする。
fn device_row(icon: &str, title: &str, subtitle: Option<&str>, accent: bool, enabled: bool) -> gtk::Box {
    let image = gtk::Image::builder().icon_name(icon).pixel_size(22).width_request(30).build();
    let name = gtk::Label::builder().label(title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["heading"]).build();
    if accent {
        image.add_css_class("accent-text");
        name.add_css_class("accent-text");
    }
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(1).valign(gtk::Align::Center).hexpand(true).build();
    text.append(&name);
    if let Some(subtitle) = subtitle.filter(|s| !s.is_empty()) {
        text.append(&gtk::Label::builder().label(subtitle).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["caption", "dim-label"]).build());
    }
    let line = gtk::Box::builder().spacing(12).css_classes(["device-row"]).build();
    if !enabled {
        line.add_css_class("off");
    }
    line.append(&image);
    line.append(&text);
    line
}

/// 下の帯の「デバイスに接続」（Mac の DevicesPopover）: 「現在のデバイス」に今鳴っている端末、「ほかのデバイスを選択」に家のほかの端末
/// （押すとその端末に移す。この PC を押すと、ほかの端末の再生を引き取る）、最後に「端末の連携の設定」。
/// 操作だけの端末と wave API（音を出さない）は出さない。自分を先に、その後はオンラインのもの。オフラインは薄くして押せない。
fn fill_devices(app: &Rc<App>, column: &gtk::Box, popover: &gtk::Popover) {
    while let Some(child) = column.first_child() {
        column.remove(&child);
    }
    let info = app.connect.info();
    let members = app.connect.members();
    let mirror = app.connect.mirror();
    let is_pc = |id: &str| members.iter().find(|m| m.device_id == id).and_then(|m| m.kind.as_deref()) == Some("pc");
    column.append(&gtk::Label::builder().label("現在のデバイス").xalign(0.0).css_classes(["heading"]).build());
    match &mirror {
        Some(m) => {
            let subtitle = [m.song.title.clone(), m.song.artists_text()].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" · ");
            column.append(&device_row(if is_pc(&m.device_id) { "computer-symbolic" } else { "phone-symbolic" }, &m.label, Some(&subtitle), true, true));
        }
        None => column.append(&device_row("computer-symbolic", "この PC", None, true, true)),
    }
    if !info.in_household {
        column.append(&gtk::Label::builder().label("スマホと連携すると、再生を引き継げます").xalign(0.0).wrap(true).margin_top(4).css_classes(["dim-label"]).build());
    } else {
        let mut others: Vec<_> = members
            .iter()
            .filter(|m| match &mirror {
                None => !m.is_self,
                Some(r) => m.device_id != r.device_id,
            })
            .filter(|m| m.kind.as_deref() != Some("api"))
            .cloned()
            .collect();
        // 自分を先に、その後はオンラインのものを先に（同じなら家の順のまま）
        others.sort_by_key(|m| (!m.is_self, !(m.online || m.is_self)));
        if !others.is_empty() {
            column.append(&gtk::Label::builder().label("ほかのデバイスを選択").xalign(0.0).margin_top(8).css_classes(["heading"]).build());
        }
        for member in others {
            let enabled = member.is_self || member.online;
            let (icon, title) = if member.is_self { ("computer-symbolic", "この PC".to_string()) } else { (if member.kind.as_deref() == Some("pc") { "computer-symbolic" } else { "phone-symbolic" }, member.label.clone()) };
            let line = device_row(icon, &title, (!enabled).then_some("オフライン"), false, enabled);
            if !enabled {
                column.append(&line);
                continue;
            }
            let button = gtk::Button::builder().child(&line).css_classes(["flat"]).build();
            let (app, popover, device_id, is_self) = (app.clone(), popover.clone(), member.device_id.clone(), member.is_self);
            button.connect_clicked(move |_| {
                popover.popdown();
                if is_self {
                    app.connect.transfer_here();
                } else {
                    app.connect.play_on_device(&device_id);
                }
            });
            column.append(&button);
        }
    }
    // 最後に「端末の連携の設定」（押すと小窓を閉じてページを開く。全画面からは全画面も閉じる）
    let settings = super::devices::settings_link(app);
    settings.set_halign(gtk::Align::Start);
    settings.set_margin_top(8);
    settings.add_css_class("text-link");
    let app = app.clone();
    settings.connect_clicked(move |_| {
        if app.is_full() {
            app.set_full_screen(false);
        }
    });
    column.append(&settings);
}

/// 下の帯: 左に曲、中央に操作と位置（窓の左右の中央）、右に歌詞・キュー・端末・再生中の画面・全画面・音量（Mac と同じ）。
/// 窓が狭いときは、端末名、次に音量のバーを隠す（音量のバーはアイコンから小窓で出す）。
fn player_bar(app: &Rc<App>) -> gtk::Widget {
    // 左: 曲（ジャケットを押すと右の欄の再生中を開け閉め、曲名でアルバム、アーティストでそのページ）
    let art = images::square(56);
    {
        let app = app.clone();
        let click = gtk::GestureClick::new();
        click.connect_released(move |_, _, _, _| app.toggle_pane());
        art.root.add_controller(click);
        art.root.set_cursor_from_name(Some("pointer"));
    }
    let title = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(22).css_classes(["heading"]).build();
    let artist = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(22).css_classes(["caption", "dim-label"]).build();
    album_link(app, &title);
    artist_link(app, &artist);
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).spacing(2).build();
    text.append(&title);
    text.append(&artist);
    let song = gtk::Box::builder().spacing(12).build();
    song.append(&art.root);
    song.append(&text);
    // 評価（Mac と同じく曲名の右）
    let like = gtk::Button::builder().icon_name("list-add-symbolic").tooltip_text("高く評価した曲に追加").valign(gtk::Align::Center).css_classes(["flat", "circular", "like-button"]).build();
    song.append(&like);
    like_behaviour(app, &like);

    // 中央: 操作と位置
    let previous = gtk::Button::builder().icon_name("media-skip-backward-symbolic").tooltip_text("前へ").css_classes(["circular", "flat"]).build();
    let play_icon = gtk::Image::from_icon_name("media-playback-start-symbolic");
    let play_spinner = adw::Spinner::builder().width_request(20).height_request(20).halign(gtk::Align::Center).valign(gtk::Align::Center).build();
    let play_face = gtk::Stack::new();
    play_face.add_named(&play_icon, Some("icon"));
    play_face.add_named(&play_spinner, Some("loading"));
    let play = gtk::Button::builder().child(&play_face).tooltip_text("再生/一時停止").css_classes(["circular", "suggested-action", "play-large"]).build();
    let next = gtk::Button::builder().icon_name("media-skip-forward-symbolic").tooltip_text("次へ").css_classes(["circular", "flat"]).build();
    // シャッフルとリピート（Mac と同じく前・次の外側）
    let shuffle = gtk::ToggleButton::builder().icon_name("media-playlist-shuffle-symbolic").tooltip_text("シャッフル").css_classes(["circular", "flat"]).build();
    let repeat = gtk::ToggleButton::builder().icon_name("media-playlist-repeat-symbolic").tooltip_text("リピート").css_classes(["circular", "flat"]).build();
    let controls = gtk::Box::builder().spacing(12).halign(gtk::Align::Center).build();
    controls.append(&shuffle);
    controls.append(&previous);
    controls.append(&play);
    controls.append(&next);
    controls.append(&repeat);
    let position = gtk::Label::builder().label("0:00").width_chars(5).xalign(1.0).css_classes(["caption", "numeric", "dim-label"]).build();
    let duration = gtk::Label::builder().label("0:00").width_chars(5).xalign(0.0).css_classes(["caption", "numeric", "dim-label"]).build();
    let seek = gtk::Scale::builder().orientation(gtk::Orientation::Horizontal).hexpand(true).width_request(420).build();
    seek.set_range(0.0, 1.0);
    let seek_row = gtk::Box::builder().spacing(8).build();
    seek_row.append(&position);
    seek_row.append(&seek);
    seek_row.append(&duration);
    // ライブ配信: 長さも位置も無く、シークしない（時刻の段を LIVE の印に替える）
    let live = gtk::Label::builder().label("LIVE").halign(gtk::Align::Center).valign(gtk::Align::Center).css_classes(["live-badge"]).build();
    let seek_stack = gtk::Stack::builder().vhomogeneous(true).build();
    seek_stack.add_named(&seek_row, Some("seek"));
    seek_stack.add_named(&live, Some("live"));
    let middle = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    middle.append(&controls);
    middle.append(&seek_stack);

    // 右: 歌詞・キュー・端末・再生中の画面・全画面・音量
    let lyrics_toggle = gtk::ToggleButton::builder().icon_name("text-x-generic-symbolic").tooltip_text("歌詞").css_classes(["flat"]).build();
    let queue_toggle = gtk::ToggleButton::builder().icon_name("view-list-symbolic").tooltip_text("キュー").css_classes(["flat"]).build();
    let pane_toggle = gtk::ToggleButton::builder().icon_name("sidebar-show-right-symbolic").tooltip_text("再生中の画面").css_classes(["flat"]).build();
    let full_toggle = gtk::ToggleButton::builder().icon_name("view-fullscreen-symbolic").tooltip_text("全画面 (F11)").css_classes(["flat"]).build();
    // 音量: アイコンでミュート・戻す。狭いときはアイコンから小窓（ミュートと音量のバー）
    let volume = gtk::Scale::builder().orientation(gtk::Orientation::Horizontal).width_request(110).build();
    volume.set_range(0.0, 1.0);
    let mute = gtk::Button::builder().icon_name("audio-volume-high-symbolic").tooltip_text("ミュート").css_classes(["flat"]).build();
    let volume_inline = gtk::Box::builder().spacing(2).build();
    volume_inline.append(&mute);
    volume_inline.append(&volume);
    let small_volume = gtk::Scale::builder().orientation(gtk::Orientation::Horizontal).width_request(110).build();
    small_volume.set_range(0.0, 1.0);
    let small_mute = gtk::Button::builder().icon_name("audio-volume-high-symbolic").tooltip_text("ミュート").css_classes(["flat"]).build();
    let small_box = gtk::Box::builder().spacing(8).margin_top(6).margin_bottom(6).margin_start(6).margin_end(6).build();
    small_box.append(&small_mute);
    small_box.append(&small_volume);
    let volume_menu = gtk::MenuButton::builder()
        .icon_name("audio-volume-high-symbolic")
        .tooltip_text("音量")
        .popover(&gtk::Popover::builder().child(&small_box).build())
        .direction(gtk::ArrowType::Up)
        .css_classes(["flat"])
        .visible(false)
        .build();
    let end = gtk::Box::builder().spacing(4).valign(gtk::Align::Center).halign(gtk::Align::End).build();
    end.append(&lyrics_toggle);
    end.append(&queue_toggle);
    // 端末の連携の中継を設定しているときだけ、再生する端末を選ぶボタン（Mac と同じ）
    let device_icon = gtk::Image::from_icon_name("audio-speakers-symbolic");
    let device_name = gtk::Label::builder().ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(14).visible(false).css_classes(["caption", "heading", "accent-text"]).build();
    let devices = {
        let face = gtk::Box::builder().spacing(6).build();
        face.append(&device_icon);
        face.append(&device_name);
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).width_request(300).margin_top(8).margin_bottom(8).margin_start(8).margin_end(8).build();
        let popover = gtk::Popover::builder().child(&column).build();
        let app = app.clone();
        popover.connect_show(move |popover| fill_devices(&app, &column, popover));
        gtk::MenuButton::builder().child(&face).tooltip_text("デバイスに接続").popover(&popover).direction(gtk::ArrowType::Up).css_classes(["flat"]).build()
    };
    if app.connect.info().configured {
        end.append(&devices);
    }
    end.append(&pane_toggle);
    end.append(&full_toggle);
    end.append(&volume_inline);
    end.append(&volume_menu);

    let bar = gtk::CenterBox::builder().css_classes(["player-bar"]).build();
    bar.set_start_widget(Some(&song));
    bar.set_center_widget(Some(&middle));
    bar.set_end_widget(Some(&end));
    // 左右を同じ幅にして、操作を窓の左右の中央に置く
    let group = gtk::SizeGroup::new(gtk::SizeGroupMode::Horizontal);
    group.add_widget(&song);
    group.add_widget(&end);

    {
        // 押した結果は描き直しで合わせる（押した瞬間の切り替わりは打ち消す）
        let app_for_shuffle = app.clone();
        shuffle.connect_clicked(move |_| app_for_shuffle.connect.toggle_shuffle());
        let app_for_repeat = app.clone();
        repeat.connect_clicked(move |_| app_for_repeat.connect.cycle_repeat());
        let playback = app.playback.clone();
        previous.connect_clicked(move |_| playback.previous());
        let playback = app.playback.clone();
        play.connect_clicked(move |_| playback.toggle());
        // 次の曲が無くても押せる（Mac と同じ。ラジオを継ぎ足す途中のことがある）
        let playback = app.playback.clone();
        next.connect_clicked(move |_| playback.next());
        let a = app.clone();
        queue_toggle.connect_clicked(move |_| a.toggle_queue());
        let a = app.clone();
        pane_toggle.connect_clicked(move |_| a.toggle_pane());
        let a = app.clone();
        full_toggle.connect_clicked(move |_| a.set_full_screen(!a.is_full()));
    }
    // 音量（ほかの端末を映している間は、その端末の音量を変える）
    let set_volume: Rc<dyn Fn(f64)> = {
        let app = app.clone();
        Rc::new(move |value: f64| {
            let value = value.clamp(0.0, 1.0);
            if app.connect.mirror().is_some() {
                app.connect.remote_volume((value * 100.0).round() as i32);
            } else {
                app.playback.player().set_volume(value);
            }
        })
    };
    let current_volume = {
        let app = app.clone();
        move || match app.connect.mirror() {
            Some(mirror) => mirror.volume_percent.map_or(1.0, |p| f64::from(p) / 100.0),
            None => app.playback.player().volume(),
        }
    };
    let show_volume = {
        let (volume, small_volume, mute, small_mute, volume_menu) = (volume.clone(), small_volume.clone(), mute.clone(), small_mute.clone(), volume_menu.clone());
        Rc::new(move |value: f64| {
            for scale in [&volume, &small_volume] {
                if (scale.value() - value).abs() > 0.001 {
                    scale.set_value(value);
                }
            }
            let icon = volume_icon(value);
            mute.set_icon_name(icon);
            small_mute.set_icon_name(icon);
            volume_menu.set_icon_name(icon);
        })
    };
    for scale in [&volume, &small_volume] {
        let (set_volume, show_volume) = (set_volume.clone(), show_volume.clone());
        scale.connect_change_value(move |_, _, value| {
            set_volume(value);
            show_volume(value.clamp(0.0, 1.0));
            gtk::glib::Propagation::Proceed
        });
    }
    for button in [&mute, &small_mute] {
        let (app, set_volume, show_volume, current_volume) = (app.clone(), set_volume.clone(), show_volume.clone(), current_volume.clone());
        button.connect_clicked(move |_| {
            let now = current_volume();
            let value = if now > 0.0 {
                app.volume_before_mute.set(now);
                0.0
            } else {
                app.volume_before_mute.get()
            };
            set_volume(value);
            show_volume(value);
        });
    }
    // 表示の状態（右の欄・全画面・窓の幅）に合わせる
    {
        let weak = Rc::downgrade(app);
        let (queue_toggle, pane_toggle, full_toggle, device_name, volume_inline, volume_menu, seek) =
            (queue_toggle.clone(), pane_toggle.clone(), full_toggle.clone(), device_name.clone(), volume_inline.clone(), volume_menu.clone(), seek.clone());
        let sync = move || {
            let Some(app) = weak.upgrade() else { return };
            let shown = app.show_right();
            for (toggle, on) in [(&queue_toggle, shown && app.right_view() == RightView::Queue), (&pane_toggle, shown && app.right_view() == RightView::Now), (&full_toggle, app.is_full())] {
                if toggle.is_active() != on {
                    toggle.set_active(on);
                }
            }
            // 狭いとき（1180 未満）は位置のバーを縮め、音量のバーをアイコンの小窓へしまう（端末名は 1400 未満でしまう）
            let level = app.width_level();
            device_name.set_visible(level == 0 && app.connect.mirror().is_some());
            volume_inline.set_visible(level <= 1);
            volume_menu.set_visible(level > 1);
            seek.set_width_request(if level <= 1 { 420 } else { 240 });
        };
        sync();
        app.on_view(sync);
    }
    // 歌詞は中央のページに出す（もう一度押すと閉じる。Mac と同じ）。ページを戻る・ホームで閉じたらボタンも戻す
    {
        let app_for_toggle = app.clone();
        lyrics_toggle.connect_toggled(move |t| {
            let open = app_for_toggle.visible_kind().as_deref() == Some("lyrics");
            if t.is_active() && !open {
                app_for_toggle.open_from_full(|| app_for_toggle.push(super::lyrics::page(&app_for_toggle)));
            } else if !t.is_active() && open {
                app_for_toggle.nav.pop();
            }
        });
        let toggle = lyrics_toggle.clone();
        app.nav.connect_visible_page_notify(move |nav| {
            let open = visible_kind(nav).as_deref() == Some("lyrics");
            if toggle.is_active() != open {
                toggle.set_active(open);
            }
        });
    }
    // 利用者が動かしたときだけシークする（位置の書き換えでは呼ばれない）。Mac と同じく、動かしている間は時刻だけをつまみに付いてこさせ、
    // 手を止めてから（0.3 秒動かなければ）1度だけ移る。動かしている間は、0.5 秒ごとの描き直しでつまみを戻さない
    let dragging = Rc::new(Cell::new(false));
    {
        let (app, dragging, position) = (app.clone(), dragging.clone(), position.clone());
        let pending: Rc<std::cell::RefCell<Option<gtk::glib::SourceId>>> = Rc::default();
        seek.connect_change_value(move |_, _, value| {
            let value = value.clamp(0.0, 1.0);
            dragging.set(true);
            let total_ms = match app.connect.mirror() {
                Some(mirror) => mirror.song.duration_seconds.map(|s| u64::from(s) * 1000),
                None => app.playback.player().duration_ms(),
            };
            if let Some(total) = total_ms {
                position.set_label(&pages::format_seconds((value * total as f64 / 1000.0) as u32));
            }
            if let Some(old) = pending.borrow_mut().take() {
                old.remove();
            }
            let (app, dragging, pending_inner) = (app.clone(), dragging.clone(), pending.clone());
            let id = gtk::glib::timeout_add_local_once(std::time::Duration::from_millis(300), move || {
                pending_inner.borrow_mut().take();
                if let Some(mirror) = app.connect.mirror() {
                    if let Some(seconds) = mirror.song.duration_seconds {
                        app.connect.remote_seek((value * f64::from(seconds) * 1000.0) as i64);
                    }
                } else if let Some(total) = app.playback.player().duration_ms() {
                    app.playback.player().seek_ms((value * total as f64) as u64);
                }
                // 移った位置が描き直しに届くまで少し待ってから、つまみを位置に合わせ直す
                let dragging = dragging.clone();
                gtk::glib::timeout_add_local_once(std::time::Duration::from_millis(600), move || dragging.set(false));
            });
            *pending.borrow_mut() = Some(id);
            gtk::glib::Propagation::Proceed
        });
    }
    let refresh = {
        let (app, middle, play, song, show_volume, current_volume) = (app.clone(), middle.clone(), play.clone(), song.clone(), show_volume.clone(), current_volume.clone());
        let (shuffle, repeat, device_name) = (shuffle.clone(), repeat.clone(), device_name.clone());
        move || {
            let (shuffled, repeat_mode, modes_usable) = match app.connect.mirror() {
                Some(mirror) => (mirror.shuffle.unwrap_or(false), mirror.repeat, mirror.shuffle.is_some()),
                None => (app.playback.shuffled(), app.playback.repeat(), true),
            };
            show_modes(&shuffle, &repeat, shuffled, repeat_mode, modes_usable);
            show_volume(current_volume());
            // ほかの端末で鳴っていれば、それを映す（操作はその端末への命令になる）。端末のボタンをスマホの絵とその端末名にする
            let mirror = app.connect.mirror();
            device_icon.set_icon_name(Some(if mirror.is_some() { "phone-symbolic" } else { "audio-speakers-symbolic" }));
            if mirror.is_some() { device_icon.add_css_class("accent-text") } else { device_icon.remove_css_class("accent-text") }
            device_name.set_label(mirror.as_ref().map_or("", |m| m.label.as_str()));
            device_name.set_visible(app.width_level() == 0 && mirror.is_some());
            if let Some(mirror) = mirror {
                middle.set_sensitive(true);
                song.set_visible(true);
                art.load(mirror.song.thumbnail_url.as_deref(), 112);
                title.set_label(&mirror.song.title);
                artist.set_label(&mirror.song.artists_text());
                play_face.set_visible_child_name("icon");
                play_icon.set_icon_name(Some(if mirror.playing { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" }));
                return;
            }
            let current = app.playback.current();
            middle.set_sensitive(current.is_some());
            song.set_visible(current.is_some());
            if let Some(song) = &current {
                art.load(song.thumbnail_url.as_deref(), 112);
                title.set_label(&song.title);
                artist.set_label(&song.artists_text());
            }
            let player = app.playback.player();
            let playing = player.is_playing();
            // 読み込み中（鳴らそうとしていて、まだ鳴っていない）はくるくる
            let loading = current.is_some() && player.wants_to_play() && !playing;
            play_face.set_visible_child_name(if loading { "loading" } else { "icon" });
            play_icon.set_icon_name(Some(if playing { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" }));
            play.set_tooltip_text(Some("再生/一時停止"));
        }
    };
    refresh();
    app.playback.on_change(refresh.clone());
    // 位置は 0.5 秒ごとに描き直す（再生・一時停止の印も、状態の変化が遅れて届くのでここでも合わせる）
    app.connect.on_change(refresh.clone());
    let app = app.clone();
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(500), move || {
        let player = app.playback.player();
        let (pos, total) = match app.connect.mirror() {
            Some(mirror) => (mirror.position_ms.max(0) as u64, u64::from(mirror.song.duration_seconds.unwrap_or(0)) * 1000),
            // 前回のキューを戻しただけのときは、戻した位置と曲の長さを出す
            None => (
                app.playback.position_ms(),
                player.duration_ms().or_else(|| app.playback.current().and_then(|s| s.duration_seconds).map(|d| u64::from(d) * 1000)).unwrap_or(0),
            ),
        };
        let live = app.connect.mirror().map(|m| m.song.is_live).unwrap_or_else(|| app.playback.current().is_some_and(|s| s.is_live));
        seek_stack.set_visible_child_name(if live { "live" } else { "seek" });
        if !dragging.get() {
            position.set_label(&pages::format_seconds((pos / 1000) as u32));
        }
        duration.set_label(&pages::format_seconds((total / 1000) as u32));
        if !live && !dragging.get() && total > 0 {
            seek.set_value(pos as f64 / total as f64);
        }
        refresh();
        gtk::glib::ControlFlow::Continue
    });
    bar.upcast()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(id: &str, origin: Origin) -> QueueItem {
        let song = Song { video_id: id.into(), title: id.into(), artists: Vec::new(), album: None, duration_seconds: None, thumbnail_url: None, kind: None, is_live: false };
        QueueItem { song, item_id: id.into(), origin }
    }

    #[test]
    fn queue_sections_split_now_user_added_and_flow() {
        let items = vec![item("a", Origin::Source), item("b", Origin::Source), item("u1", Origin::UserAdded), item("u2", Origin::UserAdded), item("c", Origin::Source), item("d", Origin::Suggestion)];
        let entries = queue_entries(&items, Some(1), false);
        let shape: Vec<(&str, Option<&str>, bool, bool)> = entries.iter().map(|e| (e.item_id.as_str(), e.section, e.is_current, e.flow_header)).collect();
        assert_eq!(
            shape,
            vec![
                ("b", Some("再生中"), true, false),
                ("u1", Some("キューの次の曲"), false, false),
                ("u2", None, false, false),
                ("c", Some("次に流れる曲"), false, true),
                ("d", None, false, false),
            ]
        );
        assert_eq!(user_queue_end(&items, 1), 4);
    }

    #[test]
    fn queue_without_user_added_songs_has_two_sections() {
        let items = vec![item("a", Origin::Source), item("b", Origin::Source)];
        let entries = queue_entries(&items, Some(0), false);
        assert_eq!(entries[0].section, Some("再生中"));
        assert_eq!(entries[1].section, Some("次に流れる曲"));
        assert!(entries[1].flow_header);
        // 最後の曲ならキューは今の曲だけ
        assert_eq!(queue_entries(&items, Some(1), false).len(), 1);
    }

    #[test]
    fn history_lists_played_songs_newest_first() {
        let items = vec![item("a", Origin::Source), item("b", Origin::Source), item("c", Origin::Source)];
        let ids: Vec<String> = queue_entries(&items, Some(2), true).into_iter().map(|e| e.item_id).collect();
        assert_eq!(ids, vec!["b", "a"]);
        assert!(queue_entries(&items, Some(0), true).is_empty());
        assert!(queue_entries(&[], None, false).is_empty());
    }

    #[test]
    fn volume_icon_has_four_steps() {
        assert_eq!(volume_icon(0.0), "audio-volume-muted-symbolic");
        assert_eq!(volume_icon(0.2), "audio-volume-low-symbolic");
        assert_eq!(volume_icon(0.5), "audio-volume-medium-symbolic");
        assert_eq!(volume_icon(0.9), "audio-volume-high-symbolic");
    }
}
