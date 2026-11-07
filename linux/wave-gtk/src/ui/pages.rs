//! 中央に積むページ（Mac の Pages と同じ並び）: ホーム・気分・ジャンル・プレイリストとアルバム・アーティスト・最近再生した曲・ダウンロード。
//! 検索のページは search.rs、ポッドキャストは podcasts.rs、曲の行・棚・カードは track.rs。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use wave_core::music::collection::{self, SortKey};
use wave_core::music::models::{HomeChip, HomeItem, HomeShelf, Song};
use wave_core::music::repository::LIKED_SONGS;

use super::images;
use super::shell::App;
use super::track::{self, Columns, Row};
use crate::runtime;

/// 読み込み中のページの中身。
fn spinner() -> gtk::Widget {
    adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).valign(gtk::Align::Center).vexpand(true).build().upcast()
}

fn failure(text: &str) -> gtk::Widget {
    adw::StatusPage::builder().icon_name("network-error-symbolic").title(text).vexpand(true).build().upcast()
}

fn page(title: &str, tag: &str, child: &impl IsA<gtk::Widget>) -> adw::NavigationPage {
    adw::NavigationPage::builder().title(title).tag(tag).child(child).build()
}

/// 中身を後から差し替える入れ物（読み込みの間は回る印を出す）。
fn holder() -> adw::Bin {
    adw::Bin::builder().child(&spinner()).build()
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

pub fn format_seconds(seconds: u32) -> String {
    let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60);
    if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m}:{s:02}") }
}

/// 縦に流れるページの入れ物（余白と幅の上限）。
fn scrolling_column(column: &gtk::Box) -> gtk::ScrolledWindow {
    gtk::ScrolledWindow::builder().child(&clamp(column)).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build()
}

fn page_column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).margin_top(20).margin_bottom(24).margin_start(20).margin_end(20).build()
}

/// 広い窓で一覧が横に伸びすぎないようにする。
fn clamp(child: &impl IsA<gtk::Widget>) -> adw::Clamp {
    adw::Clamp::builder().maximum_size(1200).tightening_threshold(900).child(child).build()
}

fn section_title(text: &str) -> gtk::Label {
    gtk::Label::builder().label(text).xalign(0.0).margin_top(12).css_classes(["title-2"]).build()
}

// MARK: ホーム

/// ホームの状態（Mac の HomeModel と同じ読み方）。
struct Home {
    app: Rc<App>,
    chips: gtk::Box,
    recent: adw::Bin,
    releases: adw::Bin,
    new_releases: adw::Bin,
    shelves: gtk::Box,
    footer: adw::Bin,
    state: RefCell<HomeState>,
}

#[derive(Default)]
struct HomeState {
    chips: Vec<HomeChip>,
    selected: Option<HomeChip>,
    continuation: Option<String>,
    seen: std::collections::HashSet<String>,
    count: usize,
    loading: bool,
    request: u64,
    /// YouTube Music の新作（「すべて」のときだけ読む。ホームに同じ新作の棚が来ていれば出さない）。
    new_releases: Vec<wave_core::music::models::AlbumSummary>,
    /// 最初のページの棚（YouTube Music の新作の棚と重なるかを見る）。
    first_shelves: Vec<HomeShelf>,
    failed: bool,
}

/// ホーム: 一番上にチップ（すべて／各チップ。もう一度押すと外れ、棚を読み直す）、ログイン中かつ「すべて」なら最近聴いたもの（4列で最大8件）、
/// 登録したアーティストの新作、YouTube Music の新作、ホームの棚。最初に棚が8つになるまで読み、下の端に近づくとさらに読む。
pub fn home(app: &Rc<App>) -> adw::NavigationPage {
    track::ensure_style();
    let (chip_scroller, chips) = track::chip_row();
    chip_scroller.set_margin_start(8);
    chip_scroller.set_margin_end(8);
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(24).margin_top(16).margin_bottom(32).margin_start(8).margin_end(8).build();
    column.append(&chip_scroller);
    let home = Rc::new(Home {
        app: app.clone(),
        chips,
        recent: adw::Bin::new(),
        releases: adw::Bin::new(),
        new_releases: adw::Bin::new(),
        shelves: gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(24).build(),
        footer: adw::Bin::new(),
        state: RefCell::default(),
    });
    for widget in [home.recent.upcast_ref::<gtk::Widget>(), home.releases.upcast_ref(), home.new_releases.upcast_ref(), home.shelves.upcast_ref(), home.footer.upcast_ref()] {
        column.append(widget);
    }
    let scroller = gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
    {
        let weak = Rc::downgrade(&home);
        track::on_near_bottom(&scroller, move || {
            if let Some(home) = weak.upgrade() {
                home.load_more();
            }
        });
    }
    home.render_chips();
    home.reload();
    // ページが消えるまで状態を持つ
    let keep = home.clone();
    let page = page("ホーム", "home", &scroller);
    page.connect_destroy(move |_| {
        let _ = &keep;
    });
    page
}

impl Home {
    fn render_chips(self: &Rc<Self>) {
        while let Some(child) = self.chips.first_child() {
            self.chips.remove(&child);
        }
        let (chips, selected) = {
            let st = self.state.borrow();
            (st.chips.clone(), st.selected.clone())
        };
        let all = track::chip("すべて", selected.is_none());
        {
            let weak = Rc::downgrade(self);
            all.connect_clicked(move |_| {
                if let Some(home) = weak.upgrade() {
                    home.select(None);
                }
            });
        }
        self.chips.append(&all);
        for chip in chips {
            let button = track::chip(&chip.title, selected.as_ref() == Some(&chip));
            let weak = Rc::downgrade(self);
            button.connect_clicked(move |_| {
                if let Some(home) = weak.upgrade() {
                    home.select(Some(chip.clone()));
                }
            });
            self.chips.append(&button);
        }
    }

    /// チップを選ぶ（同じものをもう一度押すと外す）。
    fn select(self: &Rc<Self>, chip: Option<HomeChip>) {
        {
            let mut st = self.state.borrow_mut();
            st.selected = if st.selected == chip { None } else { chip };
        }
        self.render_chips();
        self.reload();
    }

