//! 中央のページで使う部品（Mac の TrackRow・TrackHeader・MediaCard・ShelfView・Chip と同じ）:
//! 曲の一覧の行（# / タイトル / アルバム / 時間。ダブルクリックで再生、1回のクリックで選ぶ、右クリックと「…」で曲のメニュー）、
//! 棚（見出しと左右の送り）、カード（乗せると再生ボタン）、チップ、ページの頭の絵（高く評価した曲・ダウンロード・新しいエピソード）。

use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;

use adw::prelude::*;
use wave_core::music::models::{HomeItem, Song};

use super::images;
use super::shell::App;
use super::song_menu::Removal;
use crate::runtime;

const STYLE: &str = "
.track-list { background: transparent; }
.track-list > row { border-radius: 6px; padding: 0 8px; }
.track-list > row:selected { background-color: alpha(currentColor, 0.12); color: inherit; }
.track-list > row:hover { background-color: alpha(currentColor, 0.06); }
.track-list > row:selected:hover { background-color: alpha(currentColor, 0.14); }
.track-header { padding: 0 8px 6px 8px; border-bottom: 1px solid alpha(currentColor, 0.12); }
.accent-text { color: @accent_color; }
.live-badge { background-color: #d93025; color: white; border-radius: 4px; padding: 1px 6px; font-size: 9px; font-weight: 800; }
.chip { border-radius: 9999px; padding: 4px 12px; min-height: 0; background-color: alpha(currentColor, 0.08); box-shadow: none; }
.chip:hover { background-color: alpha(currentColor, 0.14); }
.chip.on { background-color: @accent_bg_color; color: @accent_fg_color; }
.media-card { border-radius: 8px; padding: 8px; }
.media-card:hover { background-color: alpha(currentColor, 0.07); }
.play-fab { min-width: 44px; min-height: 44px; padding: 0; box-shadow: 0 2px 6px alpha(black, 0.35); }
.recent-tile { border-radius: 6px; background-color: alpha(currentColor, 0.07); }
.recent-tile:hover { background-color: alpha(currentColor, 0.12); }
.mood-tile { border-radius: 8px; padding: 16px; }
.mood-tile label { color: white; font-weight: 800; font-size: 17px; }
.top-card { border-radius: 8px; padding: 20px; background-color: alpha(currentColor, 0.06); }
.top-card:hover { background-color: alpha(currentColor, 0.1); }
.history-tile { border-radius: 6px; padding: 8px; background-color: alpha(currentColor, 0.05); }
.history-tile:hover { background-color: alpha(currentColor, 0.1); }
.page-title { font-size: 40px; font-weight: 800; }
.cover-liked { background-image: linear-gradient(135deg, #450af5, #c4efd9); border-radius: 6px; color: white; }
.cover-downloads { background-image: linear-gradient(135deg, #1e3264, #1db954); border-radius: 6px; color: white; }
.cover-episodes { background-image: linear-gradient(135deg, #006450, #2e77d0); border-radius: 6px; color: white; }
.cover-query { background-color: alpha(currentColor, 0.08); border-radius: 6px; }
.link-text { font-weight: 700; }
.link-text:hover { text-decoration: underline; }
.dim-row { opacity: 0.55; }
";

thread_local! {
    static STYLED: Cell<bool> = const { Cell::new(false) };
    /// 高く評価した曲の ID（行の ✓ に使う。初めて一覧を出すときに読む）。
    static LIKED: RefCell<Option<HashSet<String>>> = const { RefCell::new(None) };
    static LIKED_LOADING: Cell<bool> = const { Cell::new(false) };
    /// 評価が変わったら呼ぶもの（部品が消えたら外す）。
    static LIKED_WATCHERS: RefCell<Vec<Box<dyn Fn() -> bool>>> = RefCell::new(Vec::new());
}

/// この部品の見た目を画面に足す（1度だけ）。
pub fn ensure_style() {
    if STYLED.with(|s| s.replace(true)) {
        return;
    }
    let css = gtk::CssProvider::new();
    css.load_from_string(STYLE);
    if let Some(display) = gtk::gdk::Display::default() {
        gtk::style_context_add_provider_for_display(&display, &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
    }
}

// MARK: 高く評価した曲（行の ✓）

/// 高く評価した曲か（まだ読んでいなければ false）。
pub fn is_liked(video_id: &str) -> bool {
    LIKED.with(|l| l.borrow().as_ref().is_some_and(|set| set.contains(video_id)))
}

/// 高く評価した曲の一覧を読んであれば、その曲を高く評価しているか。
pub fn liked_known(video_id: &str) -> Option<bool> {
    LIKED.with(|l| l.borrow().as_ref().map(|set| set.contains(video_id)))
}

/// 評価を変えた（曲のメニュー・下の帯の評価から）。行の ✓ を出し直す。
pub fn set_liked(video_id: &str, liked: bool) {
    LIKED.with(|l| {
        let mut l = l.borrow_mut();
        let set = l.get_or_insert_with(HashSet::new);
        if liked { set.insert(video_id.to_string()) } else { set.remove(video_id) };
    });
    notify_liked();
}

fn notify_liked() {
    let watchers = LIKED_WATCHERS.with(|w| std::mem::take(&mut *w.borrow_mut()));
    let kept: Vec<_> = watchers.into_iter().filter(|w| w()).collect();
    LIKED_WATCHERS.with(|w| {
        let mut w = w.borrow_mut();
        let added = std::mem::take(&mut *w);
        *w = kept;
        w.extend(added);
    });
}

fn watch_liked<W: ObjectType>(widget: &W, f: impl Fn(&W) + 'static) {
    let weak = gtk::glib::object::WeakRef::new();
    weak.set(Some(widget));
    f(widget);
    LIKED_WATCHERS.with(|w| {
        w.borrow_mut().push(Box::new(move || match weak.upgrade() {
            Some(widget) => {
                f(&widget);
                true
            }
            None => false,
        }))
    });
}

/// 高く評価した曲を読む（ログイン中で、まだ読んでいなければ）。
fn load_liked(app: &Rc<App>) {
    if !app.repo.client().is_logged_in() || LIKED.with(|l| l.borrow().is_some()) || LIKED_LOADING.with(|l| l.replace(true)) {
        return;
    }
    let repo = app.repo.clone();
    gtk::glib::spawn_future_local(async move {
        let result = runtime::run(async move { repo.liked_songs().await }).await;
        LIKED_LOADING.with(|l| l.set(false));
        if let Ok((songs, _)) = result {
            LIKED.with(|l| *l.borrow_mut() = Some(songs.into_iter().map(|s| s.video_id).collect()));
            notify_liked();
        }
    });
}

/// ログアウト・別のアカウントでログインしたとき、覚えた評価を捨てる。
pub fn forget_liked() {
    LIKED.with(|l| *l.borrow_mut() = None);
}

// MARK: 曲の一覧

/// 一覧の1行（番号は一覧の元の並びの番号）。
pub struct Row {
    pub number: usize,
    pub song: Song,
    pub removal: Option<Removal>,
}

impl Row {
    pub fn numbered(songs: Vec<Song>) -> Vec<Row> {
        songs.into_iter().enumerate().map(|(i, song)| Row { number: i + 1, song, removal: None }).collect()
    }
}

/// 一覧の列（アルバムのページでは画像とアルバムの列を隠す）。
#[derive(Clone, Copy)]
pub struct Columns {
    pub artwork: bool,
    pub album: bool,
}

impl Columns {
    pub const FULL: Columns = Columns { artwork: true, album: true };
    /// 狭いところ（検索の「すべて」）: アルバムの列を出さない。
    pub const SHORT: Columns = Columns { artwork: true, album: false };
    /// アルバムのページ: 画像もアルバムも出さない。
    pub const ALBUM: Columns = Columns { artwork: false, album: false };
}

/// 一覧の上の見出しの行（# / タイトル / アルバム / 時間）。
pub fn header(columns: Columns) -> gtk::Widget {
    ensure_style();
    let row = gtk::Box::builder().spacing(12).css_classes(["track-header", "caption", "dim-label"]).build();
    row.append(&gtk::Label::builder().label("#").xalign(1.0).width_request(32).build());
    row.append(&gtk::Label::builder().label("タイトル").xalign(0.0).hexpand(true).build());
    if columns.album {
        row.append(&gtk::Label::builder().label("アルバム").xalign(0.0).hexpand(true).build());
    }
    let clock = gtk::Image::builder().icon_name("alarm-symbolic").halign(gtk::Align::End).width_request(96).build();
    row.append(&clock);
    row.append(&gtk::Box::builder().width_request(36).build());
    row.upcast()
}

/// 曲の一覧。ダブルクリック（か選んで Return）で on_play(行の位置)。1回のクリックは選ぶだけ。
pub fn list(app: &Rc<App>, rows: Vec<Row>, columns: Columns, on_play: Rc<dyn Fn(usize)>) -> gtk::ListBox {
    ensure_style();
    load_liked(app);
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::Single).activate_on_single_click(false).css_classes(["track-list"]).build();
    append(app, &list, rows, columns);
    list.connect_row_activated(move |_, row| on_play(row.index().max(0) as usize));
    list
}

/// 一覧の後ろに行を足す（続きのページ）。
pub fn append(app: &Rc<App>, list: &gtk::ListBox, rows: Vec<Row>, columns: Columns) {
    for row in rows {
        list.append(&track_row(app, row, columns));
    }
}

/// 1曲の行。
pub fn track_row(app: &Rc<App>, row: Row, columns: Columns) -> gtk::ListBoxRow {
    let Row { number, song, removal } = row;
    let line = gtk::Box::builder().spacing(12).height_request(56).build();
    // 番号（今の曲はスピーカーの絵とアクセントの色）
    let number_label = gtk::Label::builder().label(number.to_string()).xalign(1.0).css_classes(["dim-label", "numeric"]).build();
    let speaker = gtk::Image::builder().icon_name("audio-volume-high-symbolic").pixel_size(14).halign(gtk::Align::End).css_classes(["accent-text"]).visible(false).build();
    // 中の部品の広がりを外へ伝えない（伝えると、題の列が狭くなる）
    let number_box = gtk::Box::builder().width_request(32).halign(gtk::Align::End).hexpand(false).build();
    number_box.append(&number_label);
    number_box.append(&speaker);
    number_label.set_hexpand(true);
    speaker.set_hexpand(true);
    line.append(&number_box);
    if columns.artwork {
        let art = images::square(40);
        art.load(song.thumbnail_url.as_deref(), 80);
        line.append(&art.root);
    }
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).hexpand(true).spacing(2).build();
    let title = gtk::Label::builder().label(&song.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).hexpand(true).build();
    text.append(&title);
    text.append(&gtk::Label::builder().label(song.artists_text()).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["caption", "dim-label"]).build());
    line.append(&text);
    if columns.album {
        let album = song.album.as_ref().map(|a| a.title.clone()).unwrap_or_default();
        line.append(&gtk::Label::builder().label(album).xalign(0.0).hexpand(true).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["dim-label"]).build());
    }
    // 右: 取った印・高く評価した ✓・ライブの印か長さ
    let trailing = gtk::Box::builder().spacing(8).width_request(96).halign(gtk::Align::End).hexpand(false).build();
    let spacer = gtk::Box::builder().hexpand(true).build();
    trailing.append(&spacer);
    let mark = gtk::Image::builder().pixel_size(14).css_classes(["dim-label"]).visible(false).build();
    {
        let (downloads, id) = (app.downloads.clone(), song.video_id.clone());
        app.downloads.watch(&mark, move |mark| {
            let (icon, tip) = match downloads.state(&id) {
                crate::downloads::SongState::Done => (Some("folder-download-symbolic"), "ダウンロード済み"),
                crate::downloads::SongState::Downloading => (Some("content-loading-symbolic"), "ダウンロード中"),
                crate::downloads::SongState::Waiting => (Some("content-loading-symbolic"), "ダウンロード待ち"),
                crate::downloads::SongState::None => (None, ""),
            };
            mark.set_visible(icon.is_some());
            if let Some(icon) = icon {
                mark.set_icon_name(Some(icon));
                mark.set_tooltip_text(Some(tip));
            }
        });
    }
    trailing.append(&mark);
    let liked = gtk::Image::builder().icon_name("emblem-ok-symbolic").pixel_size(14).tooltip_text("高く評価済み").css_classes(["accent-text"]).visible(false).build();
    {
        let id = song.video_id.clone();
        watch_liked(&liked, move |image| image.set_visible(is_liked(&id)));
    }
    trailing.append(&liked);
    if song.is_live {
        trailing.append(&gtk::Label::builder().label("ライブ").valign(gtk::Align::Center).css_classes(["live-badge"]).build());
    } else {
        trailing.append(&gtk::Label::builder().label(song.duration_seconds.map(super::pages::format_seconds).unwrap_or_default()).css_classes(["dim-label", "numeric"]).build());
    }
    line.append(&trailing);
    let item = gtk::ListBoxRow::builder().child(&line).build();
    let menu = super::song_menu::button(app, &song, removal, Some(item.upcast_ref()));
    menu.set_opacity(0.0);
    line.append(&menu);
    // 「…」は乗せたときか選んだとき（メニューを開いている間も）だけ見せる
    let show_menu = {
        let menu = menu.downgrade();
        move |row: &gtk::ListBoxRow| {
            let Some(menu) = menu.upgrade() else { return };
            let flags = row.state_flags();
            let on = flags.contains(gtk::StateFlags::PRELIGHT) || flags.contains(gtk::StateFlags::SELECTED) || menu.is_active();
            menu.set_opacity(if on { 1.0 } else { 0.0 });
        }
    };
    {
        let show_menu = show_menu.clone();
        item.connect_state_flags_changed(move |row, _| show_menu(row));
    }
    {
        let weak = item.downgrade();
        menu.connect_active_notify(move |_| {
            if let Some(row) = weak.upgrade() {
                show_menu(&row);
            }
        });
    }
    // 今の曲は番号の代わりにスピーカーの絵、題をアクセントの色に
    {
        let (playback, id) = (app.playback.clone(), song.video_id.clone());
        app.playback.watch(&title, move |title| {
            let current = playback.current().is_some_and(|s| s.video_id == id);
            number_label.set_visible(!current);
            speaker.set_visible(current);
            if current { title.add_css_class("accent-text") } else { title.remove_css_class("accent-text") }
        });
    }
    item
}

// MARK: チップ

/// 絞り込みのチップ（入っているときはアクセントの色）。
pub fn chip(label: &str, on: bool) -> gtk::Button {
    ensure_style();
    let button = gtk::Button::builder().label(label).css_classes(["chip", "caption-heading"]).build();
    set_chip(&button, on);
    button
}

pub fn set_chip(button: &gtk::Button, on: bool) {
    if on { button.add_css_class("on") } else { button.remove_css_class("on") }
}

/// 横に並ぶチップ（収まらなければ横に送る）。
pub fn chip_row() -> (gtk::ScrolledWindow, gtk::Box) {
    let row = gtk::Box::builder().spacing(8).build();
    let scroller = gtk::ScrolledWindow::builder().child(&row).vscrollbar_policy(gtk::PolicyType::Never).hscrollbar_policy(gtk::PolicyType::External).propagate_natural_height(true).build();
    (scroller, row)
}

// MARK: カード

/// 棚のカードの絵の大きさ。
pub const CARD_SIZE: i32 = 160;

/// カードの補足（アーティストは「アーティスト」）。
fn card_subtitle(item: &HomeItem) -> String {
    match item {
        HomeItem::Artist(_) => "アーティスト".into(),
        HomeItem::Song(s) => s.artists_text(),
        other => other.subtitle().unwrap_or_default(),
    }
}

/// 棚のカード（画像・名前1行・補足2行）。乗せると再生ボタンが出て、ページを開かずに流す。曲のカードは右クリックで曲のメニュー。
/// 押すとアルバム・プレイリスト・アーティストは開き、曲は1曲＋ラジオで流す（Linux には曲の画面が無い）。
/// on_open は開く・流す前に呼ぶ（検索から開いたものを履歴に残す）。
pub fn card(app: &Rc<App>, item: HomeItem, on_open: Option<Rc<dyn Fn()>>) -> gtk::Widget {
    ensure_style();
    let picture = images::square(CARD_SIZE);
    if matches!(item, HomeItem::Artist(_)) {
        picture.add_css_class("round");
    }
    picture.load(item.thumbnail_url(), CARD_SIZE as u32 * 2);
    let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").halign(gtk::Align::End).valign(gtk::Align::End).margin_end(6).margin_bottom(6).opacity(0.0).css_classes(["circular", "suggested-action", "play-fab"]).build();
    let overlay = gtk::Overlay::builder().child(&picture.root).build();
    overlay.add_overlay(&play);
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).width_request(CARD_SIZE).css_classes(["media-card"]).build();
    column.append(&overlay);
    column.append(&gtk::Label::builder().label(item.title()).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).width_request(CARD_SIZE).css_classes(["heading"]).build());
    let subtitle = card_subtitle(&item);
    column.append(&gtk::Label::builder().label(&subtitle).xalign(0.0).wrap(true).wrap_mode(gtk::pango::WrapMode::WordChar).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).width_request(CARD_SIZE).css_classes(["caption", "dim-label"]).build());
    column.set_cursor_from_name(Some("pointer"));
    let motion = gtk::EventControllerMotion::new();
    {
        let play = play.clone();
        motion.connect_enter(move |_, _, _| play.set_opacity(1.0));
    }
    {
        let play = play.clone();
        motion.connect_leave(move |_| play.set_opacity(0.0));
    }
    column.add_controller(motion);
    let item = Rc::new(item);
    {
        let (app, item, on_open) = (app.clone(), item.clone(), on_open.clone());
        play.connect_clicked(move |_| {
            if let Some(f) = &on_open {
                f();
            }
            play_item(&app, &item);
        });
    }
    let click = gtk::GestureClick::builder().button(1).build();
    {
        let (app, item) = (app.clone(), item.clone());
        click.connect_released(move |gesture, n, _, _| {
            if n != 1 {
                return;
            }
            gesture.set_state(gtk::EventSequenceState::Claimed);
            if let Some(f) = &on_open {
                f();
            }
            open_item(&app, &item);
        });
    }
    column.add_controller(click);
    if let HomeItem::Song(song) = &*item {
        let right = gtk::GestureClick::builder().button(3).build();
        let (app, song, weak) = (app.clone(), song.clone(), column.downgrade());
        right.connect_pressed(move |gesture, _, x, y| {
            gesture.set_state(gtk::EventSequenceState::Claimed);
            if let Some(column) = weak.upgrade() {
                super::song_menu::popup(&app, &song, column.upcast_ref(), x, y);
            }
        });
        column.add_controller(right);
    }
    column.upcast()
}

/// カードを押した: アルバム・プレイリスト・アーティストは開き、曲は1曲＋ラジオで流す。
pub fn open_item(app: &Rc<App>, item: &HomeItem) {
    match item {
        HomeItem::Song(song) => app.playback.play_list(vec![song.clone()], 0),
        HomeItem::Playlist(p) => app.push(super::pages::playlist(app, &p.playlist_id, &p.title)),
        HomeItem::Album(a) => app.push(super::pages::album(app, &a.browse_id, &a.title)),
        HomeItem::Artist(a) => app.push(super::pages::artist(app, &a.browse_id, &a.name)),
    }
}

/// カードの再生ボタン: ページを開かずに流す（Mac の Nav.play と同じ）。プレイリストは最初のページが届いたら鳴らし始め、残りは後から足す。
pub fn play_item(app: &Rc<App>, item: &HomeItem) {
    let (app, item) = (app.clone(), item.clone());
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        match item {
            HomeItem::Song(song) => app.playback.play_list(vec![song], 0),
            HomeItem::Album(a) => {
                let id = a.browse_id.clone();
                match runtime::run(async move { repo.album(&id).await }).await {
                    Ok(page) if !page.songs.is_empty() => app.playback.play_list_from(page.songs, 0, Some(a.browse_id)),
                    _ => app.toast("アルバムを読み込めませんでした"),
                }
            }
            HomeItem::Playlist(p) => play_playlist(&app, &p.playlist_id, false).await,
            HomeItem::Artist(a) => {
                let id = a.browse_id.clone();
                match runtime::run(async move { repo.artist(&id).await }).await {
                    Ok(page) if !page.top_songs.is_empty() => app.playback.play_artist(&a.browse_id, page.top_songs, 0, false, page.all_songs_playlist_id),
                    _ => app.toast("アーティストの曲を読み込めませんでした"),
                }
            }
        }
    });
}

