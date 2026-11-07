//! 左の欄のマイライブラリ（Mac の LibraryPane・LibraryEntry と同じ）。見出しは「マイライブラリ」と「＋」（プレイリストを作る。ログイン中だけ）、
//! 種類のチップ（プレイリスト・アーティスト・アルバム・ポッドキャスト。もう一度押すと外す）、検索の欄と、その右に今の並べ方を文字で出す並べ替え
//! （最近・最近追加した順・アルファベット順）とリスト・グリッドの切り替え。先頭は高く評価した曲（ジャケットの絵）→ ダウンロード
//! （「プレイリスト • 自動 • N 曲」）。ポッドキャストで絞ったときだけ「新しいエピソード」を一番上に。読み込み中は進み具合のバー。
//! 各行の右クリックで「再生」。グリッドは名前だけ。中の検索は大文字・小文字、全角・半角、ひらがな・カタカナを区別しない。

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::{Rc, Weak};

use adw::prelude::*;
use wave_core::lyrics::search::normalize;
use wave_core::music::models::{AlbumSummary, ArtistSummary, HomeItem, PlaylistSummary};
use wave_core::music::podcast::PodcastSummary;
use wave_core::music::repository::LIKED_SONGS;

use super::images;
use super::shell::App;
use super::track;
use crate::runtime;

thread_local! {
    /// 今のマイライブラリ（プレイリストを作った・番組を保存したあとに読み直す）。
    static CURRENT: RefCell<Weak<View>> = const { RefCell::new(Weak::new()) };
}

/// 一覧の1行の行き先。
#[derive(Clone)]
enum Target {
    Liked,
    Downloads,
    NewEpisodes,
    Item(HomeItem),
    Podcast(PodcastSummary),
}

#[derive(Clone)]
struct Entry {
    title: String,
    subtitle: String,
    thumbnail: Option<String>,
    round: bool,
    target: Target,
    /// 「最近」で並べるときの目印（Windows の LibraryState.KeyOf と同じ形）。
    key: Option<String>,
}

#[derive(Default)]
struct Data {
    playlists: Vec<Entry>,
    artists: Vec<Entry>,
    albums: Vec<Entry>,
    podcasts: Vec<Entry>,
    recent: Vec<String>,
    /// 読み終えていない種類の数（進み具合のバー）。
    pending: usize,
}

struct View {
    app: Rc<App>,
    data: RefCell<Data>,
    kind: RefCell<Option<&'static str>>,
    query: RefCell<String>,
    list: gtk::ListBox,
    grid: gtk::FlowBox,
    stack: gtk::Stack,
    progress: gtk::ProgressBar,
    shown: RefCell<Vec<Entry>>,
}

const KINDS: [(&str, &str); 4] = [("playlist", "プレイリスト"), ("artist", "アーティスト"), ("album", "アルバム"), ("podcast", "ポッドキャスト")];
const SORTS: [(&str, &str); 3] = [("recent", "最近"), ("added", "最近追加した順"), ("alpha", "アルファベット順")];

/// マイライブラリを読み直す（プレイリストを作った・番組を保存したあと）。
pub fn reload(_app: &Rc<App>) {
    if let Some(view) = CURRENT.with(|c| c.borrow().upgrade()) {
        view.load();
    }
}

