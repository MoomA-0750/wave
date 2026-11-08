//! 検索のページ（Mac の SearchPage と同じ）。語の前は「最近の検索」（最大12件・×・すべて消去）と「すべてをブラウズ」（気分・ジャンルの大きなタイル）、
//! 語の後はタブ（すべて／曲／アルバム／アーティスト／ライブ／ポッドキャスト／エピソード／歌詞）。最初は「すべて」（トップの検索結果・曲4件・
//! アーティストの棚・アルバムの棚）。曲・アルバム・アーティストは同時に読み、ほかはタブを開いたときに読む。続きは下の端で自動で読む。
//! 曲はダブルクリックでその1曲＋ラジオ（検索の履歴に残す）。ページは1枚だけ積み、上の検索の欄から語を替える。

use std::cell::{Cell, RefCell};
use std::future::Future;
use std::pin::Pin;
use std::rc::{Rc, Weak};

use adw::prelude::*;
use wave_core::innertube::Failure;
use wave_core::music::models::{AlbumSummary, ArtistSummary, HomeItem, MoodCategory, Song};

use super::images;
use super::shell::App;
use super::track::{self, Columns, Row};
use crate::runtime;
use crate::settings::SearchHistoryEntry;

/// 最近の検索に出す数。
const RECENT_COUNT: usize = 12;

type Page<T> = Result<(Vec<T>, Option<String>), Failure>;
type Fetch<T> = Rc<dyn Fn(Option<String>) -> Pin<Box<dyn Future<Output = Page<T>>>>>;

thread_local! {
    /// 今の検索のページ（1枚だけ）。
    static CURRENT: RefCell<Option<Rc<Search>>> = const { RefCell::new(None) };
    /// 上の帯の検索の欄（最近の検索の語を押したときに、欄の文字も替える）。
    static ENTRY: RefCell<Option<gtk::glib::WeakRef<gtk::SearchEntry>>> = const { RefCell::new(None) };
    /// 気分・ジャンルのタイル（一度読んだら使い回す）。
    static MOODS: RefCell<Option<Vec<MoodCategory>>> = const { RefCell::new(None) };
}

pub struct Search {
    app: Rc<App>,
    page: adw::NavigationPage,
    stack: gtk::Stack,
    browse: adw::Bin,
    tabs: adw::ToggleGroup,
    content: gtk::Stack,
    query: RefCell<Option<String>>,
    /// 語を替えるたびに増やす（古い結果は捨てる）。
    request: Cell<u64>,
    /// 曲・アルバム・アーティストの最初のページ（「すべて」と各タブで使う）。
    first: RefCell<Option<FirstPages>>,
}

#[derive(Clone)]
struct FirstPages {
    songs: Page<Song>,
    albums: Page<AlbumSummary>,
    artists: Page<ArtistSummary>,
}

/// 上の帯の検索の欄を覚える（shell が呼ぶ）。
pub fn set_entry(entry: &gtk::SearchEntry) {
    ENTRY.with(|e| *e.borrow_mut() = Some(entry.downgrade()));
}

/// 今の検索のページ（いちばん上に出ているときだけ）。
fn visible(app: &App) -> Option<Rc<Search>> {
    let current = CURRENT.with(|c| c.borrow().clone())?;
    (app.visible_kind().as_deref() == Some("search") && current.page.is_mapped()).then_some(current)
}

/// 検索の欄に入った: 検索のページへ移る（もういれば何もしない）。
pub fn enter(app: &Rc<App>) {
    if app.visible_kind().as_deref() != Some("search") {
        app.push(page(app, None));
    }
}

/// 語で検索する（検索のページにいればその場で、いなければ開いて）。None は「すべてをブラウズ」に戻る。
pub fn open(app: &Rc<App>, query: Option<&str>) {
    match visible(app) {
        Some(current) => current.search(query.map(String::from)),
        None if query.is_some() => app.push(page(app, query)),
        None => {}
    }
}