    fn reload(self: &Rc<Self>) {
        let (request, selected) = {
            let mut st = self.state.borrow_mut();
            st.request += 1;
            st.continuation = None;
            st.seen.clear();
            st.count = 0;
            st.loading = false;
            st.failed = false;
            st.new_releases.clear();
            st.first_shelves.clear();
            (st.request, st.selected.clone())
        };
        while let Some(child) = self.shelves.first_child() {
            self.shelves.remove(&child);
        }
        self.recent.set_child(None::<&gtk::Widget>);
        self.new_releases.set_child(None::<&gtk::Widget>);
        self.footer.set_child(Some(&spinner()));
        // 登録したアーティストの新作（「すべて」のときだけ。アーティストごとに2件まで交互に、見つけて7日は NEW）
        self.releases.set_child(None::<&gtk::Widget>);
        if selected.is_none() {
            let now = now_ms();
            let items: Vec<HomeItem> = wave_core::music::releases::diversified(&self.app.releases.recent(), 2)
                .into_iter()
                .take(wave_core::music::releases::NEW_RELEASES_COUNT)
                .filter_map(|r| wave_core::music::releases::release_item(&r, now))
                .collect();
            if !items.is_empty() {
                self.releases.set_child(Some(&track::shelf(&self.app, "登録したアーティストの新作", None, items)));
            }
            self.load_recent(request);
            self.load_new_releases(request);
        }
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            // 最初は棚が8つ以上になるまで続きを読む（Mac・Android と同じ）
            for _ in 0..4 {
                if !this.load_page(request).await {
                    break;
                }
                let st = this.state.borrow();
                if st.request != request || st.count >= 8 || st.continuation.is_none() {
                    break;
                }
            }
            this.update_footer(request);
        });
    }

    fn load_recent(self: &Rc<Self>, request: u64) {
        if !self.app.repo.client().is_logged_in() {
            return;
        }
        let (repo, weak) = (self.app.repo.clone(), Rc::downgrade(self));
        gtk::glib::spawn_future_local(async move {
            let items = runtime::run(async move { repo.recent_activity().await }).await.unwrap_or_default();
            let Some(home) = weak.upgrade() else { return };
            if home.state.borrow().request != request || items.is_empty() {
                return;
            }
            let grid = gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).homogeneous(true).min_children_per_line(4).max_children_per_line(4).column_spacing(8).row_spacing(8).build();
            for item in items.into_iter().take(8) {
                grid.append(&recent_tile(&home.app, item));
            }
            home.recent.set_child(Some(&grid));
        });
    }

    fn load_new_releases(self: &Rc<Self>, request: u64) {
        let (repo, weak) = (self.app.repo.clone(), Rc::downgrade(self));
        gtk::glib::spawn_future_local(async move {
            let releases = runtime::run(async move { repo.new_releases().await }).await.unwrap_or_default();
            let Some(home) = weak.upgrade() else { return };
            if home.state.borrow().request != request {
                return;
            }
            home.state.borrow_mut().new_releases = releases;
            home.show_new_releases();
            home.update_footer(request);
        });
    }

    /// 新作の棚（ホームに同じ新作の棚が来ていれば出さない）。
    fn show_new_releases(&self) {
        let st = self.state.borrow();
        if st.new_releases.is_empty() || st.selected.is_some() || wave_core::music::releases::duplicates_new_releases(&st.new_releases, &st.first_shelves) {
            self.new_releases.set_child(None::<&gtk::Widget>);
            return;
        }
        if let Some(shelf) = wave_core::music::releases::new_releases_shelf("新作", st.new_releases.clone()) {
            self.new_releases.set_child(Some(&track::shelf(&self.app, &shelf.title, None, shelf.items)));
        }
    }

    /// 下の端に近づいた: 続きの棚を読む。
    fn load_more(self: &Rc<Self>) {
        let (request, ready) = {
            let st = self.state.borrow();
            (st.request, st.continuation.is_some() && !st.loading)
        };
        if !ready {
            return;
        }
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            this.load_page(request).await;
            this.update_footer(request);
        });
    }

    /// 1ページ読んで棚を足す。読めたら true。
    async fn load_page(self: &Rc<Self>, request: u64) -> bool {
        let (continuation, params) = {
            let mut st = self.state.borrow_mut();
            if st.loading {
                return false;
            }
            st.loading = true;
            let params = if st.continuation.is_none() { st.selected.as_ref().map(|c| c.params.clone()) } else { None };
            (st.continuation.clone(), params)
        };
        if continuation.is_some() {
            self.footer.set_child(Some(&spinner()));
        }
        let repo = self.app.repo.clone();
        let (c, p) = (continuation.clone(), params.clone());
        let mut result = runtime::run(async move { repo.home(c.as_deref(), p.as_deref()).await }).await;
        // 起動直後はほかの読み込みと重なって時間切れになることがあるので、通信の失敗は1回だけやり直す
        if matches!(result, Err(wave_core::innertube::Failure::Network)) && self.state.borrow().request == request {
            let repo = self.app.repo.clone();
            let (c, p) = (continuation.clone(), params.clone());
            result = runtime::run(async move { repo.home(c.as_deref(), p.as_deref()).await }).await;
        }
        let mut st = self.state.borrow_mut();
        st.loading = false;
        if st.request != request {
            return false;
        }
        let page = match result {
            Ok(page) => page,
            Err(e) => {
                eprintln!("home failed: {e}");
                if st.count == 0 {
                    st.failed = true;
                }
                return false;
            }
        };
        let first = continuation.is_none();
        if first && st.selected.is_none() && !page.chips.is_empty() && st.chips != page.chips {
            st.chips = page.chips.clone();
            drop(st);
            self.render_chips();
            st = self.state.borrow_mut();
        }
        if first {
            st.first_shelves = page.shelves.clone();
        }
        let mut fresh = Vec::new();
        for shelf in page.shelves {
            if st.seen.insert(wave_core::music::releases::shelf_key(&shelf)) {
                fresh.push(shelf);
            }
        }
        st.count += fresh.len();
        st.continuation = page.continuation;
        drop(st);
        for shelf in fresh {
            self.shelves.append(&track::shelf(&self.app, &shelf.title, shelf.strapline.as_deref(), shelf.items));
        }
        self.show_new_releases();
        true
    }

    /// 下の端: 読み込み中の印、読めなかったとき（新作も無いとき）は「もう一度読み込む」。
    fn update_footer(self: &Rc<Self>, request: u64) {
        let st = self.state.borrow();
        if st.request != request {
            return;
        }
        if st.loading {
            self.footer.set_child(Some(&spinner()));
        } else if st.failed && st.new_releases.is_empty() {
            let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).halign(gtk::Align::Center).margin_top(24).build();
            column.append(&gtk::Label::builder().label("ホームを読み込めませんでした").css_classes(["title-3"]).build());
            let retry = gtk::Button::builder().label("もう一度読み込む").halign(gtk::Align::Center).css_classes(["pill"]).build();
            let weak = Rc::downgrade(self);
            retry.connect_clicked(move |_| {
                if let Some(home) = weak.upgrade() {
                    home.reload();
                }
            });
            column.append(&retry);
            self.footer.set_child(Some(&column));
        } else {
            self.footer.set_child(None::<&gtk::Widget>);
        }
    }
}

/// 最近聴いたものの格子の1枚（画像と名前。押すと開く）。
fn recent_tile(app: &Rc<App>, item: HomeItem) -> gtk::Widget {
    let row = gtk::Box::builder().spacing(12).height_request(56).css_classes(["recent-tile"]).build();
    row.set_overflow(gtk::Overflow::Hidden);
    let art = images::square(56);
    if matches!(item, HomeItem::Artist(_)) {
        art.add_css_class("round");
    }
    art.load(item.thumbnail_url(), 112);
    row.append(&art.root);
    row.append(&gtk::Label::builder().label(item.title()).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).hexpand(true).margin_end(12).css_classes(["heading"]).build());
    row.set_cursor_from_name(Some("pointer"));
    let click = gtk::GestureClick::new();
    let app = app.clone();
    click.connect_released(move |_, _, _, _| track::open_item(&app, &item));
    row.add_controller(click);
    row.upcast()
}

// MARK: 気分・ジャンル

/// 気分・ジャンルのタイルは、検索のページの「すべてをブラウズ」にある（Mac と同じ）。
pub fn moods(app: &Rc<App>) -> adw::NavigationPage {
    super::search::page(app, None)
}

/// 気分・ジャンルの1つのページ（ホームと同じ棚の並び）。
pub fn mood_category(app: &Rc<App>, title: &str, params: &str) -> adw::NavigationPage {
    let bin = holder();
    let app = app.clone();
    let target = bin.clone();
    let params = params.to_string();
    let heading = title.to_string();
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let result = runtime::run(async move { repo.mood_category(&params).await }).await;
        let content: gtk::Widget = match result {
            Ok(home) if !home.shelves.is_empty() => {
                let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(24).margin_top(16).margin_bottom(32).margin_start(8).margin_end(8).build();
                let title = track::page_title(&heading);
                title.set_margin_start(8);
                column.append(&title);
                for shelf in home.shelves {
                    column.append(&track::shelf(&app, &shelf.title, shelf.strapline.as_deref(), shelf.items));
                }
                gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build().upcast()
            }
            Ok(_) => failure("このページには何もありませんでした"),
            Err(e) => failure(&format!("読めませんでした（{e}）")),
        };
        target.set_child(Some(&content));
    });
    page(title, "mood", &bin)
}

// MARK: 曲の一覧（ほかの画面から）

/// 曲の一覧（Claude の答えなど）。ダブルクリックで、その一覧をキューにしてその曲から鳴らす。
pub fn song_list(app: &Rc<App>, songs: Vec<Song>) -> gtk::ListBox {
    let shown = songs.clone();
    let app_c = app.clone();
    track::list(app, Row::numbered(songs), Columns::FULL, Rc::new(move |i| app_c.playback.play_list(shown.clone(), i)))
}

/// 検索のページ（search.rs）。
pub fn search(app: &Rc<App>, query: &str) -> adw::NavigationPage {
    super::search::page(app, Some(query))
}

/// 最近再生した曲（再生履歴。「今日」「昨日」などの区切りごと）。曲はダブルクリックでその1曲＋ラジオ（Mac と同じ）。
pub fn history(app: &Rc<App>) -> adw::NavigationPage {
    let bin = holder();
    let app = app.clone();
    let target = bin.clone();
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let result = runtime::run(async move { repo.history_sections().await }).await;
        let content: gtk::Widget = match result {
            Ok(sections) if sections.is_empty() => adw::StatusPage::builder().icon_name("document-open-recent-symbolic").title("再生履歴はまだありません").vexpand(true).build().upcast(),
            Ok(sections) => {
                let column = page_column();
                column.append(&track::page_title("最近再生した曲"));
                let mut number = 0;
                for (title, songs) in sections {
                    if let Some(title) = title {
                        column.append(&section_title(&title));
                    }
                    let rows: Vec<Row> = songs.iter().cloned().map(|song| {
                        number += 1;
                        Row { number, song, removal: None }
                    }).collect();
                    let app_c = app.clone();
                    column.append(&track::list(&app, rows, Columns::FULL, Rc::new(move |i| {
                        if let Some(song) = songs.get(i) {
                            app_c.playback.play_list(vec![song.clone()], 0);
                        }
                    })));
                }
                scrolling_column(&column).upcast()
            }
            Err(wave_core::innertube::Failure::NotLoggedIn) => failure("再生履歴を見るにはログインしてください"),
            Err(e) => failure(&format!("再生履歴を読み込めませんでした（{e}）")),
        };
        target.set_child(Some(&content));
    });
    page("最近再生した曲", "history", &bin)
}

// MARK: プレイリスト・アルバム

/// 何の一覧か。
#[derive(Clone)]
enum Source {
    Album,
    Playlist,
    /// 高く評価した曲のうち、そのアーティストのもの（名前）。
    LikedBy(String),
}