pub fn sidebar(app: &Rc<App>) -> gtk::Widget {
    track::ensure_style();
    // ログインし直したら（窓の中身を作り直したら）、前のアカウントの評価を忘れる
    track::forget_liked();
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    let header = gtk::Box::builder().spacing(8).margin_top(12).margin_bottom(4).margin_start(16).margin_end(8).build();
    header.append(&gtk::Image::from_icon_name("library-music-symbolic"));
    header.append(&gtk::Label::builder().label("マイライブラリ").xalign(0.0).hexpand(true).css_classes(["heading"]).build());
    let logged_in = app.repo.client().is_logged_in();
    let create = gtk::Button::builder().icon_name("list-add-symbolic").tooltip_text("プレイリストを作成").visible(logged_in).css_classes(["flat", "circular"]).build();
    header.append(&create);
    column.append(&header);

    let (chip_scroller, chips) = track::chip_row();
    chip_scroller.set_margin_start(12);
    chip_scroller.set_margin_end(12);
    chip_scroller.set_margin_top(4);
    chip_scroller.set_margin_bottom(4);
    column.append(&chip_scroller);
    let tools = gtk::Box::builder().spacing(4).margin_start(12).margin_end(8).margin_top(4).margin_bottom(4).build();
    let search = gtk::SearchEntry::builder().placeholder_text("マイライブラリを検索").hexpand(true).build();
    tools.append(&search);
    let sort_label = gtk::Label::builder().css_classes(["caption"]).build();
    let sort = gtk::MenuButton::builder().child(&sort_label).tooltip_text("並べ替え").css_classes(["flat"]).build();
    tools.append(&sort);
    let layout = gtk::Button::builder().css_classes(["flat", "circular"]).build();
    tools.append(&layout);
    column.append(&tools);

    let list = gtk::ListBox::builder().css_classes(["navigation-sidebar"]).build();
    let grid = gtk::FlowBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .homogeneous(true)
        .min_children_per_line(2)
        .max_children_per_line(4)
        .column_spacing(8)
        .row_spacing(8)
        .margin_start(12)
        .margin_end(12)
        .valign(gtk::Align::Start)
        .build();
    let stack = gtk::Stack::new();
    stack.add_named(&gtk::ScrolledWindow::builder().child(&list).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build(), Some("list"));
    stack.add_named(&gtk::ScrolledWindow::builder().child(&grid).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build(), Some("grid"));
    let empty = gtk::Label::builder().label("ログインすると、YouTube Music のプレイリスト・アーティスト・アルバムがここに並びます。").wrap(true).xalign(0.0).valign(gtk::Align::Start).margin_start(16).margin_end(16).margin_top(12).build();
    stack.add_named(&empty, Some("logged-out"));
    let progress = gtk::ProgressBar::builder().margin_start(12).margin_end(12).visible(false).build();
    column.append(&progress);
    column.append(&stack);

    let view = Rc::new(View { app: app.clone(), data: RefCell::default(), kind: RefCell::default(), query: RefCell::default(), list: list.clone(), grid: grid.clone(), stack, progress, shown: RefCell::default() });
    CURRENT.with(|c| *c.borrow_mut() = Rc::downgrade(&view));
    {
        let view = view.clone();
        create.connect_clicked(move |_| {
            let weak = Rc::downgrade(&view);
            super::song_menu::create_playlist(&view.app, None, Some(Rc::new(move || {
                if let Some(view) = weak.upgrade() {
                    view.load();
                }
            })));
        });
    }

    // 種類の絞り込み（もう一度押すと外す）
    let buttons: Rc<Vec<(gtk::Button, &'static str)>> = Rc::new(
        KINDS
            .iter()
            .map(|(kind, title)| {
                let button = track::chip(title, false);
                chips.append(&button);
                (button, *kind)
            })
            .collect(),
    );
    for (button, kind) in buttons.iter() {
        let (view, buttons, kind) = (view.clone(), buttons.clone(), *kind);
        button.connect_clicked(move |_| {
            let next = if *view.kind.borrow() == Some(kind) { None } else { Some(kind) };
            *view.kind.borrow_mut() = next;
            for (b, k) in buttons.iter() {
                track::set_chip(b, next == Some(*k));
            }
            view.render();
        });
    }
    {
        let view = view.clone();
        search.connect_search_changed(move |entry| {
            *view.query.borrow_mut() = entry.text().to_string();
            view.render();
        });
    }

    // 並べ替え（今の並べ方を文字で出す）
    let menu = gtk::gio::Menu::new();
    for (key, label) in SORTS {
        menu.append(Some(label), Some(&format!("library.sort::{key}")));
    }
    sort.set_menu_model(Some(&menu));
    let show_sort = {
        let (view, sort_label) = (view.clone(), sort_label.clone());
        move || {
            let current = view.app.downloads.settings.borrow().library_sort.clone();
            sort_label.set_label(SORTS.iter().find(|(k, _)| *k == current).map_or("最近", |(_, l)| l));
        }
    };
    show_sort();
    let actions = gtk::gio::SimpleActionGroup::new();
    let current = app.downloads.settings.borrow().library_sort.clone();
    let sort_action = gtk::gio::SimpleAction::new_stateful("sort", Some(gtk::glib::VariantTy::STRING), &current.to_variant());
    {
        let view = view.clone();
        sort_action.connect_activate(move |action, value| {
            let Some(value) = value.and_then(|v| v.get::<String>()) else { return };
            action.set_state(&value.to_variant());
            view.app.downloads.update_settings(|s| s.library_sort = value.clone());
            show_sort();
            view.render();
        });
    }
    actions.add_action(&sort_action);
    column.insert_action_group("library", Some(&actions));

    // リストとグリッド（押すと切り替わる先の絵）
    let show_layout = {
        let (view, layout) = (view.clone(), layout.clone());
        move || {
            let grid = view.app.downloads.settings.borrow().library_grid;
            layout.set_icon_name(if grid { "view-list-symbolic" } else { "view-grid-symbolic" });
            layout.set_tooltip_text(Some(if grid { "リストで表示" } else { "グリッドで表示" }));
            view.show_stack();
        }
    };
    show_layout();
    {
        let (view, show_layout) = (view.clone(), show_layout.clone());
        layout.connect_clicked(move |_| {
            view.app.downloads.update_settings(|s| s.library_grid = !s.library_grid);
            show_layout();
        });
    }

    {
        let view = view.clone();
        list.connect_row_activated(move |_, row| view.open(row.index() as usize));
    }
    {
        let view = view.clone();
        grid.connect_child_activated(move |_, child| view.open(child.index() as usize));
    }
    // RSS の登録が変わったら（ほかの端末から届いたときも）出し直す（変わったかを数秒おきに見る）
    {
        let weak = Rc::downgrade(&view);
        let seen = RefCell::new(feeds_signature(app));
        gtk::glib::timeout_add_local(std::time::Duration::from_secs(3), move || {
            let Some(view) = weak.upgrade() else { return gtk::glib::ControlFlow::Break };
            let now = feeds_signature(&view.app);
            if *seen.borrow() != now {
                *seen.borrow_mut() = now;
                view.render();
            }
            gtk::glib::ControlFlow::Continue
        });
    }
    // ダウンロードの曲数が変わったら出し直す
    {
        let weak = Rc::downgrade(&view);
        let count = std::cell::Cell::new(usize::MAX);
        app.downloads.watch(&column, move |_| {
            let Some(view) = weak.upgrade() else { return };
            let now = downloaded_count(&view.app);
            if count.replace(now) != now {
                view.render();
            }
        });
    }
    view.render();
    view.load();
    // 読み込み中は進み具合のバーを動かす
    {
        let weak = Rc::downgrade(&view);
        gtk::glib::timeout_add_local(std::time::Duration::from_millis(120), move || {
            let Some(view) = weak.upgrade() else { return gtk::glib::ControlFlow::Break };
            let loading = view.data.borrow().pending > 0;
            view.progress.set_visible(loading);
            if loading {
                view.progress.pulse();
            }
            gtk::glib::ControlFlow::Continue
        });
    }
    column.upcast()
}

