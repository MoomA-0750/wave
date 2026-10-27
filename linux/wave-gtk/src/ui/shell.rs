//! 窓の並び（Mac・Windows と同じ）: 上の帯に戻る・ホーム・検索の欄、左にマイライブラリ、中央にページ、
//! 右に再生中の欄（しまえる）、下に再生の操作の帯。部品は libadwaita のもの。

use std::cell::Cell;
use std::rc::Rc;

use adw::prelude::*;
use wave_core::music::repository::{LIKED_SONGS, MusicRepository};

use super::{images, pages};
use crate::playback::Playback;
use crate::runtime;

pub struct App {
    pub repo: MusicRepository,
    pub playback: Rc<Playback>,
    nav: adw::NavigationView,
}

impl App {
    pub fn push(&self, page: adw::NavigationPage) {
        self.nav.push(&page);
    }
}

const STYLE: &str = "
.artwork { border-radius: 8px; }
.artwork-frame { background-color: alpha(currentColor, 0.08); }
.artwork.round { border-radius: 9999px; }
.pane { background-color: var(--view-bg-color); border-radius: 12px; }
.card-button { padding: 6px; border-radius: 12px; }
.play-large { min-width: 48px; min-height: 48px; }
.player-bar { padding: 8px 16px; }
.song-list { background: transparent; }
";

/// 窓の中身（上の帯を含む）を作る。
pub fn build(repo: MusicRepository, playback: Rc<Playback>) -> (gtk::Widget, Rc<App>) {
    let css = gtk::CssProvider::new();
    css.load_from_string(STYLE);
    gtk::style_context_add_provider_for_display(&gtk::gdk::Display::default().expect("display"), &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);

    let nav = adw::NavigationView::new();
    let app = Rc::new(App { repo, playback, nav: nav.clone() });
    nav.add(&pages::home(&app));

    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&header(&app));
    let body = gtk::Box::builder().spacing(8).margin_start(8).margin_end(8).build();
    body.append(&pane(&library(&app), Some(300)));
    let center = pane(&nav, None);
    center.set_hexpand(true);
    body.append(&center);
    let now_playing = pane(&now_playing(&app), Some(340));
    body.append(&now_playing);
    toolbar.set_content(Some(&body));
    toolbar.add_bottom_bar(&player_bar(&app, &now_playing));
    (toolbar.upcast(), app)
}