/// プレイリスト・アルバムのページの状態（Mac の CollectionModel と同じ）。
struct Collection {
    app: Rc<App>,
    id: String,
    source: Source,
    title: RefCell<String>,
    thumbnail: RefCell<Option<String>>,
    /// 読み込んだ全部の曲（元の並び）と、プレイリストの中の項目の ID。
    all: RefCell<Vec<(Song, Option<String>)>>,
    filter: RefCell<String>,
    kind: RefCell<Option<String>>,
    sort: Cell<(SortKey, bool)>,
    sortable: Cell<bool>,
    editable: Cell<bool>,
    loading: Cell<bool>,
    /// 一覧の「シャッフル再生」の指定。この一覧を流しているときは、キューのシャッフルの入り切りになる。
    shuffle_play: Cell<bool>,
    generation: Cell<u64>,
    /// 今出している行（all の位置）。
    shown: RefCell<Vec<usize>>,
    error: RefCell<Option<String>>,
    // 部品
    header: adw::Bin,
    controls: gtk::Box,
    list_holder: gtk::Box,
    sort_label: gtk::Label,
    sort_list: gtk::ListBox,
    kind_chips: gtk::Box,
    spinner: adw::Spinner,
    play: gtk::Button,
}

/// プレイリストのページ（LM は高く評価した曲）。
pub fn playlist(app: &Rc<App>, playlist_id: &str, title: &str) -> adw::NavigationPage {
    collection_page(app, playlist_id, title, Source::Playlist, None)
}

/// アルバムのページ。
pub fn album(app: &Rc<App>, browse_id: &str, title: &str) -> adw::NavigationPage {
    collection_page(app, browse_id, title, Source::Album, None)
}

fn collection_page(app: &Rc<App>, id: &str, title: &str, source: Source, songs: Option<Vec<Song>>) -> adw::NavigationPage {
    track::ensure_style();
    let sort_label = gtk::Label::builder().css_classes(["dim-label"]).build();
    let sort_list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["navigation-sidebar"]).build();
    let (_, kind_chips) = track::chip_row();
    let collection = Rc::new(Collection {
        app: app.clone(),
        id: id.to_string(),
        source,
        title: RefCell::new(title.to_string()),
        thumbnail: RefCell::default(),
        all: RefCell::default(),
        filter: RefCell::default(),
        kind: RefCell::default(),
        sort: Cell::new((SortKey::Custom, false)),
        sortable: Cell::new(false),
        editable: Cell::new(false),
        loading: Cell::new(true),
        shuffle_play: Cell::new(false),
        generation: Cell::new(0),
        shown: RefCell::default(),
        error: RefCell::default(),
        header: adw::Bin::new(),
        controls: gtk::Box::builder().spacing(16).build(),
        list_holder: gtk::Box::builder().orientation(gtk::Orientation::Vertical).build(),
        sort_label,
        sort_list,
        kind_chips,
        spinner: adw::Spinner::builder().width_request(18).height_request(18).build(),
        play: gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").valign(gtk::Align::Center).visible(false).css_classes(["circular", "suggested-action", "play-large"]).build(),
    });
    collection.restore_order();
    let column = page_column();
    column.append(&collection.header);
    collection.build_controls();
    column.append(&collection.controls);
    collection.build_kind_chips();
    column.append(&collection.kind_chips);
    column.append(&collection.list_holder);
    collection.render_header(None, None);
    match songs {
        Some(songs) => {
            if let Source::LikedBy(name) = &collection.source {
                collection.render_header(None, Some(format!("{name} • {}", collection::count_and_length(&songs))));
            }
            *collection.all.borrow_mut() = songs.into_iter().map(|s| (s, None)).collect();
            collection.loaded();
        }
        None => collection.load(),
    }
    let scroller = scrolling_column(&column);
    // 選んだ行で Return を押すと、その行から（一覧の中の検索の欄の Return は欄が使う）
    let page = page(title, if matches!(collection.source, Source::Album) { "album" } else { "playlist" }, &scroller);
    let keep = collection.clone();
    page.connect_destroy(move |_| {
        let _ = &keep;
    });
    page
}

impl Collection {
    fn is_album(&self) -> bool {
        matches!(self.source, Source::Album)
    }

    fn is_liked(&self) -> bool {
        self.id == LIKED_SONGS
    }

    fn restore_order(&self) {
        if self.is_album() {
            return;
        }
        if let Some(saved) = self.app.downloads.settings.borrow().playlist_orders.get(&self.id) {
            self.sort.set(collection::parse_order(saved));
        }
    }

    fn save_order(&self) {
        if self.is_album() || matches!(self.source, Source::LikedBy(_)) {
            return;
        }
        let (key, descending) = self.sort.get();
        let (id, value) = (self.id.clone(), collection::format_order(key, descending));
        self.app.downloads.update_settings(|s| {
            s.playlist_orders.insert(id, value);
        });
    }