/// 取り終えた曲の数（「ダウンロード」の行の補足）。
fn downloaded_count(app: &App) -> usize {
    app.downloads.manifest().wanted_songs().iter().filter(|s| app.downloads.state(&s.video_id) == crate::downloads::SongState::Done).count()
}

impl View {
    fn show_stack(&self) {
        let name = if !self.app.repo.client().is_logged_in() {
            "logged-out"
        } else if self.app.downloads.settings.borrow().library_grid {
            "grid"
        } else {
            "list"
        };
        self.stack.set_visible_child_name(name);
    }

    /// 読み直す（届いた種類から出す）。
    fn load(self: &Rc<Self>) {
        if !self.app.repo.client().is_logged_in() {
            return;
        }
        self.data.borrow_mut().pending = 5;
        let repo = self.app.repo.clone();
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            let done = |this: &Rc<View>| {
                let mut data = this.data.borrow_mut();
                data.pending = data.pending.saturating_sub(1);
            };
            let r = repo.clone();
            if let Ok(playlists) = runtime::run(async move { r.library_playlists().await }).await {
                *this.app.editable_playlists.borrow_mut() = playlists.iter().filter(|p| p.editable).cloned().collect();
                this.data.borrow_mut().playlists = playlists.into_iter().filter(|p| p.playlist_id != LIKED_SONGS).map(playlist_entry).collect();
            }
            done(&this);
            this.render();
            let r = repo.clone();
            if let Ok(artists) = runtime::run(async move { r.library_artists().await }).await {
                this.data.borrow_mut().artists = artists.into_iter().map(artist_entry).collect();
            }
            done(&this);
            this.render();
            let r = repo.clone();
            if let Ok(albums) = runtime::run(async move { r.library_albums().await }).await {
                this.data.borrow_mut().albums = albums.into_iter().map(album_entry).collect();
            }
            done(&this);
            this.render();
            let r = repo.clone();
            if let Ok(shows) = runtime::run(async move { r.library_podcasts().await }).await {
                this.data.borrow_mut().podcasts = shows.into_iter().map(podcast_entry).collect();
            }
            done(&this);
            this.render();
            let r = repo.clone();
            if let Ok(recent) = runtime::run(async move { r.recent_activity().await }).await {
                this.data.borrow_mut().recent = recent.iter().filter_map(key_of).collect();
            }
            done(&this);
            this.render();
        });
    }

    fn entries(&self) -> Vec<Entry> {
        let data = self.data.borrow();
        let kind = *self.kind.borrow();
        let wants = |k: &str| kind.is_none_or(|w| w == k);
        let mut entries: Vec<Entry> = Vec::new();
        if wants("playlist") {
            entries.extend(data.playlists.iter().cloned());
        }
        if wants("artist") {
            entries.extend(data.artists.iter().cloned());
        }
        if wants("album") {
            entries.extend(data.albums.iter().cloned());
        }
        // YouTube で保存した番組の後ろに、RSS で登録した番組を並べる（Mac の allPodcasts と同じ）
        if wants("podcast") {
            let mut seen = std::collections::HashSet::new();
            let feeds = self.app.podcasts.library.state().subscriptions.iter().map(|s| podcast_entry(s.summary())).collect::<Vec<_>>();
            entries.extend(data.podcasts.iter().cloned().chain(feeds).filter(|e| seen.insert(e.key.clone())));
        }
        let query = normalize(&self.query.borrow());
        let matches = |title: &str| query.is_empty() || normalize(title).contains(&query);
        entries.retain(|e| matches(&e.title));
        match self.app.downloads.settings.borrow().library_sort.as_str() {
            "alpha" => entries.sort_by_key(|e| e.title.to_lowercase()),
            "added" => {}
            // 最近再生したものを先に、残りは追加した順のまま
            _ => {
                let rank: HashMap<&str, usize> = data.recent.iter().enumerate().rev().map(|(i, k)| (k.as_str(), i)).collect();
                entries.sort_by_key(|e| e.key.as_deref().and_then(|k| rank.get(k).copied()).unwrap_or(usize::MAX));
            }
        }
        let mut head: Vec<Entry> = Vec::new();
        // ポッドキャストで絞ったときは「新しいエピソード」を先頭に
        if kind == Some("podcast") && matches("新しいエピソード") {
            head.push(fixed("新しいエピソード", "プレイリスト • 自動".into(), Target::NewEpisodes));
        }
        // 高く評価した曲はいつも先頭、ダウンロードはその次
        if wants("playlist") && matches("高く評価した曲") {
            head.push(fixed("高く評価した曲", "プレイリスト • 自動".into(), Target::Liked));
        }
        if wants("playlist") && matches("ダウンロード") {
            head.push(fixed("ダウンロード", format!("プレイリスト • 自動 • {} 曲", downloaded_count(&self.app)), Target::Downloads));
        }
        head.extend(entries);
        head
    }

    fn render(self: &Rc<Self>) {
        self.show_stack();
        let entries = self.entries();
        while let Some(row) = self.list.row_at_index(0) {
            self.list.remove(&row);
        }
        while let Some(child) = self.grid.child_at_index(0) {
            self.grid.remove(&child);
        }
        for entry in &entries {
            let row = row(entry);
            self.add_menu(row.upcast_ref(), entry);
            self.list.append(&row);
            let card = card(entry);
            self.add_menu(card.upcast_ref(), entry);
            self.grid.append(&card);
        }
        *self.shown.borrow_mut() = entries;
    }

    /// 右クリックで「再生」（ページを開かずに流す）。
    fn add_menu(self: &Rc<Self>, widget: &gtk::Widget, entry: &Entry) {
        let item = match &entry.target {
            Target::Item(item) => item.clone(),
            Target::Liked => HomeItem::Playlist(PlaylistSummary { playlist_id: LIKED_SONGS.into(), title: entry.title.clone(), subtitle: None, thumbnail_url: None, editable: false }),
            _ => return,
        };
        let menu = gtk::gio::Menu::new();
        menu.append(Some("再生"), Some("entry.play"));
        let actions = gtk::gio::SimpleActionGroup::new();
        let play = gtk::gio::SimpleAction::new("play", None);
        let app = self.app.clone();
        play.connect_activate(move |_, _| track::play_item(&app, &item));
        actions.add_action(&play);
        widget.insert_action_group("entry", Some(&actions));
        let right = gtk::GestureClick::builder().button(3).build();
        let weak = widget.downgrade();
        right.connect_pressed(move |gesture, _, x, y| {
            gesture.set_state(gtk::EventSequenceState::Claimed);
            let Some(widget) = weak.upgrade() else { return };
            let popover = gtk::PopoverMenu::from_model(Some(&menu));
            popover.set_parent(&widget);
            popover.set_has_arrow(false);
            popover.set_pointing_to(Some(&gtk::gdk::Rectangle::new(x as i32, y as i32, 1, 1)));
            popover.connect_closed(|p| {
                let p = p.clone();
                gtk::glib::idle_add_local_once(move || p.unparent());
            });
            popover.popup();
        });
        widget.add_controller(right);
    }

    fn open(&self, index: usize) {
        let Some(entry) = self.shown.borrow().get(index).cloned() else { return };
        let app = &self.app;
        match entry.target {
            Target::Liked => app.push(super::pages::playlist(app, LIKED_SONGS, "高く評価した曲")),
            Target::Downloads => app.push(super::pages::downloads(app)),
            Target::NewEpisodes => app.push(super::podcasts::new_episodes(app)),
            Target::Item(item) => track::open_item(app, &item),
            Target::Podcast(show) => app.push(super::podcasts::show(app, &show.browse_id, &show.title)),
        }
    }
}