/// 検索のページ。query が無ければ「最近の検索」と「すべてをブラウズ」。
pub fn page(app: &Rc<App>, query: Option<&str>) -> adw::NavigationPage {
    track::ensure_style();
    let stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).vexpand(true).build();
    let browse = adw::Bin::new();
    stack.add_named(&browse, Some("browse"));
    let tabs = adw::ToggleGroup::builder().halign(gtk::Align::Start).homogeneous(false).css_classes(["round"]).build();
    for (name, label) in [("all", "すべて"), ("songs", "曲"), ("albums", "アルバム"), ("artists", "アーティスト"), ("live", "ライブ"), ("podcasts", "ポッドキャスト"), ("episodes", "エピソード"), ("lyrics", "歌詞")] {
        // 既定の名前の文字は縮めると切れるので、切れない文字を中身にする
        tabs.add(adw::Toggle::builder().child(&gtk::Label::new(Some(label))).name(name).build());
    }
    tabs.set_active_name(Some("all"));
    let content = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).vexpand(true).build();
    let results = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).build();
    // 種類が多く欄に収まらないときは、名前を切らずに横に送れるようにする
    let tab_scroller = gtk::ScrolledWindow::builder().child(&tabs).vscrollbar_policy(gtk::PolicyType::Never).hscrollbar_policy(gtk::PolicyType::External).propagate_natural_height(true).margin_top(12).margin_start(16).margin_end(16).build();
    results.append(&tab_scroller);
    results.append(&content);
    stack.add_named(&results, Some("results"));
    let page = adw::NavigationPage::builder().title("検索").tag("search").child(&stack).build();
    let search = Rc::new(Search { app: app.clone(), page: page.clone(), stack, browse, tabs: tabs.clone(), content, query: RefCell::default(), request: Cell::new(0), first: RefCell::default() });
    {
        let weak = Rc::downgrade(&search);
        tabs.connect_active_name_notify(move |_| {
            if let Some(search) = weak.upgrade() {
                search.show_tab();
            }
        });
    }
    search.search(query.map(String::from));
    // 確かめ用: WAVE_DEMO_SEARCH_TAB にある種類（lyrics など）を開く
    if query.is_some()
        && let Ok(kind) = std::env::var("WAVE_DEMO_SEARCH_TAB")
    {
        tabs.set_active_name(Some(&kind));
    }
    CURRENT.with(|c| *c.borrow_mut() = Some(search));
    page
}

impl Search {
    /// 語を替える（None・空なら「すべてをブラウズ」）。
    pub fn search(self: &Rc<Self>, query: Option<String>) {
        let query = query.map(|q| q.trim().to_string()).filter(|q| !q.is_empty());
        self.request.set(self.request.get() + 1);
        *self.query.borrow_mut() = query.clone();
        *self.first.borrow_mut() = None;
        // 前の語の結果は捨てる
        while let Some(child) = self.content.first_child() {
            self.content.remove(&child);
        }
        match query {
            None => {
                self.page.set_title("検索");
                self.show_browse();
                self.stack.set_visible_child_name("browse");
            }
            Some(q) => {
                self.page.set_title(&format!("「{q}」の検索"));
                self.stack.set_visible_child_name("results");
                self.load_first(q);
                self.show_tab();
            }
        }
    }

    fn weak(self: &Rc<Self>) -> Weak<Self> {
        Rc::downgrade(self)
    }

    fn remember(&self, entry: SearchHistoryEntry) {
        self.app.remember_search(entry);
    }

    // MARK: 語の前