    /// 頭（ジャケット・題・アルバムのアーティスト（押すとアーティストのページ）・補足）。
    fn render_header(&self, artists: Option<(String, Option<String>)>, detail: Option<String>) {
        let header = gtk::Box::builder().spacing(24).margin_top(12).build();
        if self.is_liked() || matches!(self.source, Source::LikedBy(_)) {
            header.append(&track::cover("liked", 220));
        } else {
            let art = images::square(220);
            art.load(self.thumbnail.borrow().as_deref(), 440);
            header.append(&art.root);
        }
        let info = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).valign(gtk::Align::End).hexpand(true).build();
        info.append(&gtk::Label::builder().label(if self.is_album() { "アルバム" } else { "プレイリスト" }).xalign(0.0).build());
        info.append(&track::page_title(&self.title.borrow()));
        if let Some((names, id)) = artists {
            match id {
                Some(id) => {
                    let (app, name) = (self.app.clone(), names.clone());
                    info.append(&track::link(&names, move || app.push(artist(&app, &id, &name))));
                }
                None => info.append(&gtk::Label::builder().label(&names).xalign(0.0).css_classes(["heading"]).build()),
            }
        }
        if let Some(detail) = detail.filter(|d| !d.is_empty()) {
            info.append(&gtk::Label::builder().label(detail).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["dim-label"]).build());
        }
        header.append(&info);
        self.header.set_child(Some(&header));
    }

    /// 操作の行: 再生（流しているときは一時停止）・シャッフル（入り切り）・ダウンロード・「…」（次に再生・キューに追加）・右端に 🔍 と並べ替え。
    fn build_controls(self: &Rc<Self>) {
        let weak = Rc::downgrade(self);
        {
            let weak = weak.clone();
            self.play.connect_clicked(move |_| {
                let Some(c) = weak.upgrade() else { return };
                if c.app.playback.is_playing_source(&c.id) {
                    c.app.playback.toggle();
                } else {
                    c.play_from(0, c.shuffle_play.get());
                }
            });
        }
        {
            let (playback, id) = (self.app.playback.clone(), self.id.clone());
            self.app.playback.watch(&self.play, move |play| track::set_play_icon(play, playback.is_playing_source(&id) && playback.player().is_playing()));
        }
        self.controls.append(&self.play);
        let shuffle = gtk::ToggleButton::builder().icon_name("media-playlist-shuffle-symbolic").tooltip_text("シャッフル").valign(gtk::Align::Center).css_classes(["circular", "flat"]).build();
        let syncing = Rc::new(Cell::new(false));
        {
            let (weak, syncing) = (weak.clone(), syncing.clone());
            shuffle.connect_toggled(move |button| {
                let Some(c) = weak.upgrade() else { return };
                if syncing.get() {
                    return;
                }
                if c.app.playback.is_playing_source(&c.id) {
                    c.app.playback.set_shuffle(button.is_active());
                } else {
                    c.shuffle_play.set(button.is_active());
                }
            });
        }
        {
            let (weak, syncing) = (weak.clone(), syncing.clone());
            self.app.playback.watch(&shuffle, move |button| {
                let Some(c) = weak.upgrade() else { return };
                let on = if c.app.playback.is_playing_source(&c.id) { c.app.playback.shuffled() } else { c.shuffle_play.get() };
                syncing.set(true);
                button.set_active(on);
                syncing.set(false);
            });
        }
        self.controls.append(&shuffle);
        if !matches!(self.source, Source::LikedBy(_)) {
            self.controls.append(&self.download_button());
        }
        // 次に再生・キューに追加は「…」の中（Mac と同じ）
        let menu = gtk::gio::Menu::new();
        menu.append(Some("次に再生"), Some("collection.next"));
        menu.append(Some("キューに追加"), Some("collection.queue"));
        let actions = gtk::gio::SimpleActionGroup::new();
        for (name, next) in [("next", true), ("queue", false)] {
            let action = gtk::gio::SimpleAction::new(name, None);
            let weak = weak.clone();
            action.connect_activate(move |_, _| {
                let Some(c) = weak.upgrade() else { return };
                let songs = c.shown_songs();
                let count = songs.len();
                if next {
                    // 並びが保たれるよう、後ろから今の曲の直後へ入れる
                    for song in songs.into_iter().rev() {
                        c.app.playback.play_next(song);
                    }
                    c.app.toast(&format!("{count} 曲を次に再生します"));
                } else {
                    for song in songs {
                        c.app.playback.add_to_queue(song);
                    }
                    c.app.toast(&format!("{count} 曲をキューに追加しました"));
                }
            });
            actions.add_action(&action);
        }
        let more = gtk::MenuButton::builder().icon_name("view-more-symbolic").tooltip_text("その他").menu_model(&menu).valign(gtk::Align::Center).css_classes(["circular", "flat"]).build();
        more.insert_action_group("collection", Some(&actions));
        self.controls.append(&more);
        self.spinner.set_valign(gtk::Align::Center);
        self.controls.append(&self.spinner);
        self.controls.append(&gtk::Box::builder().hexpand(true).build());
        // 一覧の中の検索（🔍 で開き、Esc で閉じる）
        let filter = gtk::SearchEntry::builder().placeholder_text("この一覧の中を検索").width_request(240).visible(false).valign(gtk::Align::Center).build();
        let find = gtk::Button::builder().icon_name("system-search-symbolic").tooltip_text("この一覧の中を検索").valign(gtk::Align::Center).css_classes(["flat", "circular"]).build();
        {
            let filter = filter.clone();
            let weak = weak.clone();
            find.connect_clicked(move |_| {
                let open = !filter.is_visible();
                filter.set_visible(open);
                if open {
                    filter.grab_focus();
                } else {
                    filter.set_text("");
                    if let Some(c) = weak.upgrade() {
                        *c.filter.borrow_mut() = String::new();
                        c.render();
                    }
                }
            });
        }
        {
            let weak = weak.clone();
            filter.connect_search_changed(move |entry| {
                if let Some(c) = weak.upgrade() {
                    *c.filter.borrow_mut() = entry.text().to_string();
                    c.render();
                }
            });
        }
        filter.connect_stop_search(|entry| {
            entry.set_text("");
            entry.set_visible(false);
        });
        self.controls.append(&find);
        self.controls.append(&filter);
        let popover = gtk::Popover::builder().child(&self.sort_list).build();
        let sort = gtk::MenuButton::builder().child(&self.sort_label).popover(&popover).tooltip_text("並べ替え").valign(gtk::Align::Center).css_classes(["flat"]).build();
        {
            let weak = weak.clone();
            self.sort_list.connect_row_activated(move |_, row| {
                popover.popdown();
                if let Some(c) = weak.upgrade() {
                    let choices = SortKey::choices(c.sortable.get());
                    if let Some(key) = choices.get(row.index().max(0) as usize) {
                        c.choose(*key);
                    }
                }
            });
        }
        self.controls.append(&sort);
        self.render_sort();
    }

    /// 曲の種類で絞るチップ（もう一度押すと外す）。
    fn build_kind_chips(self: &Rc<Self>) {
        while let Some(child) = self.kind_chips.first_child() {
            self.kind_chips.remove(&child);
        }
        let current = self.kind.borrow().clone();
        for (kind, label) in [(None, "すべて"), (Some("MUSIC_VIDEO_TYPE_ATV"), "曲"), (Some("MUSIC_VIDEO_TYPE_OMV"), "MV"), (Some("MUSIC_VIDEO_TYPE_UGC"), "その他の動画（歌ってみた・ライブなど）")] {
            let chip = track::chip(label, current.as_deref() == kind);
            let weak = Rc::downgrade(self);
            chip.connect_clicked(move |_| {
                let Some(c) = weak.upgrade() else { return };
                {
                    let mut k = c.kind.borrow_mut();
                    *k = if kind.is_none() || k.as_deref() == kind { None } else { kind.map(String::from) };
                }
                c.build_kind_chips();
                c.render();
            });
            self.kind_chips.append(&chip);
        }
    }

    fn render_sort(&self) {
        let (key, descending) = self.sort.get();
        self.sort_label.set_label(key.label());
        while let Some(row) = self.sort_list.row_at_index(0) {
            self.sort_list.remove(&row);
        }
        for choice in SortKey::choices(self.sortable.get()) {
            let row = adw::ActionRow::builder().title(choice.label()).activatable(true).build();
            if choice == key {
                let icon = if key == SortKey::Custom && !descending { "object-select-symbolic" } else if descending { "go-down-symbolic" } else { "go-up-symbolic" };
                row.add_suffix(&gtk::Image::from_icon_name(icon));
            }
            self.sort_list.append(&row);
        }
    }

    /// 同じ並べ方をもう一度選ぶと向きを変える。YouTube Music に並べてもらう並べ方に替えた・外したときは読み直す。
    fn choose(self: &Rc<Self>, key: SortKey) {
        let was_server = self.sort.get().0.server();
        let next = collection::choose(self.sort.get(), key);
        self.sort.set(next);
        self.save_order();
        self.render_sort();
        if matches!(self.source, Source::Playlist) && (was_server || next.0.server()) {
            self.load();
        } else {
            self.render();
        }
    }

    fn download_button(self: &Rc<Self>) -> gtk::Widget {
        let button = gtk::ToggleButton::builder().icon_name("folder-download-symbolic").tooltip_text("ダウンロード").valign(gtk::Align::Center).css_classes(["circular", "flat"]).build();
        let label = gtk::Label::builder().css_classes(["caption", "dim-label"]).valign(gtk::Align::Center).build();
        let row = gtk::Box::builder().spacing(6).build();
        row.append(&button);
        row.append(&label);
        let updating = Rc::new(Cell::new(false));
        {
            let (weak, button, updating) = (Rc::downgrade(self), button.clone(), updating.clone());
            self.app.downloads.watch(&label, move |label| {
                let Some(c) = weak.upgrade() else { return };
                let downloads = &c.app.downloads;
                let on = downloads.manifest().collection(&c.id).is_some();
                updating.set(true);
                button.set_active(on);
                updating.set(false);
                let ids: Vec<String> = c.all.borrow().iter().map(|(s, _)| s.video_id.clone()).collect();
                let done = ids.iter().filter(|v| downloads.state(v) == crate::downloads::SongState::Done).count();
                label.set_label(&if !on {
                    String::new()
                } else if done >= ids.len() {
                    "ダウンロード済み".to_string()
                } else {
                    format!("{} 曲中 {} 曲", ids.len(), done)
                });
                button.set_tooltip_text(Some(if on { "ダウンロードを削除" } else { "ダウンロード" }));
            });
        }
        let weak = Rc::downgrade(self);
        button.connect_toggled(move |b| {
            if updating.get() {
                return;
            }
            let Some(c) = weak.upgrade() else { return };
            if b.is_active() {
                if c.loading.get() || c.all.borrow().is_empty() {
                    updating.set(true);
                    b.set_active(false);
                    updating.set(false);
                    return;
                }
                let kind = if c.is_album() { wave_core::download::manifest::KIND_ALBUM } else if c.is_liked() { wave_core::download::manifest::KIND_LIKED } else { wave_core::download::manifest::KIND_PLAYLIST };
                let songs: Vec<Song> = c.all.borrow().iter().map(|(s, _)| s.clone()).collect();
                let thumbnail = if c.is_liked() { None } else { c.thumbnail.borrow().clone() };
                c.app.downloads.download_collection(kind, &c.id, &c.title.borrow(), thumbnail.as_deref(), songs);
            } else {
                // 解除は確かめてから（Mac と同じ）
                updating.set(true);
                b.set_active(true);
                updating.set(false);
                let dialog = adw::AlertDialog::builder().heading("ダウンロードを削除しますか？").body("この端末から曲を消します。あとからもう一度ダウンロードできます。").default_response("cancel").close_response("cancel").build();
                dialog.add_response("cancel", "キャンセル");
                dialog.add_response("remove", "削除");
                dialog.set_response_appearance("remove", adw::ResponseAppearance::Destructive);
                let c2 = c.clone();
                dialog.connect_response(None, move |_, response| {
                    if response == "remove" {
                        c2.app.downloads.remove_collection(&c2.id);
                    }
                });
                dialog.present(c.app.window().as_ref());
            }
        });
        row.upcast()
    }

    /// 読み込む（プレイリストは届いたページから順に出す。読めなければ、取ってあれば取った曲を出す）。
    fn load(self: &Rc<Self>) {
        let generation = self.generation.get() + 1;
        self.generation.set(generation);
        self.all.borrow_mut().clear();
        *self.error.borrow_mut() = None;
        self.loading.set(true);
        self.spinner.set_visible(true);
        self.render();
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            match this.source.clone() {
                Source::Album => this.load_album(generation).await,
                Source::Playlist => this.load_playlist(generation).await,
                Source::LikedBy(_) => {}
            }
            if this.generation.get() == generation {
                this.loaded();
            }
        });
    }

    fn loaded(self: &Rc<Self>) {
        self.loading.set(false);
        self.spinner.set_visible(false);
        if self.all.borrow().is_empty() && self.error.borrow().is_none() {
            *self.error.borrow_mut() = Some("曲がありません".into());
        }
        self.render();
        // 確かめ用: WAVE_DEMO_COLLECTION_FILTER の語で一覧の中を絞る
        if let Ok(text) = std::env::var("WAVE_DEMO_COLLECTION_FILTER") {
            *self.filter.borrow_mut() = text;
            self.render();
        }
    }

    async fn load_album(self: &Rc<Self>, generation: u64) {
        let (repo, id) = (self.app.repo.clone(), self.id.clone());
        let result = runtime::run(async move { repo.album(&id).await }).await;
        if self.generation.get() != generation {
            return;
        }
        match result {
            Ok(page) => {
                *self.title.borrow_mut() = page.title.clone();
                *self.thumbnail.borrow_mut() = page.thumbnail_url.clone();
                let detail = [page.subtitle.clone(), Some(collection::count_and_length(&page.songs))].into_iter().flatten().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" • ");
                // アーティストは題の下に別の行で出す（押すとアーティストのページ）
                let artists = (!page.artists.is_empty()).then(|| {
                    let names = page.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", ");
                    (names, page.artists.iter().find_map(|a| a.browse_id.clone()))
                });
                self.render_header(artists, Some(detail));
                *self.all.borrow_mut() = page.songs.into_iter().map(|s| (s, None)).collect();
            }
            Err(e) => {
                if !self.show_offline() {
                    *self.error.borrow_mut() = Some(format!("アルバムを読み込めませんでした（{e}）"));
                }
            }
        }
    }

    async fn load_playlist(self: &Rc<Self>, generation: u64) {
        let (key, descending) = self.sort.get();
        let order = match key {
            SortKey::Added => Some(wave_core::music::repository::order_params(wave_core::music::repository::ORDER_ADDED, descending)),
            SortKey::Published => Some(wave_core::music::repository::order_params(wave_core::music::repository::ORDER_PUBLISHED, descending)),
            _ => None,
        };
        let (repo, id) = (self.app.repo.clone(), self.id.clone());
        let o = order.clone();
        let first = runtime::run(async move { repo.playlist_in_order(&id, None, o.as_deref()).await }).await;
        if self.generation.get() != generation {
            return;
        }
        let first = match first {
            Ok(page) => page,
            Err(e) => {
                if !self.show_offline() {
                    *self.error.borrow_mut() = Some(match e {
                        wave_core::innertube::Failure::NotLoggedIn => "このプレイリストを見るにはログインしてください".into(),
                        e => format!("プレイリストを読み込めませんでした（{e}）"),
                    });
                }
                return;
            }
        };
        self.editable.set(first.editable);
        self.sortable.set(first.sortable);
        // 並べ替えられないプレイリストでは、YouTube Music に並べてもらう並べ方は選べない
        if !first.sortable && self.sort.get().0.server() {
            self.sort.set((SortKey::Custom, false));
        }
        self.render_sort();
        if let Some(title) = first.title.clone().filter(|t| !t.is_empty()) {
            *self.title.borrow_mut() = title;
        }
        if first.thumbnail_url.is_some() {
            *self.thumbnail.borrow_mut() = first.thumbnail_url.clone();
        }
        let subtitle = first.subtitle.clone();
        self.render_header(None, subtitle.clone());
        self.append_entries(first.songs.into_iter().zip(first.set_video_ids.into_iter().chain(std::iter::repeat(None))).collect());
        // 届いたページから順に出す
        let mut token = first.continuation;
        for _ in 0..100 {
            let Some(t) = token.clone() else { break };
            let (repo, id) = (self.app.repo.clone(), self.id.clone());
            let tok = t.clone();
            let Ok(next) = runtime::run(async move { repo.playlist(&id, Some(&tok)).await }).await else { break };
            if self.generation.get() != generation {
                return;
            }
            self.append_entries(next.songs.into_iter().zip(next.set_video_ids.into_iter().chain(std::iter::repeat(None))).collect());
            token = next.continuation.filter(|n| *n != t);
        }
        // サーバーの補足（曲数・時間を含む）があればそれを出す。無ければ読めた曲から数える
        if subtitle.is_none() {
            let songs: Vec<Song> = self.all.borrow().iter().map(|(s, _)| s.clone()).collect();
            self.render_header(None, Some(collection::count_and_length(&songs)));
        }
    }

    /// 届いた曲を足す。絞り込み・並べ替えをしていなければ、行を後ろに足すだけにする（長いプレイリストで出し直さない）。
    fn append_entries(self: &Rc<Self>, entries: Vec<(Song, Option<String>)>) {
        let plain = self.filter.borrow().trim().is_empty() && self.kind.borrow().is_none() && self.sort.get() == (SortKey::Custom, false) && !self.shown.borrow().is_empty();
        let start = self.all.borrow().len();
        self.all.borrow_mut().extend(entries);
        if !plain {
            self.render();
            return;
        }
        let end = self.all.borrow().len();
        let rows = self.rows_for(&(start..end).collect::<Vec<_>>());
        if let Some(list) = self.list_holder.last_child().and_downcast::<gtk::ListBox>() {
            track::append(&self.app, &list, rows, self.columns());
            self.shown.borrow_mut().extend(start..end);
        } else {
            self.render();
        }
    }

    /// 読めなかったとき、取ってあれば取った曲を出す（通信なしでも開ける）。出したら true。
    fn show_offline(&self) -> bool {
        let Some(c) = self.app.downloads.manifest().collection(&self.id).cloned() else { return false };
        if c.songs.is_empty() {
            return false;
        }
        *self.title.borrow_mut() = c.title.clone();
        *self.thumbnail.borrow_mut() = c.thumbnail_url.clone();
        self.render_header(None, Some(format!("ダウンロード済み • {} 曲", c.songs.len())));
        *self.all.borrow_mut() = c.songs.into_iter().map(|s| (s, None)).collect();
        true
    }

    fn columns(&self) -> Columns {
        if self.is_album() { Columns::ALBUM } else { Columns::FULL }
    }

    fn rows_for(self: &Rc<Self>, indices: &[usize]) -> Vec<Row> {
        let all = self.all.borrow();
        let editable = self.editable.get() && !self.is_liked();
        indices
            .iter()
            .filter_map(|&i| {
                let (song, set_id) = all.get(i)?.clone();
                // 自分のプレイリストなら、曲のメニューから外せる
                let removal = set_id.filter(|_| editable).map(|set_video_id| {
                    let weak = Rc::downgrade(self);
                    let removed = set_video_id.clone();
                    super::song_menu::Removal {
                        playlist_id: self.id.clone(),
                        set_video_id,
                        on_removed: Rc::new(move || {
                            if let Some(c) = weak.upgrade() {
                                c.all.borrow_mut().retain(|(_, s)| s.as_deref() != Some(removed.as_str()));
                                c.render();
                            }
                        }),
                    }
                });
                Some(Row { number: i + 1, song, removal })
            })
            .collect()
    }

    /// 絞り込みと並べ替えを当てて、一覧を出し直す。
    fn render(self: &Rc<Self>) {
        while let Some(child) = self.list_holder.first_child() {
            self.list_holder.remove(&child);
        }
        let songs: Vec<Song> = self.all.borrow().iter().map(|(s, _)| s.clone()).collect();
        let (key, descending) = self.sort.get();
        let shown = collection::arrange(&songs, &self.filter.borrow(), self.kind.borrow().as_deref(), key, descending);
        self.play.set_visible(!shown.is_empty());
        *self.shown.borrow_mut() = shown.clone();
        if songs.is_empty() {
            if let Some(error) = self.error.borrow().as_deref().filter(|_| !self.loading.get()) {
                self.list_holder.append(&gtk::Label::builder().label(error).xalign(0.0).margin_top(24).css_classes(["dim-label"]).build());
            }
            return;
        }
        self.list_holder.append(&track::header(self.columns()));
        if shown.is_empty() {
            self.list_holder.append(&gtk::Label::builder().label("一致する曲はありません").margin_top(24).css_classes(["dim-label"]).build());
            return;
        }
        let rows = self.rows_for(&shown);
        let weak = Rc::downgrade(self);
        let list = track::list(&self.app, rows, self.columns(), Rc::new(move |position| {
            if let Some(c) = weak.upgrade() {
                c.play_from(position, false);
            }
        }));
        self.list_holder.append(&list);
    }

    fn shown_songs(&self) -> Vec<Song> {
        let all = self.all.borrow();
        self.shown.borrow().iter().filter_map(|&i| all.get(i).map(|(s, _)| s.clone())).collect()
    }

    /// 出ている並びで、position の行から流す（shuffle ならシャッフルを入れて）。
    fn play_from(&self, position: usize, shuffle: bool) {
        let songs = self.shown_songs();
        if songs.is_empty() {
            return;
        }
        if shuffle {
            self.app.playback.play_shuffled(songs, Some(self.id.clone()));
        } else {
            self.app.playback.play_list_from(songs, position, Some(self.id.clone()));
        }
    }
}

