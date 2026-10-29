//! 左の欄のマイライブラリ（Windows の MainWindow のマイライブラリと同じ）。プレイリスト・アーティスト・アルバム・ポッドキャストを並べ、
//! 種類で絞り、中を検索し（大文字・小文字、全角・半角、ひらがな・カタカナを区別しない）、並べ替え（最近・最近追加した順・アルファベット順）、
//! リストとグリッドを切り替える。並べ方とグリッドは設定に持ち越す。高く評価した曲・ダウンロード・ポッドキャストはいつも先頭の方に出す。

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use adw::prelude::*;
use wave_core::lyrics::search::normalize;
use wave_core::music::models::HomeItem;
use wave_core::music::podcast::PodcastSummary;
use wave_core::music::repository::LIKED_SONGS;

use super::images;
use super::shell::App;
use crate::runtime;

/// 一覧の1行の行き先。
#[derive(Clone)]
enum Target {
    Liked,
    Downloads,
    Podcasts,
    Playlist(String, String),
    Artist(String, String),
    Album(String, String),
    Podcast(String, String),
}

#[derive(Clone)]
struct Entry {
    title: String,
    subtitle: String,
    thumbnail: Option<String>,
    icon: Option<&'static str>,
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
}

struct View {
    app: Rc<App>,
    data: RefCell<Data>,
    kind: RefCell<Option<&'static str>>,
    query: RefCell<String>,
    list: gtk::ListBox,
    grid: gtk::FlowBox,
    stack: gtk::Stack,
    shown: RefCell<Vec<Entry>>,
}

const KINDS: [(&str, &str); 4] = [("playlist", "プレイリスト"), ("artist", "アーティスト"), ("album", "アルバム"), ("podcast", "ポッドキャスト")];