/// プレイリストを頭から流す（最初のページで鳴らし始め、残りは読めたら足す）。
pub async fn play_playlist(app: &Rc<App>, playlist_id: &str, shuffle: bool) {
    let (repo, id) = (app.repo.clone(), playlist_id.to_string());
    let Ok(first) = runtime::run(async move { repo.playlist(&id, None).await }).await else {
        app.toast("プレイリストを読み込めませんでした");
        return;
    };
    if first.songs.is_empty() {
        app.toast("プレイリストを読み込めませんでした");
        return;
    }
    let more = first.continuation.is_some();
    app.playback.play_list_pending(first.songs.clone(), 0, Some(playlist_id.to_string()), more);
    if shuffle {
        app.playback.set_shuffle(true);
    }
    if !more {
        return;
    }
    let (repo, id) = (app.repo.clone(), playlist_id.to_string());
    let all = runtime::run(async move { crate::playback::full_playlist(&repo, &id).await }).await;
    if app.playback.is_playing_source(playlist_id) {
        app.playback.append_from(all);
    }
}

// MARK: 棚

/// 横に流れる棚（見出しと、続きがある向きだけの左右の送りボタン）。
pub fn shelf(app: &Rc<App>, title: &str, strapline: Option<&str>, items: Vec<HomeItem>) -> gtk::Widget {
    shelf_with(app, title, strapline, items.into_iter().map(|item| card(app, item, None)).collect())
}