// MARK: アーティスト

/// アーティストのページ（Mac と同じ並び: 頭に写真・名前・登録、再生とシャッフル、ライブ・お知らせ、人気、すべての曲、高く評価した曲、
/// 新作（曲も含めて NEW の印）・アルバムの棚・動画の棚）。再生は人気の曲から流し、「すべての曲」を読めたら後ろに足す。
pub fn artist(app: &Rc<App>, browse_id: &str, name: &str) -> adw::NavigationPage {
    track::ensure_style();
    let bin = holder();
    let (app, target, id) = (app.clone(), bin.clone(), browse_id.to_string());
    let artist_id = id.clone();
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let content: gtk::Widget = match runtime::run(async move { repo.artist(&id).await }).await {
            Ok(page) => {
                let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).margin_top(20).margin_bottom(32).margin_start(12).margin_end(12).build();
                // 頭: 丸い写真・名前・登録
                let header = gtk::Box::builder().spacing(24).margin_start(12).build();
                let art = images::square(200);
                art.add_css_class("round");
                art.load(page.thumbnail_url.as_deref(), 400);
                header.append(&art.root);
                let info = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).valign(gtk::Align::End).hexpand(true).build();
                info.append(&gtk::Label::builder().label("アーティスト").xalign(0.0).build());
                info.append(&track::page_title(&page.name));
                header.append(&info);
                if let Some(channel_id) = page.channel_id.clone().filter(|_| app.repo.client().is_logged_in()) {
                    header.append(&subscribe_button(&app, channel_id, page.subscribed));
                }
                column.append(&header);
                // 再生とシャッフル（入り切り）だけ
                let controls = gtk::Box::builder().spacing(16).margin_start(12).build();
                let shuffle_play = Rc::new(Cell::new(false));
                let top = page.top_songs.clone();
                let all_songs = page.all_songs_playlist_id.clone();
                let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").visible(!top.is_empty()).css_classes(["circular", "suggested-action", "play-large"]).build();
                {
                    let (app, id, top, all_songs, shuffle_play) = (app.clone(), artist_id.clone(), top.clone(), all_songs.clone(), shuffle_play.clone());
                    play.connect_clicked(move |_| {
                        if app.playback.is_playing_source(&id) {
                            app.playback.toggle();
                        } else {
                            app.playback.play_artist(&id, top.clone(), 0, shuffle_play.get(), all_songs.clone());
                        }
                    });
                }
                {
                    let (playback, id) = (app.playback.clone(), artist_id.clone());
                    app.playback.watch(&play, move |play| track::set_play_icon(play, playback.is_playing_source(&id) && playback.player().is_playing()));
                }
                controls.append(&play);
                let shuffle = gtk::ToggleButton::builder().icon_name("media-playlist-shuffle-symbolic").tooltip_text("シャッフル").valign(gtk::Align::Center).css_classes(["circular", "flat"]).build();
                let syncing = Rc::new(Cell::new(false));
                {
                    let (app, id, shuffle_play, syncing) = (app.clone(), artist_id.clone(), shuffle_play.clone(), syncing.clone());
                    shuffle.connect_toggled(move |b| {
                        if syncing.get() {
                            return;
                        }
                        if app.playback.is_playing_source(&id) { app.playback.set_shuffle(b.is_active()) } else { shuffle_play.set(b.is_active()) }
                    });
                }
                {
                    let (playback, id) = (app.playback.clone(), artist_id.clone());
                    app.playback.watch(&shuffle, move |b| {
                        let on = if playback.is_playing_source(&id) { playback.shuffled() } else { shuffle_play.get() };
                        syncing.set(true);
                        b.set_active(on);
                        syncing.set(false);
                    });
                }
                controls.append(&shuffle);
                column.append(&controls);
                // ライブ・お知らせ（チャンネルの投稿のうち、1年以内のライブ・ツアーの告知）。読めたら差し込む
                let live = gtk::Box::new(gtk::Orientation::Vertical, 0);
                column.append(&live);
                let channel_id = page.channel_id.clone().or_else(|| artist_id.starts_with("UC").then(|| artist_id.clone()));
                if let Some(channel_id) = channel_id {
                    show_live_posts(&app, channel_id, &live);
                }
                if !top.is_empty() {
                    let popular = gtk::Box::builder().orientation(gtk::Orientation::Vertical).margin_start(12).margin_end(12).build();
                    popular.append(&gtk::Label::builder().label("人気").xalign(0.0).margin_bottom(4).css_classes(["title-2"]).build());
                    let (app_c, id, top_c, all_c) = (app.clone(), artist_id.clone(), top.clone(), all_songs.clone());
                    popular.append(&track::list(&app, Row::numbered(top.clone()), Columns::FULL, Rc::new(move |i| app_c.playback.play_artist(&id, top_c.clone(), i, false, all_c.clone()))));
                    column.append(&popular);
                }
                if let Some(all) = all_songs.clone() {
                    let title = format!("{} のすべての曲", page.name);
                    let app_c = app.clone();
                    let link = track::link("すべての曲を見る", move || app_c.push(playlist(&app_c, &all, &title)));
                    link.set_margin_start(12);
                    column.append(&link);
                }
                if app.repo.client().is_logged_in() {
                    column.append(&liked_by_artist(&app, &artist_id, &page.name));
                }
                // 新作（登録したアーティストの見張りで見つけたもの。今のページから消えたものは出さない。曲も含めて NEW の印）を先頭に
                let year = gtk::glib::DateTime::now_local().map(|d| d.year()).ok();
                let releases: Vec<HomeItem> = wave_core::music::releases::listed(&app.releases.recent(), &artist_id, &page, year, None)
                    .into_iter()
                    .filter(|r| r.artist_id == artist_id)
                    .filter_map(|r| wave_core::music::releases::release_item(&r, now_ms()))
                    .collect();
                if !releases.is_empty() {
                    column.append(&track::shelf(&app, "新作", None, releases));
                }
                for shelf in page.album_shelves {
                    column.append(&track::shelf(&app, &shelf.title, None, shelf.albums.into_iter().map(HomeItem::Album).collect()));
                }
                if !page.videos.is_empty() {
                    column.append(&track::shelf(&app, "動画", None, page.videos.into_iter().map(HomeItem::Song).collect()));
                }
                let scrolled = gtk::ScrolledWindow::builder().child(&clamp(&column)).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
                // 確かめ用: WAVE_DEMO_ARTIST_SCROLL の px だけ、読み終わるころに下へ送る
                if let Some(px) = std::env::var("WAVE_DEMO_ARTIST_SCROLL").ok().and_then(|v| v.parse::<f64>().ok()) {
                    let scrolled = scrolled.clone();
                    gtk::glib::timeout_add_local_once(std::time::Duration::from_secs(8), move || scrolled.vadjustment().set_value(px));
                }
                scrolled.upcast()
            }
            Err(e) => failure(&format!("アーティストを読み込めませんでした（{e}）")),
        };
        target.set_child(Some(&content));
    });
    page(name, "artist", &bin)
}