impl App {
    /// 確かめ用（WAVE_DEMO_QUERY）: その語で検索のページを開き、1曲目から流す。
    /// WAVE_DEMO_PAGE が artist・album なら、続けてその曲のアーティスト・アルバムのページを開く。
    pub fn demo(self: &Rc<Self>, query: &str) {
        self.push(pages::search(self, query));
        let (app, query) = (self.clone(), query.to_string());
        gtk::glib::spawn_future_local(async move {
            let repo = app.repo.clone();
            if let Ok(page) = runtime::run(async move { repo.search_songs(&query, None).await }).await {
                let first = page.songs.first().cloned();
                app.playback.play_list(page.songs, 0);
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
    }
    frame.upcast()
}

/// 上の帯: 左に戻る、中央（窓の左右の中央）にホームと検索の欄。
fn header(app: &Rc<App>) -> adw::HeaderBar {
    let back = gtk::Button::builder().icon_name("go-previous-symbolic").tooltip_text("戻る").visible(false).build();
    let home = gtk::Button::builder().icon_name("go-home-symbolic").tooltip_text("ホーム").css_classes(["circular"]).build();
    let search = gtk::SearchEntry::builder().placeholder_text("何を聴きたいですか？").width_request(440).build();
    let center = gtk::Box::builder().spacing(8).build();
    center.append(&home);
    center.append(&search);
    let bar = adw::HeaderBar::builder().title_widget(&center).build();
    bar.pack_start(&back);

    let nav = app.nav.clone();
    back.connect_clicked(move |_| {
        nav.pop();
    });
    let nav = app.nav.clone();
    home.connect_clicked(move |_| {
        nav.pop_to_tag("home");
    });
    let back_button = back.clone();
    app.nav.connect_visible_page_notify(move |nav| back_button.set_visible(nav.navigation_stack().n_items() > 1));
    let app = app.clone();
    search.connect_activate(move |entry| {
        let query = entry.text().trim().to_string();
        if query.is_empty() {
            return;
        }
        // 検索のページは1枚だけ積む（打ち直したら置き換える）
        if app.nav.visible_page().and_then(|p| p.tag()).as_deref() == Some("search") {
            app.nav.pop();
        }
        app.push(pages::search(&app, &query));
    });
    bar
}

/// 左の欄: マイライブラリ（先頭に高く評価した曲、続いてライブラリのプレイリスト）。
fn library(app: &Rc<App>) -> gtk::Widget {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    column.append(&gtk::Label::builder().label("マイライブラリ").xalign(0.0).margin_top(16).margin_bottom(8).margin_start(16).css_classes(["title-3"]).build());
    let list = gtk::ListBox::builder().css_classes(["navigation-sidebar"]).build();
    let liked = library_row("高く評価した曲", "自動のプレイリスト", None);
    list.append(&liked);
    let scroller = gtk::ScrolledWindow::builder().child(&list).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
    column.append(&scroller);

    let entries: Rc<std::cell::RefCell<Vec<(String, String)>>> = Rc::new(std::cell::RefCell::new(vec![(LIKED_SONGS.to_string(), "高く評価した曲".to_string())]));
    {
        let (app, entries) = (app.clone(), entries.clone());
        list.connect_row_activated(move |_, row| {
            if let Some((id, title)) = entries.borrow().get(row.index() as usize).cloned() {
                app.push(pages::playlist(&app, &id, &title));
            }
        });
    }
    let (app, list) = (app.clone(), list.clone());
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let Ok(playlists) = runtime::run(async move { repo.library_playlists().await }).await else { return };
        for p in playlists.into_iter().filter(|p| p.playlist_id != LIKED_SONGS) {
            list.append(&library_row(&p.title, p.subtitle.as_deref().unwrap_or(""), p.thumbnail_url.as_deref()));
            entries.borrow_mut().push((p.playlist_id, p.title));
        }
    });
    column.upcast()
}

fn library_row(title: &str, subtitle: &str, thumbnail: Option<&str>) -> gtk::ListBoxRow {
    let row = gtk::Box::builder().spacing(12).margin_top(4).margin_bottom(4).build();
    let picture = images::square(48);
    picture.load(thumbnail, 96);
    if thumbnail.is_none() {
        // 高く評価した曲は画像の代わりに印
        let icon = gtk::Image::builder().icon_name("emblem-favorite-symbolic").pixel_size(24).width_request(48).height_request(48).css_classes(["artwork", "accent"]).build();
        row.append(&icon);
    } else {
        row.append(&picture.root);
    }
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).build();
    text.append(&gtk::Label::builder().label(title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build());
    text.append(&gtk::Label::builder().label(subtitle).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["caption", "dim-label"]).build());
    row.append(&text);
    gtk::ListBoxRow::builder().child(&row).build()
}