/// 好きなカードを並べた棚。
pub fn shelf_with(_app: &Rc<App>, title: &str, strapline: Option<&str>, cards: Vec<gtk::Widget>) -> gtk::Widget {
    ensure_style();
    let column = gtk::Box::new(gtk::Orientation::Vertical, 4);
    let head = gtk::Box::builder().spacing(4).margin_start(8).margin_end(8).build();
    let titles = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).valign(gtk::Align::End).build();
    if let Some(strapline) = strapline.filter(|s| !s.is_empty()) {
        titles.append(&gtk::Label::builder().label(strapline).xalign(0.0).css_classes(["caption", "dim-label"]).build());
    }
    titles.append(&gtk::Label::builder().label(title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["title-2"]).build());
    head.append(&titles);
    let previous = gtk::Button::builder().icon_name("go-previous-symbolic").tooltip_text("前へ").valign(gtk::Align::End).opacity(0.0).sensitive(false).css_classes(["flat", "circular"]).build();
    let next = gtk::Button::builder().icon_name("go-next-symbolic").tooltip_text("次へ").valign(gtk::Align::End).opacity(0.0).sensitive(false).css_classes(["flat", "circular"]).build();
    head.append(&previous);
    head.append(&next);
    column.append(&head);
    let row = gtk::Box::builder().spacing(0).valign(gtk::Align::Start).build();
    for card in cards {
        row.append(&card);
    }
    // 横に流れる棚は、中身の高さをそのまま使う（測らないと2行目の補足が切れる）
    let scroller = gtk::ScrolledWindow::builder().child(&row).vscrollbar_policy(gtk::PolicyType::Never).hscrollbar_policy(gtk::PolicyType::External).propagate_natural_height(true).build();
    column.append(&scroller);
    let adjustment = scroller.hadjustment();
    let update = {
        let (previous, next) = (previous.clone(), next.clone());
        move |a: &gtk::Adjustment| {
            let back = a.value() > 1.0;
            let forward = a.value() + a.page_size() < a.upper() - 1.0;
            previous.set_opacity(if back { 1.0 } else { 0.0 });
            previous.set_sensitive(back);
            next.set_opacity(if forward { 1.0 } else { 0.0 });
            next.set_sensitive(forward);
        }
    };
    {
        let update = update.clone();
        adjustment.connect_value_changed(move |a| update(a));
    }
    adjustment.connect_changed(move |a| update(a));
    for (button, direction) in [(&previous, -1.0), (&next, 1.0)] {
        let (adjustment, scroller) = (adjustment.clone(), scroller.clone());
        button.connect_clicked(move |_| {
            // 4枚ずつ送る（窓が狭ければ見えている幅だけ）
            let step = ((CARD_SIZE + 16) as f64 * 4.0).min(adjustment.page_size().max(1.0));
            let target = (adjustment.value() + direction * step).clamp(adjustment.lower(), (adjustment.upper() - adjustment.page_size()).max(0.0));
            let a = adjustment.clone();
            let animation = adw::TimedAnimation::new(&scroller, adjustment.value(), target, 300, adw::CallbackAnimationTarget::new(move |v| a.set_value(v)));
            animation.set_easing(adw::Easing::EaseOutCubic);
            animation.play();
        });
    }
    column.upcast()
}