/// 登録のボタン。登録していなければ色付き、登録済みなら控えめに。書けたら表示を変える（楽観的に変えない）。登録しているか分からなければ押せない。
fn subscribe_button(app: &Rc<App>, channel_id: String, subscribed: Option<bool>) -> gtk::Widget {
    let button = gtk::Button::builder().valign(gtk::Align::End).css_classes(["pill"]).build();
    let state = Rc::new(Cell::new(subscribed));
    let show = {
        let (button, state) = (button.clone(), state.clone());
        move |busy: bool| {
            let on = state.get() == Some(true);
            button.set_label(if on { "登録済み" } else { "登録" });
            if on { button.remove_css_class("suggested-action") } else { button.add_css_class("suggested-action") }
            button.set_sensitive(!busy && state.get().is_some());
        }
    };
    show(false);
    let app = app.clone();
    button.connect_clicked(move |_| {
        let Some(current) = state.get() else { return };
        let target = !current;
        show(true);
        let (app, state, show, channel_id) = (app.clone(), state.clone(), show.clone(), channel_id.clone());
        gtk::glib::spawn_future_local(async move {
            let repo = app.repo.clone();
            let result = runtime::run(async move { repo.set_subscribed(&channel_id, target).await }).await;
            eprintln!("artist subscribe target={target} ok={}", result.is_ok());
            match result {
                Ok(()) => state.set(Some(target)),
                Err(_) => app.toast("登録を変更できませんでした"),
            }
            show(false);
        });
    });
    button.upcast()
}

/// チャンネルの投稿のうち、ライブ・ツアーの告知らしいものを横に並べて slot に入れる（押すと YouTube の投稿を開く。6件まで）。
fn show_live_posts(app: &Rc<App>, channel_id: String, slot: &gtk::Box) {
    let (repo, slot) = (app.repo.clone(), slot.clone());
    gtk::glib::spawn_future_local(async move {
        let result = runtime::run(async move { repo.artist_posts(&channel_id).await }).await;
        let count = result.as_ref().map(Vec::len).ok();
        let posts: Vec<_> = result.map(wave_core::music::artist::live_news).unwrap_or_default().into_iter().take(6).collect();
        eprintln!("artist posts ok={} posts={count:?} live={}", count.is_some(), posts.len());
        if posts.is_empty() {
            return;
        }
        let column = gtk::Box::new(gtk::Orientation::Vertical, 8);
        column.append(&gtk::Label::builder().label("ライブ・お知らせ").xalign(0.0).margin_start(12).css_classes(["title-2"]).build());
        let row = gtk::Box::builder().spacing(12).margin_start(12).margin_end(12).valign(gtk::Align::Start).build();
        for post in posts {
            row.append(&live_post_card(post));
        }
        column.append(&gtk::ScrolledWindow::builder().child(&row).vscrollbar_policy(gtk::PolicyType::Never).propagate_natural_height(true).build());
        slot.append(&column);
    });
}

const POST_WIDTH: i32 = 260;