pub fn sidebar(app: &Rc<App>) -> gtk::Widget {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    let header = gtk::Box::builder().spacing(4).margin_top(12).margin_bottom(4).margin_start(16).margin_end(8).build();
    header.append(&gtk::Label::builder().label("マイライブラリ").xalign(0.0).hexpand(true).css_classes(["title-3"]).build());
    let sort = gtk::MenuButton::builder().icon_name("view-sort-descending-symbolic").tooltip_text("並べ替え").css_classes(["flat"]).build();
    let layout = gtk::Button::builder().css_classes(["flat"]).build();
    header.append(&sort);
    header.append(&layout);
    column.append(&header);

    let chips = gtk::Box::builder().spacing(6).margin_start(12).margin_end(12).margin_bottom(6).build();
    let chip_scroller = gtk::ScrolledWindow::builder().child(&chips).vscrollbar_policy(gtk::PolicyType::Never).propagate_natural_height(true).build();
    column.append(&chip_scroller);
    let search = gtk::SearchEntry::builder().placeholder_text("マイライブラリを検索").margin_start(12).margin_end(12).margin_bottom(6).build();
    column.append(&search);

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
    column.append(&stack);

    let view = Rc::new(View {
        app: app.clone(),
        data: RefCell::default(),
        kind: RefCell::default(),
        query: RefCell::default(),
        list: list.clone(),
        grid: grid.clone(),
        stack,
        shown: RefCell::default(),
    });

    // 種類の絞り込み（もう一度押すと外す）
    let buttons: Rc<Vec<(gtk::ToggleButton, &'static str)>> = Rc::new(
        KINDS
            .iter()
            .map(|(kind, title)| {
                let button = gtk::ToggleButton::builder().label(*title).css_classes(["caption"]).build();
                chips.append(&button);
                (button, *kind)
            })
            .collect(),
    );
    for (button, kind) in buttons.iter() {
        let (view, buttons, kind) = (view.clone(), buttons.clone(), *kind);
        button.connect_clicked(move |button| {
            let on = button.is_active();
            *view.kind.borrow_mut() = on.then_some(kind);
            for (other, k) in buttons.iter() {
                if *k != kind && other.is_active() {
                    other.set_active(false);
                }
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

    // 並べ替え
    let menu = gtk::gio::Menu::new();
    for (key, label) in [("recent", "最近"), ("added", "最近追加した順"), ("alpha", "アルファベット順")] {
        menu.append(Some(label), Some(&format!("library.sort::{key}")));
    }
    sort.set_menu_model(Some(&menu));
    let actions = gtk::gio::SimpleActionGroup::new();
    let current = app.downloads.settings.borrow().library_sort.clone();
    let sort_action = gtk::gio::SimpleAction::new_stateful("sort", Some(gtk::glib::VariantTy::STRING), &current.to_variant());
    {
        let view = view.clone();
        sort_action.connect_activate(move |action, value| {
            let Some(value) = value.and_then(|v| v.get::<String>()) else { return };
            action.set_state(&value.to_variant());
            view.app.downloads.update_settings(|s| s.library_sort = value.clone());
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
            view.stack.set_visible_child_name(if grid { "grid" } else { "list" });
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
    view.render();
    view.load();
    column.upcast()
}

impl View {
    /// 読み直す（届いた種類から出す）。
    fn load(self: &Rc<Self>) {
        let repo = self.app.repo.clone();
        let this = self.clone();
        gtk::glib::spawn_future_local(async move {
            let r = repo.clone();
            if let Ok(playlists) = runtime::run(async move { r.library_playlists().await }).await {
                *this.app.editable_playlists.borrow_mut() = playlists.iter().filter(|p| p.editable).cloned().collect();
                this.data.borrow_mut().playlists = playlists
                    .into_iter()
                    .filter(|p| p.playlist_id != LIKED_SONGS)
                    .map(|p| Entry {
                        subtitle: subtitle("プレイリスト", p.subtitle.as_deref()),
                        thumbnail: p.thumbnail_url,
                        icon: None,
                        round: false,
                        key: Some(format!("p-{}", p.playlist_id)),
                        target: Target::Playlist(p.playlist_id, p.title.clone()),
                        title: p.title,
                    })
                    .collect();
                this.render();
            }
            let r = repo.clone();
            if let Ok(artists) = runtime::run(async move { r.library_artists().await }).await {
                this.data.borrow_mut().artists = artists
                    .into_iter()
                    .map(|a| Entry {
                        subtitle: "アーティスト".into(),
                        thumbnail: a.thumbnail_url,
                        icon: None,
                        round: true,
                        key: Some(format!("ar-{}", a.browse_id)),
                        target: Target::Artist(a.browse_id, a.name.clone()),
                        title: a.name,
                    })
                    .collect();
                this.render();
            }
            let r = repo.clone();
            if let Ok(albums) = runtime::run(async move { r.library_albums().await }).await {
                this.data.borrow_mut().albums = albums
                    .into_iter()
                    .map(|a| Entry {
                        subtitle: subtitle("アルバム", a.subtitle.as_deref()),
                        thumbnail: a.thumbnail_url,
                        icon: None,
                        round: false,
                        key: Some(format!("al-{}", a.browse_id)),
                        target: Target::Album(a.browse_id, a.title.clone()),
                        title: a.title,
                    })
                    .collect();
                this.render();
            }
            let r = repo.clone();
            if let Ok(shows) = runtime::run(async move { r.library_podcasts().await }).await {
                this.data.borrow_mut().podcasts = shows.into_iter().map(podcast_entry).collect();
                this.render();
            }
            let r = repo.clone();
            if let Ok(recent) = runtime::run(async move { r.recent_activity().await }).await {
                this.data.borrow_mut().recent = recent.iter().filter_map(key_of).collect();
                this.render();
            }
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
        // YouTube で保存した番組の後ろに、RSS で登録した番組を並べる（Windows・Android と同じ）
        if wants("podcast") {
            let mut seen = std::collections::HashSet::new();
            let feeds = self.app.podcasts.library.state().subscriptions.iter().map(|s| podcast_entry(s.summary())).collect::<Vec<_>>();
            entries.extend(data.podcasts.iter().cloned().chain(feeds).filter(|e| seen.insert(e.key.clone())));
        }
        let query = normalize(&self.query.borrow());
        if !query.is_empty() {
            entries.retain(|e| normalize(&e.title).contains(&query));
        }
        match self.app.downloads.settings.borrow().library_sort.as_str() {
            "alpha" => entries.sort_by_key(|e| e.title.to_lowercase()),
            "added" => {}
            // 最近再生したものを先に、残りは追加した順のまま
            _ => {
                let rank: HashMap<&str, usize> = data.recent.iter().enumerate().rev().map(|(i, k)| (k.as_str(), i)).collect();
                entries.sort_by_key(|e| e.key.as_deref().and_then(|k| rank.get(k).copied()).unwrap_or(usize::MAX));
            }
        }
        let matches = |title: &str| query.is_empty() || normalize(title).contains(&query);
        let mut head: Vec<Entry> = Vec::new();
        if wants("playlist") && matches("高く評価した曲") {
            head.push(fixed("高く評価した曲", "プレイリスト • 自動", "starred-symbolic", Target::Liked));
        }
        if wants("playlist") && matches("ダウンロード") {
            head.push(fixed("ダウンロード", "この PC に保存", "folder-download-symbolic", Target::Downloads));
        }
        if wants("podcast") && (matches("ポッドキャスト") || matches("新しいエピソード")) {
            head.push(fixed("ポッドキャスト", "番組と新しいエピソード", "audio-input-microphone-symbolic", Target::Podcasts));
        }
        head.extend(entries);
        head
    }

    fn render(&self) {
        let entries = self.entries();
        while let Some(row) = self.list.row_at_index(0) {
            self.list.remove(&row);
        }
        while let Some(child) = self.grid.child_at_index(0) {
            self.grid.remove(&child);
        }
        for entry in &entries {
            self.list.append(&row(entry));
            self.grid.append(&card(entry));
        }
        *self.shown.borrow_mut() = entries;
    }

    fn open(&self, index: usize) {
        let Some(entry) = self.shown.borrow().get(index).cloned() else { return };
        let app = &self.app;
        match entry.target {
            Target::Liked => app.push(super::pages::playlist(app, LIKED_SONGS, "高く評価した曲")),
            Target::Downloads => app.push(super::pages::downloads(app)),
            Target::Podcasts => app.push(super::podcasts::library(app)),
            Target::Playlist(id, title) => app.push(super::pages::playlist(app, &id, &title)),
            Target::Artist(id, name) => app.push(super::pages::artist(app, &id, &name)),
            Target::Album(id, title) => app.push(super::pages::album(app, &id, &title)),
            Target::Podcast(id, title) => app.push(super::podcasts::show(app, &id, &title)),
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

fn podcast_entry(c: PodcastSummary) -> Entry {
    Entry {
        subtitle: subtitle("ポッドキャスト", c.author.as_deref()),
        thumbnail: c.thumbnail_url,
        icon: None,
        round: false,
        key: Some(format!("c-{}", c.browse_id)),
        target: Target::Podcast(c.browse_id, c.title.clone()),
        title: c.title,
    }
}

fn fixed(title: &str, subtitle: &str, icon: &'static str, target: Target) -> Entry {
    Entry { title: title.into(), subtitle: subtitle.into(), thumbnail: None, icon: Some(icon), round: false, target, key: None }
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
    if let Some(icon) = entry.icon {
        return gtk::Image::builder().icon_name(icon).pixel_size(size / 2).width_request(size).height_request(size).css_classes(["artwork", "accent"]).build().upcast();
    }
    let picture = images::square(size);
    if entry.round {
        picture.root.add_css_class("round");
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

fn card(entry: &Entry) -> gtk::FlowBoxChild {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    column.append(&artwork(entry, 96));
    column.append(&gtk::Label::builder().label(&entry.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["caption-heading"]).build());
    column.append(&gtk::Label::builder().label(&entry.subtitle).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["caption", "dim-label"]).build());
    gtk::FlowBoxChild::builder().child(&column).build()
}