/// 右の欄: 再生中の曲（大きなジャケット・曲名・アーティスト）と次の曲。
fn now_playing(app: &Rc<App>) -> gtk::Widget {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(16).margin_bottom(16).margin_start(16).margin_end(16).build();
    let picture = images::square(308);
    let title = gtk::Label::builder().xalign(0.0).wrap(true).css_classes(["title-3"]).build();
    let artist = gtk::Label::builder().xalign(0.0).wrap(true).css_classes(["dim-label"]).build();
    let next_title = gtk::Label::builder().label("次の曲").xalign(0.0).margin_top(12).css_classes(["heading"]).build();
    let next_list = gtk::ListBox::builder().css_classes(["boxed-list"]).selection_mode(gtk::SelectionMode::None).build();
    column.append(&picture.root);
    column.append(&title);
    column.append(&artist);
    column.append(&next_title);
    column.append(&next_list);
    let empty = adw::StatusPage::builder().icon_name("audio-x-generic-symbolic").title("再生していません").description("曲を選ぶとここに出ます").build();
    let stack = gtk::Stack::new();
    stack.add_named(&empty, Some("empty"));
    stack.add_named(&gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).build(), Some("song"));

    // 曲名を押すとアルバムへ、アーティストを押すとアーティストのページへ（Mac・Windows と同じ）
    {
        let app_for_artist = app.clone();
        let app = app.clone();
        let click = gtk::GestureClick::new();
        click.connect_released(move |_, _, _, _| {
            if let Some(album) = app.playback.current().and_then(|s| s.album).and_then(|a| Some((a.browse_id?, a.title))) {
                app.push(pages::album(&app, &album.0, &album.1));
            }
        });
        title.add_controller(click);
        let app = app_for_artist;
        let click = gtk::GestureClick::new();
        click.connect_released(move |_, _, _, _| {
            if let Some((id, name)) = app.playback.current().and_then(|s| s.artists.into_iter().find_map(|a| Some((a.browse_id?, a.name)))) {
                app.push(pages::artist(&app, &id, &name));
            }
        });
        artist.add_controller(click);
        title.set_cursor_from_name(Some("pointer"));
        artist.set_cursor_from_name(Some("pointer"));
    }
    let refresh = {
        let (app, stack, next_list) = (app.clone(), stack.clone(), next_list.clone());
        move || {
            let Some(song) = app.playback.current() else {
                stack.set_visible_child_name("empty");
                return;
            };
            stack.set_visible_child_name("song");
            picture.load(song.thumbnail_url.as_deref(), 616);
            title.set_label(&song.title);
            artist.set_label(&song.artists_text());
            while let Some(row) = next_list.first_child() {
                next_list.remove(&row);
            }
            let upcoming = app.playback.upcoming();
            next_title.set_visible(!upcoming.is_empty());
            next_list.set_visible(!upcoming.is_empty());
            if let Some(next) = upcoming.first() {
                let row = adw::ActionRow::builder()
                    .title(gtk::glib::markup_escape_text(&next.title))
                    .subtitle(gtk::glib::markup_escape_text(&next.artists_text()))
                    .title_lines(1)
                    .subtitle_lines(1)
                    .build();
                let art = images::square(40);
                art.load(next.thumbnail_url.as_deref(), 80);
                row.add_prefix(&art.root);
                next_list.append(&row);
            }
        }
    };
    refresh();
    app.playback.on_change(refresh);
    stack.upcast()
}