/// 告知のカード（画像・本文・投稿の時期）。
fn live_post_card(post: wave_core::music::models::ArtistPost) -> gtk::Widget {
    let body = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).width_request(POST_WIDTH - 24).margin_top(12).margin_bottom(12).margin_start(12).margin_end(12).build();
    if let Some(url) = post.image_url.as_deref().filter(|u| u.starts_with("https://")) {
        // 枠の大きさを決めて画像を重ね、横長・縦長でも枠は変えない
        let frame = gtk::Box::builder().width_request(POST_WIDTH - 24).height_request(140).css_classes(["artwork", "artwork-frame"]).build();
        let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).css_classes(["artwork"]).build();
        picture.set_overflow(gtk::Overflow::Hidden);
        let overlay = gtk::Overlay::builder().child(&frame).build();
        overlay.add_overlay(&picture);
        overlay.set_clip_overlay(&picture, true);
        let target = picture.clone();
        images::texture(url, 520, move |texture| target.set_paintable(Some(&texture)));
        body.append(&overlay);
    }
    // 6行で切る。Pango の行の上限は段落ごとなので、改行を段落の中の改行（U+2028）にして本文全体を6行にする
    let text = post.text.trim().replace("\r\n", "\n").replace(['\r', '\n'], "\u{2028}");
    body.append(&gtk::Label::builder().label(&text).xalign(0.0).wrap(true).wrap_mode(gtk::pango::WrapMode::WordChar).lines(6).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).build());
    if let Some(published) = post.published_text.as_deref() {
        body.append(&gtk::Label::builder().label(published).xalign(0.0).css_classes(["caption", "dim-label"]).build());
    }
    // 長い本文でカードが横に伸びないよう、幅の上限を決める
    let clamp = adw::Clamp::builder().child(&body).maximum_size(POST_WIDTH).tightening_threshold(POST_WIDTH).build();
    let button = gtk::Button::builder().child(&clamp).css_classes(["card"]).halign(gtk::Align::Start).valign(gtk::Align::Start).tooltip_text("YouTube で開く").build();
    let url = post.url();
    button.connect_clicked(move |_| gtk::UriLauncher::new(&url).launch(None::<&gtk::Window>, None::<&gtk::gio::Cancellable>, |_| {}));
    button.upcast()
}

/// 高く評価した曲のうち、このアーティストのもの（数の行。押すとその曲の一覧）。読むまでは「確かめています…」、無ければ出さない。
fn liked_by_artist(app: &Rc<App>, browse_id: &str, name: &str) -> gtk::Widget {
    let row = gtk::Box::builder().spacing(16).margin_start(12).build();
    row.append(&track::cover("liked", 56));
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).spacing(2).build();
    text.append(&gtk::Label::builder().label("高く評価した曲").xalign(0.0).css_classes(["heading"]).build());
    let count = gtk::Label::builder().label("確かめています…").xalign(0.0).css_classes(["caption", "dim-label"]).build();
    text.append(&count);
    row.append(&text);
    row.set_cursor_from_name(Some("pointer"));
    let found: Rc<RefCell<Option<Vec<Song>>>> = Rc::default();
    {
        let (app, found, name) = (app.clone(), found.clone(), name.to_string());
        let click = gtk::GestureClick::new();
        click.connect_released(move |_, _, _, _| {
            if let Some(songs) = found.borrow().clone() {
                app.push(liked_songs_page(&app, &name, songs));
            }
        });
        row.add_controller(click);
    }
    let (app, browse_id, name, weak) = (app.clone(), browse_id.to_string(), name.to_string(), row.downgrade());
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let result = runtime::run(async move { repo.liked_songs().await }).await;
        let Some(row) = weak.upgrade() else { return };
        let Ok((songs, complete)) = result else {
            row.set_visible(false);
            return;
        };
        let mine = wave_core::music::artist::songs_by_artist(&songs, Some(&browse_id), &name);
        eprintln!("artist liked songs={} mine={} complete={complete}", songs.len(), mine.len());
        row.set_visible(!mine.is_empty());
        count.set_label(&if complete { format!("{name} の {} 曲", mine.len()) } else { format!("{name} の {} 曲以上", mine.len()) });
        *found.borrow_mut() = Some(mine);
    });
    row.upcast()
}

/// 高く評価した曲のうち、そのアーティストのもの（プレイリストのページと同じ形）。
fn liked_songs_page(app: &Rc<App>, name: &str, songs: Vec<Song>) -> adw::NavigationPage {
    let page = collection_page(app, &format!("liked-by:{name}"), "高く評価した曲", Source::LikedBy(name.to_string()), Some(songs.clone()));
    page.set_title(&format!("高く評価した曲（{name}）"));
    page.set_tag(Some("liked-by-artist"));
    page
}

// MARK: ダウンロード

/// ダウンロードのページ（Mac と同じ並び）: 頭（使っている容量・進み具合・すべて削除）→ スマートダウンロード（入り切り・曲数・分類のチップ・
/// まとめて再生・シャッフル再生・曲）→ プレイリスト・アルバム → 曲 → エピソード。取り終えていない曲は薄く出す。
pub fn downloads(app: &Rc<App>) -> adw::NavigationPage {
    track::ensure_style();
    let column = page_column();
    let header = gtk::Box::builder().spacing(24).build();
    header.append(&track::cover("downloads", 160));
    let info = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).valign(gtk::Align::End).hexpand(true).build();
    info.append(&track::page_title("ダウンロード"));
    let status = gtk::Label::builder().xalign(0.0).wrap(true).css_classes(["dim-label"]).build();
    info.append(&status);
    let progress = gtk::ProgressBar::builder().width_request(320).halign(gtk::Align::Start).visible(false).build();
    info.append(&progress);
    let clear = gtk::Button::builder().label("すべて削除").halign(gtk::Align::Start).margin_top(4).css_classes(["destructive-action", "pill"]).build();
    info.append(&clear);
    header.append(&info);
    column.append(&header);
    {
        let app = app.clone();
        clear.connect_clicked(move |_| {
            let dialog = adw::AlertDialog::builder().heading("ダウンロードをすべて削除しますか？").body("この端末に取った曲・エピソードをすべて消します。").default_response("cancel").close_response("cancel").build();
            dialog.add_response("cancel", "キャンセル");
            dialog.add_response("remove", "すべて削除");
            dialog.set_response_appearance("remove", adw::ResponseAppearance::Destructive);
            let app_c = app.clone();
            dialog.connect_response(None, move |_, response| {
                if response == "remove" {
                    app_c.downloads.clear();
                    app_c.toast("ダウンロードをすべて削除しました");
                }
            });
            dialog.present(app.window().as_ref());
        });
    }
    // 頭の行は変わるたびに、中身は目録が変わったときだけ出し直す
    {
        let (app, progress) = (app.clone(), progress.clone());
        app.downloads.clone().watch(&status, move |status| {
            let downloads = &app.downloads;
            let wanted = downloads.manifest().wanted_songs();
            let pending: Vec<&Song> = wanted.iter().filter(|s| downloads.state(&s.video_id) != crate::downloads::SongState::Done).collect();
            let mut text = format!("使っている容量: {}", crate::downloads::format_bytes(downloads.total_bytes()));
            let active = downloads.progress.lock().unwrap().clone();
            progress.set_visible(false);
            if !pending.is_empty() {
                text.push_str(&format!(" • 残り {} 曲 • ", pending.len()));
                if !downloads.network_allows() {
                    text.push_str(if downloads.online() { "Wi-Fi など従量制でない通信を待っています" } else { "通信を待っています" });
                } else if let Some((id, got, total)) = active {
                    let title = wanted.iter().find(|s| s.video_id == id).map(|s| s.title.clone()).unwrap_or_default();
                    let percent = if total > 0 { got * 100 / total } else { 0 };
                    text.push_str(&format!("取っています: {title}（{percent}%）"));
                    progress.set_visible(true);
                    progress.set_fraction(if total > 0 { got as f64 / total as f64 } else { 0.0 });
                } else {
                    text.push_str("待っています");
                }
            }
            status.set_label(&text);
        });
    }
    let body = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).build();
    column.append(&body);
    let category: Rc<Cell<Option<wave_core::download::smart::SmartCategory>>> = Rc::default();
    let signature: Rc<RefCell<String>> = Rc::default();
    let render: Rc<RefCell<Option<Rc<dyn Fn(bool)>>>> = Rc::default();
    let render_fn: Rc<dyn Fn(bool)> = {
        let (app, body, category, signature, render) = (app.clone(), body.clone(), category.clone(), signature.clone(), Rc::downgrade(&render));
        Rc::new(move |force: bool| {
            let downloads = &app.downloads;
            let manifest = downloads.manifest();
            let settings = downloads.settings.borrow().clone();
            let done = |id: &str| downloads.state(id) == crate::downloads::SongState::Done;
            let sig = format!(
                "{}|{}|{}|{:?}|{}|{}|{}",
                settings.smart_downloads,
                settings.smart_download_count,
                manifest.collections.iter().map(|c| format!("{}:{}", c.id, c.songs.iter().filter(|s| done(&s.video_id)).count())).collect::<Vec<_>>().join(","),
                category.get(),
                manifest.singles.iter().map(|s| format!("{}{}", s.song.video_id, done(&s.song.video_id))).collect::<String>(),
                manifest.smart.iter().map(|t| format!("{}{}", t.song.video_id, done(&t.song.video_id))).collect::<String>(),
                manifest.collections.len()
            );
            if !force && *signature.borrow() == sig {
                return;
            }
            *signature.borrow_mut() = sig;
            while let Some(child) = body.first_child() {
                body.remove(&child);
            }
            // スマートダウンロード
            body.append(&section_title("スマートダウンロード"));
            let smart_row = gtk::Box::builder().spacing(16).build();
            let texts = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).spacing(2).build();
            texts.append(&gtk::Label::builder().label("聴きそうな曲を自動で取っておく").xalign(0.0).build());
            texts.append(&gtk::Label::builder().label("いつも聴いている曲・好きそうな曲・おすすめから、毎日選び直します").xalign(0.0).wrap(true).css_classes(["caption", "dim-label"]).build());
            smart_row.append(&texts);
            if settings.smart_downloads {
                let labels: Vec<String> = wave_core::download::smart::COUNTS.iter().map(|c| format!("{c} 曲")).collect();
                let model = gtk::StringList::new(&labels.iter().map(String::as_str).collect::<Vec<_>>());
                let count = gtk::DropDown::builder().model(&model).valign(gtk::Align::Center).selected(wave_core::download::smart::COUNTS.iter().position(|c| *c == settings.smart_download_count).unwrap_or(1) as u32).build();
                let app_c = app.clone();
                count.connect_selected_notify(move |d| {
                    if let Some(value) = wave_core::download::smart::COUNTS.get(d.selected() as usize).copied() {
                        app_c.downloads.update_settings(|s| s.smart_download_count = value);
                    }
                });
                smart_row.append(&count);
            }
            let switch = gtk::Switch::builder().active(settings.smart_downloads).valign(gtk::Align::Center).build();
            {
                let app_c = app.clone();
                switch.connect_active_notify(move |s| {
                    let on = s.is_active();
                    app_c.downloads.update_settings(|st| st.smart_downloads = on);
                });
            }
            smart_row.append(&switch);
            body.append(&smart_row);
            if settings.smart_downloads {
                use wave_core::download::smart::SmartCategory;
                let chips_row = gtk::Box::builder().spacing(8).build();
                for (value, label) in [(None, "すべて"), (Some(SmartCategory::Frequent), "いつも聴いている"), (Some(SmartCategory::Likely), "好きそう"), (Some(SmartCategory::Recommended), "おすすめ")] {
                    let chip = track::chip(label, category.get() == value);
                    let (category, render) = (category.clone(), render.clone());
                    chip.connect_clicked(move |_| {
                        category.set(value);
                        if let Some(render) = render.upgrade().and_then(|r| r.borrow().clone()) {
                            render(true);
                        }
                    });
                    chips_row.append(&chip);
                }
                chips_row.append(&gtk::Box::builder().hexpand(true).build());
                let songs: Vec<Song> = manifest.smart.iter().filter(|t| category.get().is_none_or(|c| t.category == c)).map(|t| t.song.clone()).collect();
                let shuffle = gtk::Button::builder().icon_name("media-playlist-shuffle-symbolic").tooltip_text("シャッフル再生").sensitive(!songs.is_empty()).valign(gtk::Align::Center).css_classes(["flat", "circular"]).build();
                let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").sensitive(!songs.is_empty()).css_classes(["circular", "suggested-action", "play-fab"]).build();
                {
                    let (app, songs) = (app.clone(), songs.clone());
                    shuffle.connect_clicked(move |_| app.playback.play_shuffled(songs.clone(), Some("downloads".into())));
                }
                {
                    let (app, songs) = (app.clone(), songs.clone());
                    play.connect_clicked(move |_| app.playback.play_list_from(songs.clone(), 0, Some("downloads".into())));
                }
                chips_row.append(&shuffle);
                chips_row.append(&play);
                body.append(&chips_row);
                if songs.is_empty() {
                    body.append(&gtk::Label::builder().label("まだ選んだ曲はありません").xalign(0.0).css_classes(["dim-label"]).build());
                } else {
                    body.append(&download_rows(&app, songs));
                }
            }
            // 丸ごと取ったもの（新しい順。右クリックで「ダウンロードを削除」）
            let mut collections = manifest.collections.clone();
            collections.sort_by_key(|c| std::cmp::Reverse(c.added_at_ms));
            if !collections.is_empty() {
                body.append(&section_title("プレイリスト・アルバム"));
                let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["track-list"]).build();
                for c in collections {
                    list.append(&collection_row(&app, c));
                }
                body.append(&list);
            }
            let singles: Vec<Song> = manifest.singles.iter().map(|s| s.song.clone()).collect();
            let (episodes, songs): (Vec<Song>, Vec<Song>) = singles.into_iter().partition(|s| wave_core::music::feed::is_external(&s.video_id));
            if !songs.is_empty() {
                body.append(&section_title("曲"));
                body.append(&download_rows(&app, songs));
            }
            if !episodes.is_empty() {
                body.append(&section_title("エピソード"));
                body.append(&download_rows(&app, episodes));
            }
            if manifest.collections.is_empty() && manifest.singles.is_empty() && manifest.smart.is_empty() {
                body.append(&gtk::Label::builder().label("曲・プレイリスト・アルバムのメニューやページの頭のボタンから、ダウンロードできます。通信の無いところでも聴けます。").xalign(0.0).wrap(true).margin_top(16).css_classes(["dim-label"]).build());
            }
        })
    };
    *render.borrow_mut() = Some(render_fn.clone());
    {
        let render = render.clone();
        app.downloads.watch(&body, move |_| {
            if let Some(f) = render.borrow().clone() {
                f(false);
            }
        });
    }
    let page = adw::NavigationPage::builder().title("ダウンロード").tag("downloads").child(&scrolling_column(&column)).build();
    page.connect_destroy(move |_| {
        render.borrow_mut().take();
    });
    page
}