    fn show_browse(self: &Rc<Self>) {
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).margin_top(16).margin_bottom(32).margin_start(24).margin_end(24).build();
        let history: Vec<SearchHistoryEntry> = self.app.downloads.settings.borrow().search_history.iter().take(RECENT_COUNT).cloned().collect();
        if !history.is_empty() {
            let head = gtk::Box::builder().spacing(8).build();
            head.append(&gtk::Label::builder().label("最近の検索").xalign(0.0).hexpand(true).css_classes(["title-2"]).build());
            let clear = gtk::Button::builder().label("すべて消去").css_classes(["flat"]).build();
            {
                let weak = self.weak();
                clear.connect_clicked(move |_| {
                    if let Some(search) = weak.upgrade() {
                        search.app.downloads.update_settings(|s| s.search_history.clear());
                        search.show_browse();
                    }
                });
            }
            head.append(&clear);
            column.append(&head);
            let grid = gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).homogeneous(true).min_children_per_line(1).max_children_per_line(4).column_spacing(8).row_spacing(8).build();
            for entry in history {
                grid.append(&self.history_tile(entry));
            }
            column.append(&grid);
        }
        column.append(&gtk::Label::builder().label("すべてをブラウズ").xalign(0.0).margin_top(8).css_classes(["title-2"]).build());
        let tiles = gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).homogeneous(true).min_children_per_line(2).max_children_per_line(8).column_spacing(12).row_spacing(12).build();
        column.append(&tiles);
        let fill = {
            let (app, tiles) = (self.app.clone(), tiles.clone());
            move |moods: &[MoodCategory]| {
                for (i, mood) in moods.iter().enumerate() {
                    tiles.append(&mood_tile(&app, mood, i));
                }
            }
        };
        match MOODS.with(|m| m.borrow().clone()) {
            Some(moods) => fill(&moods),
            None => {
                let spinner = adw::Spinner::builder().width_request(24).height_request(24).halign(gtk::Align::Start).build();
                column.append(&spinner);
                let repo = self.app.repo.clone();
                gtk::glib::spawn_future_local(async move {
                    let result = runtime::run(async move { repo.moods().await }).await;
                    spinner.set_visible(false);
                    if let Ok(sections) = result {
                        let moods: Vec<MoodCategory> = sections.into_iter().flat_map(|s| s.categories).collect();
                        if !moods.is_empty() {
                            MOODS.with(|m| *m.borrow_mut() = Some(moods.clone()));
                        }
                        fill(&moods);
                    }
                });
            }
        }
        self.browse.set_child(Some(&gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build()));
    }

    /// 最近の検索の1件（画像・名前・補足・×）。押すと開く（語ならその語で検索）。
    fn history_tile(self: &Rc<Self>, entry: SearchHistoryEntry) -> gtk::Widget {
        let row = gtk::Box::builder().spacing(12).width_request(260).css_classes(["history-tile"]).build();
        if entry.kind == "query" {
            let icon = gtk::Box::builder().width_request(48).height_request(48).css_classes(["cover-query"]).build();
            icon.append(&gtk::Image::builder().icon_name("system-search-symbolic").hexpand(true).halign(gtk::Align::Center).build());
            row.append(&icon);
        } else {
            let art = images::square(48);
            if entry.kind == "artist" {
                art.add_css_class("round");
            }
            art.load(entry.thumbnail_url.as_deref(), 96);
            row.append(&art.root);
        }
        let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).hexpand(true).build();
        text.append(&gtk::Label::builder().label(&entry.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).build());
        if let Some(subtitle) = entry.subtitle.as_deref().filter(|s| !s.is_empty()) {
            text.append(&gtk::Label::builder().label(subtitle).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["caption", "dim-label"]).build());
        }
        row.append(&text);
        let remove = gtk::Button::builder().icon_name("window-close-symbolic").tooltip_text("履歴から削除").valign(gtk::Align::Center).css_classes(["flat", "circular"]).build();
        {
            let (weak, key) = (self.weak(), entry.key());
            remove.connect_clicked(move |_| {
                if let Some(search) = weak.upgrade() {
                    search.app.downloads.update_settings(|s| s.search_history.retain(|e| e.key() != key));
                    search.show_browse();
                }
            });
        }
        row.append(&remove);
        row.set_cursor_from_name(Some("pointer"));
        let click = gtk::GestureClick::new();
        let weak = self.weak();
        click.connect_released(move |gesture, _, _, _| {
            gesture.set_state(gtk::EventSequenceState::Claimed);
            let Some(search) = weak.upgrade() else { return };
            let app = search.app.clone();
            app.remember_search(entry.clone());
            if entry.kind == "podcast"
                && let Some(id) = &entry.id
            {
                app.push(super::podcasts::show(&app, id, &entry.title));
            } else if let Some(item) = entry.item() {
                track::open_item(&app, &item);
            } else {
                if let Some(e) = ENTRY.with(|e| e.borrow().as_ref().and_then(|w| w.upgrade())) {
                    e.set_text(&entry.title);
                }
                search.search(Some(entry.title.clone()));
            }
        });
        row.add_controller(click);
        row.upcast()
    }

    // MARK: 語の後

    /// 曲・アルバム・アーティストを同時に読む。
    fn load_first(self: &Rc<Self>, query: String) {
        let request = self.request.get();
        let weak = self.weak();
        let repo = self.app.repo.clone();
        gtk::glib::spawn_future_local(async move {
            let first = runtime::run(async move {
                let (songs, albums, artists) = tokio::join!(repo.search_songs(&query, None), repo.search_albums(&query, None), repo.search_artists(&query, None));
                FirstPages { songs: songs.map(|p| (p.songs, p.continuation)), albums: albums.map(|p| (p.items, p.continuation)), artists: artists.map(|p| (p.items, p.continuation)) }
            })
            .await;
            let Some(search) = weak.upgrade() else { return };
            if search.request.get() != request {
                return;
            }
            *search.first.borrow_mut() = Some(first);
            // 読み終えるまで回る印を出していたタブを出し直す
            for name in ["all", "songs", "albums", "artists"] {
                if let Some(child) = search.content.child_by_name(name) {
                    search.content.remove(&child);
                }
            }
            search.show_tab();
        });
    }

    fn show_tab(self: &Rc<Self>) {
        let Some(name) = self.tabs.active_name().map(|n| n.to_string()) else { return };
        if self.query.borrow().is_none() {
            return;
        }
        if self.content.child_by_name(&name).is_none() {
            let widget = self.tab(&name);
            self.content.add_named(&widget, Some(&name));
        }
        self.content.set_visible_child_name(&name);
    }

    fn tab(self: &Rc<Self>, name: &str) -> gtk::Widget {
        let query = self.query.borrow().clone().unwrap_or_default();
        let first = self.first.borrow().clone();
        let needs_first = matches!(name, "all" | "songs" | "albums" | "artists");
        if needs_first && first.is_none() {
            return spinner();
        }
        let repo = self.app.repo.clone();
        match name {
            "all" => self.all(first.expect("checked above")),
            "songs" => {
                let q = query.clone();
                let fetch: Fetch<Song> = Rc::new(move |t| {
                    let (repo, q) = (repo.clone(), q.clone());
                    Box::pin(runtime::run(async move { repo.search_songs(&q, t.as_deref()).await.map(|p| (p.songs, p.continuation)) }))
                });
                self.song_pager(first.map(|f| f.songs), fetch, "見つかりませんでした", "検索できませんでした")
            }
            "live" => {
                let q = query.clone();
                let fetch: Fetch<Song> = Rc::new(move |t| {
                    let (repo, q) = (repo.clone(), q.clone());
                    Box::pin(runtime::run(async move { repo.search_live(&q, t.as_deref()).await.map(|p| (p.songs, p.continuation)) }))
                });
                self.song_pager(None, fetch, "配信中のライブは見つかりませんでした", "ライブ配信を検索できませんでした")
            }
            "albums" => {
                let q = query.clone();
                let fetch: Fetch<AlbumSummary> = Rc::new(move |t| {
                    let (repo, q) = (repo.clone(), q.clone());
                    Box::pin(runtime::run(async move { repo.search_albums(&q, t.as_deref()).await.map(|p| (p.items, p.continuation)) }))
                });
                self.card_pager(first.map(|f| f.albums), fetch, HomeItem::Album)
            }
            "artists" => {
                let q = query.clone();
                let fetch: Fetch<ArtistSummary> = Rc::new(move |t| {
                    let (repo, q) = (repo.clone(), q.clone());
                    Box::pin(runtime::run(async move { repo.search_artists(&q, t.as_deref()).await.map(|p| (p.items, p.continuation)) }))
                });
                self.card_pager(first.map(|f| f.artists), fetch, HomeItem::Artist)
            }
            "podcasts" => self.podcast_pager(query),
            "episodes" => self.episode_pager(query),
            _ => self.lyrics(query),
        }
    }

    /// 「すべて」: トップの検索結果・曲4件・アーティストの棚・アルバムの棚。
    fn all(self: &Rc<Self>, first: FirstPages) -> gtk::Widget {
        let query = self.query.borrow().clone().unwrap_or_default();
        if first.songs.is_err() && first.albums.is_err() && first.artists.is_err() {
            let failure = first.songs.clone().err().unwrap_or(Failure::Network);
            let weak = self.weak();
            return failed("検索できませんでした", &failure, move || {
                if let Some(search) = weak.upgrade() {
                    let q = search.query.borrow().clone();
                    search.search(q);
                }
            });
        }
        let songs = first.songs.map(|p| p.0).unwrap_or_default();
        let albums = first.albums.map(|p| p.0).unwrap_or_default();
        let artists = first.artists.map(|p| p.0).unwrap_or_default();
        if songs.is_empty() && albums.is_empty() && artists.is_empty() {
            return empty("見つかりませんでした");
        }
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(28).margin_top(16).margin_bottom(32).margin_start(16).margin_end(16).build();
        // 中央が狭いときは、トップの検索結果の下に曲を並べる（横に並べると曲の題が潰れ、窓の幅を押し広げる）
        let top_row = gtk::FlowBox::builder().homogeneous(true).min_children_per_line(1).max_children_per_line(2).column_spacing(24).row_spacing(28).selection_mode(gtk::SelectionMode::None).build();
        if let Some(top) = wave_core::music::collection::top_result(&query, &songs, &artists, &albums) {
            let side = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).width_request(340).build();
            side.append(&gtk::Label::builder().label("トップの検索結果").xalign(0.0).css_classes(["title-2"]).build());
            side.append(&self.top_card(top));
            top_row.insert(&side, -1);
        }
        if !songs.is_empty() {
            let side = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).width_request(340).hexpand(true).build();
            side.append(&gtk::Label::builder().label("曲").xalign(0.0).css_classes(["title-2"]).build());
            let shown: Vec<Song> = songs.iter().take(4).cloned().collect();
            side.append(&self.track_list(shown, Columns::SHORT).0);
            top_row.insert(&side, -1);
        }
        column.append(&top_row);
        let remember = |item: &HomeItem| -> Option<Rc<dyn Fn()>> {
            let (weak, entry) = (self.weak(), SearchHistoryEntry::of(item));
            Some(Rc::new(move || {
                if let Some(search) = weak.upgrade() {
                    search.remember(entry.clone());
                }
            }))
        };
        if !artists.is_empty() {
            let cards = artists.into_iter().map(HomeItem::Artist).map(|item| track::card(&self.app, item.clone(), remember(&item))).collect();
            column.append(&track::shelf_with(&self.app, "アーティスト", None, cards));
        }
        if !albums.is_empty() {
            let cards = albums.into_iter().map(HomeItem::Album).map(|item| track::card(&self.app, item.clone(), remember(&item))).collect();
            column.append(&track::shelf_with(&self.app, "アルバム", None, cards));
        }
        gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build().upcast()
    }

    /// トップの検索結果のカード（押すと開き、右下の再生ボタンで流す）。
    fn top_card(self: &Rc<Self>, item: HomeItem) -> gtk::Widget {
        let card = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).css_classes(["top-card"]).build();
        let art = images::square(96);
        if matches!(item, HomeItem::Artist(_)) {
            art.add_css_class("round");
        }
        art.load(item.thumbnail_url(), 192);
        card.append(&art.root);
        card.append(&gtk::Label::builder().label(item.title()).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["title-1"]).build());
        let subtitle = match &item {
            HomeItem::Song(s) => format!("曲 • {}", s.artists_text()),
            HomeItem::Artist(_) => "アーティスト".into(),
            HomeItem::Album(a) => a.subtitle.clone().unwrap_or_else(|| "アルバム".into()),
            HomeItem::Playlist(p) => p.subtitle.clone().unwrap_or_default(),
        };
        let bottom = gtk::Box::builder().spacing(8).build();
        bottom.append(&gtk::Label::builder().label(subtitle).xalign(0.0).hexpand(true).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["dim-label"]).build());
        let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").css_classes(["circular", "suggested-action", "play-fab"]).build();
        bottom.append(&play);
        card.append(&bottom);
        card.set_cursor_from_name(Some("pointer"));
        let item = Rc::new(item);
        {
            let (weak, item) = (self.weak(), item.clone());
            play.connect_clicked(move |_| {
                if let Some(search) = weak.upgrade() {
                    search.remember(SearchHistoryEntry::of(&item));
                    track::play_item(&search.app, &item);
                }
            });
        }
        let click = gtk::GestureClick::new();
        let weak = self.weak();
        click.connect_released(move |gesture, _, _, _| {
            gesture.set_state(gtk::EventSequenceState::Claimed);
            if let Some(search) = weak.upgrade() {
                search.remember(SearchHistoryEntry::of(&item));
                track::open_item(&search.app, &item);
            }
        });
        card.add_controller(click);
        card.upcast()
    }

    /// 曲の一覧（ダブルクリックでその1曲＋ラジオ、履歴に残す）。
    /// 押した行の曲を引けるよう、出している曲も返す（続きの行を足すときに足す）。
    fn track_list(self: &Rc<Self>, songs: Vec<Song>, columns: Columns) -> (gtk::ListBox, Rc<RefCell<Vec<Song>>>) {
        let shown: Rc<RefCell<Vec<Song>>> = Rc::new(RefCell::new(songs.clone()));
        let weak = self.weak();
        let rows = Row::numbered(songs);
        let list = track::list(&self.app, rows, columns, {
            let shown = shown.clone();
            Rc::new(move |i| {
                let Some(search) = weak.upgrade() else { return };
                if let Some(song) = shown.borrow().get(i).cloned() {
                    search.remember(SearchHistoryEntry::song(&song));
                    search.app.playback.play_list(vec![song], 0);
                }
            })
        });
        (list, shown)
    }

    fn song_pager(self: &Rc<Self>, first: Option<Page<Song>>, fetch: Fetch<Song>, empty_text: &str, failure_title: &str) -> gtk::Widget {
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).margin_top(8).margin_bottom(16).margin_start(12).margin_end(12).build();
        column.append(&track::header(Columns::FULL));
        let (list, shown) = self.track_list(Vec::new(), Columns::FULL);
        column.append(&list);
        let app = self.app.clone();
        let add = move |songs: Vec<Song>| {
            let start = shown.borrow().len();
            let rows = songs.iter().cloned().enumerate().map(|(i, song)| Row { number: start + i + 1, song, removal: None }).collect();
            shown.borrow_mut().extend(songs);
            track::append(&app, &list, rows, Columns::FULL);
        };
        pager(first, fetch, column.upcast(), Box::new(add), empty_text, failure_title)
    }

    fn card_pager<T: Clone + 'static>(self: &Rc<Self>, first: Option<Page<T>>, fetch: Fetch<T>, wrap: fn(T) -> HomeItem) -> gtk::Widget {
        let grid = track::card_grid(Vec::new());
        grid.set_margin_top(8);
        grid.set_margin_start(16);
        grid.set_margin_end(16);
        grid.set_margin_bottom(16);
        let weak = self.weak();
        let add = {
            let grid = grid.clone();
            move |items: Vec<T>| {
                let Some(search) = weak.upgrade() else { return };
                for item in items.into_iter().map(wrap) {
                    let (w, entry) = (Rc::downgrade(&search), SearchHistoryEntry::of(&item));
                    let remember: Rc<dyn Fn()> = Rc::new(move || {
                        if let Some(s) = w.upgrade() {
                            s.remember(entry.clone());
                        }
                    });
                    grid.append(&track::card(&search.app, item, Some(remember)));
                }
            }
        };
        pager(first, fetch, grid.upcast(), Box::new(add), "見つかりませんでした", "検索できませんでした")
    }

    /// ポッドキャスト: Apple の目録の番組（RSS）を先に、YouTube の番組を後に、カードで（RSS の URL を打てば、その番組）。
    fn podcast_pager(self: &Rc<Self>, query: String) -> gtk::Widget {
        let (repo, client) = (self.app.repo.clone(), self.app.podcasts.client.clone());
        let fetch: Fetch<wave_core::music::podcast::PodcastSummary> = Rc::new(move |t| {
            let (repo, client, q) = (repo.clone(), client.clone(), query.clone());
            Box::pin(runtime::run(async move {
                if let Some(t) = t {
                    return repo.search_podcasts(&q, Some(&t)).await.map(|p| (p.items, p.continuation));
                }
                let (apple, youtube) = tokio::join!(client.search(&q), repo.search_podcasts(&q, None));
                if apple.is_none()
                    && let Err(e) = youtube
                {
                    return Err(e);
                }
                let mut items = apple.unwrap_or_default();
                let continuation = youtube.as_ref().ok().and_then(|p| p.continuation.clone());
                for show in youtube.map(|p| p.items).unwrap_or_default() {
                    if !items.iter().any(|i| i.browse_id == show.browse_id) {
                        items.push(show);
                    }
                }
                Ok((items, continuation))
            }))
        });
        let grid = track::card_grid(Vec::new());
        grid.set_margin_top(8);
        grid.set_margin_start(16);
        grid.set_margin_end(16);
        let weak = self.weak();
        let add = {
            let grid = grid.clone();
            move |shows: Vec<wave_core::music::podcast::PodcastSummary>| {
                let Some(search) = weak.upgrade() else { return };
                for show in shows {
                    let (w, entry) = (Rc::downgrade(&search), SearchHistoryEntry::podcast(&show));
                    let remember: Rc<dyn Fn()> = Rc::new(move || {
                        if let Some(s) = w.upgrade() {
                            s.remember(entry.clone());
                        }
                    });
                    grid.append(&super::podcasts::card(&search.app, &show, Some(remember)));
                }
            }
        };
        let body = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        body.append(&gtk::Label::builder().label("番組名のほか、RSS の URL（https://…）でも探せます").xalign(0.0).margin_start(24).margin_top(4).css_classes(["caption", "dim-label"]).build());
        body.append(&grid);
        pager(None, fetch, body.upcast(), Box::new(add), "見つかりませんでした", "検索できませんでした")
    }

    /// エピソード（押したエピソードだけを流す）。
    fn episode_pager(self: &Rc<Self>, query: String) -> gtk::Widget {
        let repo = self.app.repo.clone();
        let fetch: Fetch<wave_core::music::podcast::PodcastEpisode> = Rc::new(move |t| {
            let (repo, q) = (repo.clone(), query.clone());
            Box::pin(runtime::run(async move { repo.search_episodes(&q, t.as_deref()).await.map(|p| (p.items, p.continuation)) }))
        });
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["track-list"]).margin_top(8).margin_start(12).margin_end(12).build();
        let app = self.app.clone();
        let add = {
            let list = list.clone();
            move |episodes: Vec<wave_core::music::podcast::PodcastEpisode>| {
                for episode in episodes {
                    let song = episode.song.clone();
                    let app_c = app.clone();
                    list.append(&super::podcasts::episode_row(&app, &episode, true, Rc::new(move || app_c.playback.play_list(vec![song.clone()], 0))));
                }
            }
        };
        pager(None, fetch, list.upcast(), Box::new(add), "見つかりませんでした", "検索できませんでした")
    }

    /// 歌詞: 合った一節を曲の下にアクセントの色で引用する（大体合っているときは「≈ 」）。続きは無い。
    fn lyrics(self: &Rc<Self>, query: String) -> gtk::Widget {
        let (repo, lyrics) = (self.app.repo.clone(), self.app.lyrics.clone());
        let fetch: Fetch<wave_core::lyrics::search::LyricsHit> = Rc::new(move |_| {
            let (repo, lyrics, q) = (repo.clone(), lyrics.clone(), query.clone());
            Box::pin(runtime::run(async move {
                wave_core::lyrics::search::search(&q, |q| async move { repo.search_top_songs(&q).await }, |song| {
                    let lyrics = lyrics.clone();
                    async move { lyrics.lyrics(&song).await }
                })
                .await
                .map(|hits| (hits, None))
            }))
        });
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).margin_top(8).margin_start(12).margin_end(12).build();
        column.append(&track::header(Columns::FULL));
        let (list, shown) = self.track_list(Vec::new(), Columns::FULL);
        column.append(&list);
        let app = self.app.clone();
        let add = move |hits: Vec<wave_core::lyrics::search::LyricsHit>| {
            for hit in hits {
                let number = shown.borrow().len() + 1;
                shown.borrow_mut().push(hit.song.clone());
                let row = track::track_row(&app, Row { number, song: hit.song, removal: None }, Columns::FULL);
                if let Some(m) = hit.matched {
                    let quote = format!("{}\u{201C}{}\u{201D}", if m.exact { "" } else { "≈ " }, m.line);
                    if let Some(line) = row.child() {
                        row.set_child(None::<&gtk::Widget>);
                        let stack = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
                        stack.append(&line);
                        // 番号・画像の幅だけ下げて、曲名の下にそろえる
                        stack.append(&gtk::Label::builder().label(quote).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).margin_start(96).margin_bottom(8).css_classes(["accent-text"]).build());
                        row.set_child(Some(&stack));
                    }
                }
                list.append(&row);
            }
        };
        pager(None, fetch, column.upcast(), Box::new(add), "見つかりませんでした", "歌詞から探せませんでした")
    }
}