/// 下の帯: 左に曲、中央に操作と位置（窓の左右の中央）、右に音量と右の欄の出し入れ。
fn player_bar(app: &Rc<App>, side: &gtk::Widget) -> gtk::Widget {
    // 左: 曲
    let art = images::square(56);
    let title = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(22).css_classes(["heading"]).build();
    let artist = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(22).css_classes(["caption", "dim-label"]).build();
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).spacing(2).build();
    text.append(&title);
    text.append(&artist);
    let song = gtk::Box::builder().spacing(12).build();
    song.append(&art.root);
    song.append(&text);

    // 中央: 操作と位置
    let previous = gtk::Button::builder().icon_name("media-skip-backward-symbolic").tooltip_text("前の曲").css_classes(["circular", "flat"]).build();
    let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").css_classes(["circular", "suggested-action", "play-large"]).build();
    let next = gtk::Button::builder().icon_name("media-skip-forward-symbolic").tooltip_text("次の曲").css_classes(["circular", "flat"]).build();
    let controls = gtk::Box::builder().spacing(12).halign(gtk::Align::Center).build();
    controls.append(&previous);
    controls.append(&play);
    controls.append(&next);
    let position = gtk::Label::builder().label("0:00").width_chars(5).xalign(1.0).css_classes(["caption", "numeric", "dim-label"]).build();
    let duration = gtk::Label::builder().label("0:00").width_chars(5).xalign(0.0).css_classes(["caption", "numeric", "dim-label"]).build();
    let seek = gtk::Scale::builder().orientation(gtk::Orientation::Horizontal).hexpand(true).width_request(420).build();
    seek.set_range(0.0, 1.0);
    let seek_row = gtk::Box::builder().spacing(8).build();
    seek_row.append(&position);
    seek_row.append(&seek);
    seek_row.append(&duration);
    let middle = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    middle.append(&controls);
    middle.append(&seek_row);

    // 右: 音量と右の欄の出し入れ
    let volume_icon = gtk::Image::from_icon_name("audio-volume-high-symbolic");
    let volume = gtk::Scale::builder().orientation(gtk::Orientation::Horizontal).width_request(120).build();
    volume.set_range(0.0, 1.0);
    volume.set_value(app.playback.player().volume());
    let side_toggle = gtk::ToggleButton::builder().icon_name("sidebar-show-right-symbolic").tooltip_text("再生中の画面").active(true).css_classes(["flat"]).build();
    let end = gtk::Box::builder().spacing(8).valign(gtk::Align::Center).build();
    end.append(&volume_icon);
    end.append(&volume);
    end.append(&side_toggle);

    let bar = gtk::CenterBox::builder().css_classes(["player-bar"]).build();
    bar.set_start_widget(Some(&song));
    bar.set_center_widget(Some(&middle));
    bar.set_end_widget(Some(&end));
    // 左右を同じ幅にして、操作を窓の左右の中央に置く
    let group = gtk::SizeGroup::new(gtk::SizeGroupMode::Horizontal);
    group.add_widget(&song);
    group.add_widget(&end);

    {
        let playback = app.playback.clone();
        previous.connect_clicked(move |_| playback.previous());
        let playback = app.playback.clone();
        play.connect_clicked(move |_| playback.toggle());
        let playback = app.playback.clone();
        next.connect_clicked(move |_| playback.next());
        let playback = app.playback.clone();
        volume.connect_value_changed(move |s| playback.player().set_volume(s.value()));
        let side = side.clone();
        side_toggle.connect_toggled(move |t| side.set_visible(t.is_active()));
    }
    // 利用者が動かしたときだけシークする（位置の書き換えでは呼ばれない）
    let dragging = Rc::new(Cell::new(false));
    {
        let (playback, dragging) = (app.playback.clone(), dragging.clone());
        seek.connect_change_value(move |_, _, value| {
            if let Some(total) = playback.player().duration_ms() {
                playback.player().seek_ms((value.clamp(0.0, 1.0) * total as f64) as u64);
            }
            dragging.set(false);
            gtk::glib::Propagation::Proceed
        });
    }
    let refresh = {
        let (app, bar, play, next, song) = (app.clone(), bar.clone(), play.clone(), next.clone(), song.clone());
        move || {
            let current = app.playback.current();
            bar.set_sensitive(current.is_some());
            song.set_visible(current.is_some());
            if let Some(song) = current {
                art.load(song.thumbnail_url.as_deref(), 112);
                title.set_label(&song.title);
                artist.set_label(&song.artists_text());
            }
            next.set_sensitive(app.playback.has_next());
            let playing = app.playback.player().is_playing();
            play.set_icon_name(if playing { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" });
            play.set_tooltip_text(Some(if playing { "一時停止" } else { "再生" }));
        }
    };
    refresh();
    app.playback.on_change(refresh.clone());
    // 位置は 0.5 秒ごとに描き直す（再生・一時停止の印も、状態の変化が遅れて届くのでここでも合わせる）
    let playback = app.playback.clone();
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(500), move || {
        let player = playback.player();
        let (pos, total) = (player.position_ms().unwrap_or(0), player.duration_ms().unwrap_or(0));
        position.set_label(&pages::format_seconds((pos / 1000) as u32));
        duration.set_label(&pages::format_seconds((total / 1000) as u32));
        if !dragging.get() && total > 0 {
            seek.set_value(pos as f64 / total as f64);
        }
        refresh();
        gtk::glib::ControlFlow::Continue
    });
    bar.upcast()
}