/// ダウンロードのページの曲の一覧（取り終えていない曲は薄く）。ダブルクリックで、その一覧をその曲から流す。
fn download_rows(app: &Rc<App>, songs: Vec<Song>) -> gtk::ListBox {
    let shown = songs.clone();
    let app_c = app.clone();
    let list = track::list(app, Row::numbered(songs.clone()), Columns::FULL, Rc::new(move |i| app_c.playback.play_list_from(shown.clone(), i, Some("downloads".into()))));
    for (i, song) in songs.iter().enumerate() {
        if let Some(row) = list.row_at_index(i as i32) {
            let (downloads, id) = (app.downloads.clone(), song.video_id.clone());
            app.downloads.watch(&row, move |row| {
                if downloads.state(&id) == crate::downloads::SongState::Done { row.remove_css_class("dim-row") } else { row.add_css_class("dim-row") }
            });
        }
    }
    list
}

/// 丸ごと取ったものの行（「種類 • 何曲（途中なら N / M 曲）」）。押すと開き、右クリックで「ダウンロードを削除」。
fn collection_row(app: &Rc<App>, c: wave_core::download::manifest::DownloadCollection) -> gtk::ListBoxRow {
    use wave_core::download::manifest::{KIND_ALBUM, KIND_LIKED};
    let line = gtk::Box::builder().spacing(12).height_request(60).build();
    if c.kind == KIND_LIKED {
        line.append(&track::cover("liked", 48));
    } else {
        let art = images::square(48);
        art.load(c.thumbnail_url.as_deref(), 96);
        line.append(&art.root);
    }
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).hexpand(true).build();
    text.append(&gtk::Label::builder().label(&c.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build());
    let kind = if c.kind == KIND_ALBUM { "アルバム" } else if c.kind == KIND_LIKED { "プレイリスト • 自動" } else { "プレイリスト" };
    let done = c.songs.iter().filter(|s| app.downloads.state(&s.video_id) == crate::downloads::SongState::Done).count();
    let count = if done >= c.songs.len() { format!("{} 曲", c.songs.len()) } else { format!("{done} / {} 曲", c.songs.len()) };
    text.append(&gtk::Label::builder().label(format!("{kind} • {count}")).xalign(0.0).css_classes(["caption", "dim-label"]).build());
    line.append(&text);
    let row = gtk::ListBoxRow::builder().child(&line).activatable(true).build();
    let click = gtk::GestureClick::builder().button(1).build();
    {
        let (app, c) = (app.clone(), c.clone());
        click.connect_released(move |_, _, _, _| {
            if c.kind == KIND_ALBUM {
                app.push(album(&app, &c.id, &c.title));
            } else if c.kind == KIND_LIKED {
                app.push(playlist(&app, LIKED_SONGS, "高く評価した曲"));
            } else {
                app.push(playlist(&app, &c.id, &c.title));
            }
        });
    }
    line.add_controller(click);
    let menu = gtk::gio::Menu::new();
    menu.append(Some("ダウンロードを削除"), Some("download.remove"));
    let actions = gtk::gio::SimpleActionGroup::new();
    let remove = gtk::gio::SimpleAction::new("remove", None);
    {
        let (app, id) = (app.clone(), c.id.clone());
        remove.connect_activate(move |_, _| app.downloads.remove_collection(&id));
    }
    actions.add_action(&remove);
    row.insert_action_group("download", Some(&actions));
    let right = gtk::GestureClick::builder().button(3).build();
    let weak = row.downgrade();
    right.connect_pressed(move |gesture, _, x, y| {
        gesture.set_state(gtk::EventSequenceState::Claimed);
        let Some(row) = weak.upgrade() else { return };
        let popover = gtk::PopoverMenu::from_model(Some(&menu));
        popover.set_parent(&row);
        popover.set_has_arrow(false);
        popover.set_pointing_to(Some(&gtk::gdk::Rectangle::new(x as i32, y as i32, 1, 1)));
        popover.connect_closed(|p| {
            let p = p.clone();
            gtk::glib::idle_add_local_once(move || p.unparent());
        });
        popover.popup();
    });
    row.add_controller(right);
    row
}