/// RSS で登録した番組の並び（変わったかを見るため）。
fn feeds_signature(app: &App) -> Vec<String> {
    app.podcasts.library.state().subscriptions.iter().map(|s| format!("{}\n{}", s.feed_url, s.title)).collect()
}

fn subtitle(kind: &str, rest: Option<&str>) -> String {
    match rest.filter(|r| !r.is_empty()) {
        Some(rest) => format!("{kind} • {rest}"),
        None => kind.to_string(),
    }
}

fn playlist_entry(p: PlaylistSummary) -> Entry {
    Entry { title: p.title.clone(), subtitle: subtitle("プレイリスト", p.subtitle.as_deref()), thumbnail: p.thumbnail_url.clone(), round: false, key: Some(format!("p-{}", p.playlist_id)), target: Target::Item(HomeItem::Playlist(p)) }
}

fn artist_entry(a: ArtistSummary) -> Entry {
    Entry { title: a.name.clone(), subtitle: "アーティスト".into(), thumbnail: a.thumbnail_url.clone(), round: true, key: Some(format!("ar-{}", a.browse_id)), target: Target::Item(HomeItem::Artist(a)) }
}

fn album_entry(a: AlbumSummary) -> Entry {
    Entry { title: a.title.clone(), subtitle: subtitle("アルバム", a.subtitle.as_deref()), thumbnail: a.thumbnail_url.clone(), round: false, key: Some(format!("al-{}", a.browse_id)), target: Target::Item(HomeItem::Album(a)) }
}