/// カードの格子（検索のアルバム・アーティスト・番組）。
pub fn card_grid(cards: Vec<gtk::Widget>) -> gtk::FlowBox {
    let grid = gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).homogeneous(true).min_children_per_line(2).max_children_per_line(12).column_spacing(0).row_spacing(8).valign(gtk::Align::Start).build();
    for card in cards {
        grid.append(&card);
    }
    grid
}

// MARK: 頭の絵

/// 絵の代わりの地と印（高く評価した曲・ダウンロード・新しいエピソード。Mac の LikedCover・DownloadsCover・NewEpisodesCover）。
pub fn cover(kind: &str, size: i32) -> gtk::Widget {
    ensure_style();
    let (class, icon) = match kind {
        "liked" => ("cover-liked", "emblem-favorite-symbolic"),
        "downloads" => ("cover-downloads", "folder-download-symbolic"),
        _ => ("cover-episodes", "audio-input-microphone-symbolic"),
    };
    let image = gtk::Image::builder().icon_name(icon).pixel_size(size * 2 / 5).halign(gtk::Align::Center).valign(gtk::Align::Center).hexpand(true).vexpand(true).build();
    // 中の絵の広がりを外へ伝えない（伝えると、並べた行の文字が右へ押される）
    let frame = gtk::Box::builder().width_request(size).height_request(size).halign(gtk::Align::Start).valign(gtk::Align::Center).hexpand(false).vexpand(false).css_classes([class]).build();
    frame.append(&image);
    frame.upcast()
}