/// 続きを下の端で読む一覧。first があれば最初のページとして使う（無ければ読む）。0件は empty_text、失敗は理由と「もう一度」。
fn pager<T: 'static>(first: Option<Page<T>>, fetch: Fetch<T>, content: gtk::Widget, add: Box<dyn Fn(Vec<T>)>, empty_text: &str, failure_title: &str) -> gtk::Widget {
    let holder = adw::Bin::builder().child(&spinner()).vexpand(true).build();
    let body = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    body.append(&content);
    let more = adw::Spinner::builder().width_request(24).height_request(24).margin_top(8).margin_bottom(8).halign(gtk::Align::Center).visible(false).build();
    body.append(&more);
    let scroller = gtk::ScrolledWindow::builder().child(&body).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
    struct State<T> {
        continuation: RefCell<Option<String>>,
        loading: Cell<bool>,
        started: Cell<bool>,
        fetch: Fetch<T>,
        add: Box<dyn Fn(Vec<T>)>,
    }
    let state = Rc::new(State { continuation: RefCell::default(), loading: Cell::new(false), started: Cell::new(false), fetch, add });
    let (empty_text, failure_title) = (empty_text.to_string(), failure_title.to_string());
    // 届いたページを出す（最初のページなら、0件・失敗を出し分ける）
    type Apply<T> = Rc<RefCell<Option<Box<dyn Fn(bool, Page<T>)>>>>;
    let apply: Apply<T> = Rc::default();
    let load: Rc<dyn Fn(bool)> = {
        let (state, apply, more, holder) = (state.clone(), apply.clone(), more.clone(), holder.clone());
        Rc::new(move |next: bool| {
            if state.loading.get() {
                return;
            }
            let token = state.continuation.borrow().clone();
            if next && token.is_none() {
                return;
            }
            state.loading.set(true);
            if next {
                more.set_visible(true);
            } else {
                holder.set_child(Some(&spinner()));
            }
            let (state, apply) = (state.clone(), apply.clone());
            let future = (state.fetch)(if next { token } else { None });
            gtk::glib::spawn_future_local(async move {
                let result = future.await;
                state.loading.set(false);
                if let Some(apply) = apply.borrow().as_ref() {
                    apply(next, result);
                }
            });
        })
    };
    *apply.borrow_mut() = Some({
        let (state, holder, scroller, more, load) = (state.clone(), holder.clone(), scroller.clone(), more.clone(), Rc::downgrade(&load));
        Box::new(move |next: bool, result: Page<T>| {
            more.set_visible(false);
            match result {
                Ok((items, token)) => {
                    if !next && items.is_empty() {
                        holder.set_child(Some(&empty(&empty_text)));
                        return;
                    }
                    // 同じ続きの印が返ったら、それ以上は読まない
                    let same = next && token.is_some() && *state.continuation.borrow() == token;
                    *state.continuation.borrow_mut() = if same { None } else { token };
                    (state.add)(items);
                    if !next {
                        state.started.set(true);
                        holder.set_child(Some(&scroller));
                    }
                }
                Err(e) if !next => {
                    let load = load.clone();
                    holder.set_child(Some(&failed(&failure_title, &e, move || {
                        if let Some(load) = load.upgrade() {
                            load(false);
                        }
                    })));
                }
                // 続きが読めなかったときは、下の端でもう一度読む
                Err(_) => {}
            }
        })
    });
    {
        let (load, state) = (load.clone(), state.clone());
        track::on_near_bottom(&scroller, move || {
            if state.started.get() {
                load(true);
            }
        });
    }
    match first {
        Some(page) => {
            if let Some(apply) = apply.borrow().as_ref() {
                apply(false, page);
            }
        }
        None => load(false),
    }
    holder.upcast()
}