fn podcast_entry(c: PodcastSummary) -> Entry {
    Entry { title: c.title.clone(), subtitle: subtitle("ポッドキャスト", c.author.as_deref()), thumbnail: c.thumbnail_url.clone(), round: false, key: Some(format!("c-{}", c.browse_id)), target: Target::Podcast(c) }
}

fn fixed(title: &str, subtitle: String, target: Target) -> Entry {
    Entry { title: title.into(), subtitle, thumbnail: None, round: false, target, key: None }
}

fn key_of(item: &HomeItem) -> Option<String> {
    match item {
        HomeItem::Playlist(p) => Some(format!("p-{}", p.playlist_id)),
        HomeItem::Artist(a) => Some(format!("ar-{}", a.browse_id)),
        HomeItem::Album(a) => Some(format!("al-{}", a.browse_id)),
        HomeItem::Song(_) => None,
    }
}

fn artwork(entry: &Entry, size: i32) -> gtk::Widget {
    match entry.target {
        Target::Liked => return track::cover("liked", size),
        Target::Downloads => return track::cover("downloads", size),
        Target::NewEpisodes => return track::cover("episodes", size),
        _ => {}
    }
    let picture = images::square(size);
    if entry.round {
        picture.add_css_class("round");
    }
    picture.load(entry.thumbnail.as_deref(), (size * 2) as u32);
    picture.root.clone().upcast()
}

fn row(entry: &Entry) -> gtk::ListBoxRow {
    let row = gtk::Box::builder().spacing(12).margin_top(4).margin_bottom(4).build();
    row.append(&artwork(entry, 48));
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).build();
    text.append(&gtk::Label::builder().label(&entry.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build());
    text.append(&gtk::Label::builder().label(&entry.subtitle).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["caption", "dim-label"]).build());
    row.append(&text);
    gtk::ListBoxRow::builder().child(&row).build()
}

/// グリッドの1枚（名前だけ）。
fn card(entry: &Entry) -> gtk::FlowBoxChild {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    column.append(&artwork(entry, 96));
    column.append(&gtk::Label::builder().label(&entry.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["caption"]).build());
    gtk::FlowBoxChild::builder().child(&column).build()
}