/// ページの大きな題。
pub fn page_title(text: &str) -> gtk::Label {
    ensure_style();
    gtk::Label::builder().label(text).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["page-title"]).build()
}

/// 押せる文字（アルバムのアーティスト名など）。
pub fn link(text: &str, on_click: impl Fn() + 'static) -> gtk::Label {
    ensure_style();
    let label = gtk::Label::builder().label(text).xalign(0.0).css_classes(["link-text"]).build();
    label.set_cursor_from_name(Some("pointer"));
    let click = gtk::GestureClick::new();
    click.connect_released(move |_, _, _, _| on_click());
    label.add_controller(click);
    label
}

/// 大きな丸い再生ボタン。playing なら一時停止の絵。
pub fn set_play_icon(button: &gtk::Button, playing: bool) {
    button.set_icon_name(if playing { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" });
    button.set_tooltip_text(Some(if playing { "一時停止" } else { "再生" }));
}

/// 下の端に近づいたら f を呼ぶ（続きのページを読む。中身が短くて送れないときも呼ぶ）。
pub fn on_near_bottom(scroller: &gtk::ScrolledWindow, f: impl Fn() + 'static) {
    let f = Rc::new(f);
    let check = move |a: &gtk::Adjustment| {
        if a.upper() > 0.0 && a.value() + a.page_size() >= a.upper() - 600.0 {
            f();
        }
    };
    let adjustment = scroller.vadjustment();
    {
        let check = check.clone();
        adjustment.connect_value_changed(move |a| check(a));
    }
    adjustment.connect_changed(move |a| check(a));
}