fn spinner() -> gtk::Widget {
    adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).valign(gtk::Align::Start).margin_top(48).build().upcast()
}

fn empty(text: &str) -> gtk::Widget {
    gtk::Label::builder().label(text).valign(gtk::Align::Start).margin_top(64).css_classes(["title-3"]).build().upcast()
}

/// 読み込みの失敗: 0件と分けて、理由と「もう一度」を出す。
fn failed(title: &str, failure: &Failure, retry: impl Fn() + 'static) -> gtk::Widget {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).halign(gtk::Align::Center).valign(gtk::Align::Start).margin_top(64).build();
    column.append(&gtk::Label::builder().label(title).css_classes(["title-3"]).build());
    column.append(&gtk::Label::builder().label(failure_text(failure)).css_classes(["dim-label"]).build());
    let button = gtk::Button::builder().label("もう一度").halign(gtk::Align::Center).css_classes(["pill"]).build();
    button.connect_clicked(move |_| retry());
    column.append(&button);
    column.upcast()
}

pub fn failure_text(e: &Failure) -> String {
    match e {
        Failure::NotLoggedIn => "ログインしていません".into(),
        Failure::Network => "通信できませんでした".into(),
        Failure::Parse => "応答を読み取れませんでした".into(),
        Failure::Http(code) => format!("応答がありませんでした（{code}）"),
    }
}

/// 気分・ジャンルのタイル（区分の色で塗った大きなタイル。白い文字が読めるよう明るすぎる色は暗くする。色が無ければ決まった色を順に）。
fn mood_tile(app: &Rc<App>, mood: &MoodCategory, index: usize) -> gtk::Widget {
    let label = gtk::Label::builder().label(&mood.title).xalign(0.0).yalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).hexpand(true).vexpand(true).build();
    let button = gtk::Button::builder().child(&label).height_request(110).css_classes(["mood-tile", "flat"]).build();
    let (r, g, b) = tile_color(mood.color, index);
    let provider = gtk::CssProvider::new();
    provider.load_from_string(&format!("button.mood-tile {{ background-color: rgb({r},{g},{b}); }} button.mood-tile:hover {{ background-color: rgba({r},{g},{b},0.85); }}"));
    #[allow(deprecated)] // 1つの部品だけの色。テーマ全体には足さない
    button.style_context().add_provider(&provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION + 1);
    let (app, title, params) = (app.clone(), mood.title.clone(), mood.params.clone());
    button.connect_clicked(move |_| app.push(super::pages::mood_category(&app, &title, &params)));
    button.upcast()
}

pub use wave_desktop::art::tile_color;
