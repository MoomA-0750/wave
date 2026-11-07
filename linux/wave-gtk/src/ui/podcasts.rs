//! ポッドキャストの画面（Mac の PodcastPage と同じ）: 番組のページ（YouTube の MPSP… と RSS の rss:…）と「新しいエピソード」
//! （YouTube と RSS の新しい回を合わせて新しい順）、番組のカード、エピソードの行、RSS の番組を探して足すページ。
//! 番組のページ: 流しているときは再生ボタンが一時停止、エピソードが無いと再生を出さない、保存は書けてから表示を変える（失敗したら戻す）、
//! YouTube の番組の保存はログイン中だけ、説明は3行で切り押すと全部、エピソードの続きは下の端で自動で読む。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use wave_core::music::feed;
use wave_core::music::models::{ArtistRef, Song};
use wave_core::music::podcast::{PodcastEpisode, PodcastPage, PodcastSummary};
use wave_core::music::repository::NEW_EPISODES_BROWSE;

use super::images;
use super::shell::App;
use super::track;
use crate::runtime;

fn page(title: &str, tag: &str, child: &impl IsA<gtk::Widget>) -> adw::NavigationPage {
    adw::NavigationPage::builder().title(title).tag(tag).child(child).build()
}

fn holder() -> adw::Bin {
    adw::Bin::builder().child(&adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).valign(gtk::Align::Center).build()).build()
}

fn column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).margin_top(20).margin_bottom(24).margin_start(20).margin_end(20).build()
}

fn scrolling(column: &gtk::Box) -> gtk::ScrolledWindow {
    let clamp = adw::Clamp::builder().maximum_size(1100).tightening_threshold(800).child(column).build();
    gtk::ScrolledWindow::builder().child(&clamp).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build()
}

/// 番組のページ。browse_id が rss: なら RSS の番組（この端末の登録）、MPSP… なら YouTube の番組（ライブラリに保存）、VLRDPN なら新しいエピソード。
pub fn show(app: &Rc<App>, browse_id: &str, title: &str) -> adw::NavigationPage {
    track::ensure_style();
    let bin = holder();
    let (app, target, id, name) = (app.clone(), bin.clone(), browse_id.to_string(), title.to_string());
    gtk::glib::spawn_future_local(async move {
        let new_episodes = id == NEW_EPISODES_BROWSE;
        let loaded: Option<PodcastPage> = match feed::feed_url_of(&id) {
            Some(url) => app.podcasts.show(url, false).await.map(|show| {
                let library = app.podcasts.library.clone();
                show.to_page(Some(library.is_subscribed(url)), feed::PAGE_SIZE, |u| library.position(u))
            }),
            None => {
                let repo = app.repo.clone();
                let browse = id.clone();
                let result = runtime::run(async move { repo.podcast(&browse).await }).await;
                match result {
                    Ok(page) => Some(page),
                    // 保存した番組が無いアカウントの「新しいエピソード」は番組のページの形で返らない。空として出す（Mac・Android と同じ）
                    Err(_) if new_episodes => Some(PodcastPage { browse_id: id.clone(), title: name.clone(), author: None, description: None, thumbnail_url: None, episodes: Vec::new(), continuation: None, saved: None }),
                    Err(_) => None,
                }
            }
        };
        let content: gtk::Widget = match loaded {
            Some(mut page) => {
                // 「新しいエピソード」に、RSS で登録した番組の新しい回を混ぜる（ADR-0012）
                if new_episodes {
                    let feeds = app.podcasts.subscribed_shows().await;
                    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64);
                    let library = app.podcasts.library.clone();
                    let refs: Vec<&feed::FeedShow> = feeds.iter().map(|s| s.as_ref()).collect();
                    page.episodes = wave_core::music::new_episodes::merge_refs(&page.episodes, &refs, now, |u| library.position(u));
                    page.title = "新しいエピソード".into();
                }
                show_content(&app, page, new_episodes).upcast()
            }
            None => adw::StatusPage::builder().icon_name("network-error-symbolic").title("ポッドキャストを読み込めませんでした").vexpand(true).build().upcast(),
        };
        target.set_child(Some(&content));
    });
    page(title, "podcast", &bin)
}

/// マイライブラリの「新しいエピソード」（保存した番組と RSS で登録した番組の新しい回）。
pub fn new_episodes(app: &Rc<App>) -> adw::NavigationPage {
    show(app, NEW_EPISODES_BROWSE, "新しいエピソード")
}

/// これまでの入口（マイライブラリのポッドキャスト）。今は「新しいエピソード」のページ。
pub fn library(app: &Rc<App>) -> adw::NavigationPage {
    new_episodes(app)
}

fn show_content(app: &Rc<App>, page: PodcastPage, new_episodes: bool) -> gtk::ScrolledWindow {
    let column = column();
    let source = page.playlist_id();
    let is_feed = feed::is_feed_show(&page.browse_id);
    // 頭: 絵・種類・題・配信者・説明（3行で切り、押すと全部）
    let header = gtk::Box::builder().spacing(24).build();
    if new_episodes && page.thumbnail_url.is_none() {
        header.append(&track::cover("episodes", 220));
    } else {
        let art = images::square(220);
        art.load(page.thumbnail_url.as_deref(), 440);
        header.append(&art.root);
    }
    let info = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).valign(gtk::Align::End).hexpand(true).build();
    info.append(&gtk::Label::builder().label(if new_episodes { "プレイリスト • 自動" } else { "ポッドキャスト" }).xalign(0.0).build());
    info.append(&track::page_title(&page.title));
    if let Some(author) = page.author.as_deref().filter(|a| !a.is_empty()) {
        info.append(&gtk::Label::builder().label(author).xalign(0.0).css_classes(["heading"]).build());
    }
    if let Some(description) = page.description.as_deref().map(str::trim).filter(|d| !d.is_empty()) {
        let label = gtk::Label::builder().label(description).xalign(0.0).wrap(true).wrap_mode(gtk::pango::WrapMode::WordChar).lines(3).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).hexpand(true).tooltip_text("すべて表示").css_classes(["dim-label"]).build();
        label.set_cursor_from_name(Some("pointer"));
        let click = gtk::GestureClick::new();
        let l = label.clone();
        click.connect_released(move |_, _, _, _| {
            let expanded = l.lines() < 0;
            l.set_lines(if expanded { 3 } else { -1 });
            l.set_ellipsize(if expanded { gtk::pango::EllipsizeMode::End } else { gtk::pango::EllipsizeMode::None });
            l.set_tooltip_text(Some(if expanded { "すべて表示" } else { "短く表示" }));
        });
        label.add_controller(click);
        info.append(&label);
    }
    header.append(&info);
    column.append(&header);

    let episodes: Rc<RefCell<Vec<PodcastEpisode>>> = Rc::new(RefCell::new(page.episodes.clone()));
    // 操作: 再生（流しているときは一時停止）・保存・RSS の番組を探す（新しいエピソードのとき）
    let controls = gtk::Box::builder().spacing(16).build();
    let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").visible(!page.episodes.is_empty()).css_classes(["circular", "suggested-action", "play-large"]).build();
    {
        let (app, episodes, source) = (app.clone(), episodes.clone(), source.clone());
        play.connect_clicked(move |_| {
            if app.playback.is_playing_source(&source) {
                app.playback.toggle();
            } else {
                let songs: Vec<Song> = episodes.borrow().iter().map(|e| e.song.clone()).collect();
                app.playback.play_list_from(songs, 0, Some(source.clone()));
            }
        });
    }
    {
        let (playback, source) = (app.playback.clone(), source.clone());
        app.playback.watch(&play, move |play| track::set_play_icon(play, playback.is_playing_source(&source) && playback.player().is_playing()));
    }
    controls.append(&play);
    let logged_in = app.repo.client().is_logged_in();
    if let Some(saved) = page.saved.filter(|_| is_feed || logged_in) {
        controls.append(&save_button(app, &page, saved));
    }
    if new_episodes {
        let find = gtk::Button::builder().label("RSS の番組を探して足す").valign(gtk::Align::Center).css_classes(["pill"]).build();
        let app = app.clone();
        find.connect_clicked(move |_| app.push(find_feed(&app)));
        controls.append(&find);
    }
    column.append(&controls);

    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["track-list"]).build();
    let on_play = {
        let (app, episodes, source) = (app.clone(), episodes.clone(), source.clone());
        Rc::new(move |index: usize| {
            let songs: Vec<Song> = episodes.borrow().iter().map(|e| e.song.clone()).collect();
            app.playback.play_list_from(songs, index, Some(source.clone()));
        })
    };
    let add_rows = {
        let (app, list, on_play) = (app.clone(), list.clone(), on_play.clone());
        move |from: usize, items: &[PodcastEpisode]| {
            for (offset, episode) in items.iter().enumerate() {
                let on_play = on_play.clone();
                let index = from + offset;
                list.append(&episode_row(&app, episode, false, Rc::new(move || on_play(index))));
            }
        }
    };
    add_rows(0, &page.episodes);
    if page.episodes.is_empty() {
        column.append(&gtk::Label::builder().label("エピソードはありません").xalign(0.0).css_classes(["dim-label"]).build());
    }
    column.append(&list);
    let more = adw::Spinner::builder().width_request(24).height_request(24).halign(gtk::Align::Center).visible(false).build();
    column.append(&more);
    // 確かめ用: WAVE_DEMO_PODCAST_DOWNLOAD があれば、開いたらすぐ最新の回を取る
    if std::env::var_os("WAVE_DEMO_PODCAST_DOWNLOAD").is_some()
        && let Some(first) = episodes.borrow().first()
    {
        app.downloads.download_song(&first.song);
    }
    // 確かめ用: WAVE_DEMO_PODCAST_PLAY があれば、開いたらすぐ最新の回を流す
    if std::env::var_os("WAVE_DEMO_PODCAST_PLAY").is_some() {
        on_play(0);
    }
    let scroller = scrolling(&column);
    // 続き（YouTube は続きのトークン、RSS は読んだ番組の続き）は下の端で読む。この番組を流していれば、読めた分を後ろに足す
    let token = Rc::new(RefCell::new(page.continuation.clone()));
    let loading = Rc::new(Cell::new(false));
    let (app, browse_id, title) = (app.clone(), page.browse_id.clone(), page.title.clone());
    track::on_near_bottom(&scroller, move || {
        let Some(t) = token.borrow().clone() else { return };
        if loading.replace(true) {
            return;
        }
        more.set_visible(true);
        let (app, episodes, token, loading, more, browse_id, title, source, add_rows) =
            (app.clone(), episodes.clone(), token.clone(), loading.clone(), more.clone(), browse_id.clone(), title.clone(), source.clone(), add_rows.clone());
        gtk::glib::spawn_future_local(async move {
            let next = match feed::feed_url_of(&browse_id) {
                Some(url) => app.podcasts.show(url, false).await.map(|show| {
                    let library = app.podcasts.library.clone();
                    show.more(&t, |u| library.position(u))
                }),
                None => {
                    let repo = app.repo.clone();
                    let show = ArtistRef { name: title.clone(), browse_id: None };
                    let browse = browse_id.clone();
                    let tok = t.clone();
                    runtime::run(async move { repo.podcast_episodes(&tok, &show, Some(&browse)).await }).await.ok()
                }
            };
            more.set_visible(false);
            loading.set(false);
            let Some(next) = next else { return };
            let fresh: Vec<PodcastEpisode> = next.items.into_iter().filter(|e| !episodes.borrow().iter().any(|k| k.song.video_id == e.song.video_id)).collect();
            let from = episodes.borrow().len();
            add_rows(from, &fresh);
            episodes.borrow_mut().extend(fresh.iter().cloned());
            *token.borrow_mut() = next.continuation.filter(|n| *n != t);
            if app.playback.is_playing_source(&source) {
                app.playback.append_from(fresh.into_iter().map(|e| e.song).collect());
            }
        });
    });
    scroller
}

/// 保存（YouTube）・登録（RSS）のボタン。YouTube の番組は書けてから表示を変える（失敗したら戻す）。RSS の番組はこの端末の登録。
fn save_button(app: &Rc<App>, page: &PodcastPage, saved: bool) -> gtk::Button {
    let button = gtk::Button::builder().valign(gtk::Align::Center).css_classes(["pill"]).build();
    let state = Rc::new(Cell::new(saved));
    let show = {
        let (button, state) = (button.clone(), state.clone());
        move || button.set_label(if state.get() { "保存済み" } else { "ライブラリに保存" })
    };
    show();
    let rss = feed::feed_url_of(&page.browse_id).map(String::from);
    let (app, summary, playlist_id) = (app.clone(), page.summary(), page.playlist_id());
    button.connect_clicked(move |button| {
        let target = !state.get();
        match &rss {
            Some(url) => {
                if target {
                    let mut subscription = feed::FeedSubscription::new(url, &summary.title);
                    subscription.author = summary.author.clone();
                    subscription.thumbnail_url = summary.thumbnail_url.clone();
                    app.podcasts.library.subscribe(subscription);
                    app.toast("番組を登録しました");
                } else {
                    app.podcasts.library.unsubscribe(url);
                    app.toast("登録をやめました");
                }
                state.set(target);
                show();
            }
            None => {
                button.set_sensitive(false);
                let (app, playlist_id, state, show, button) = (app.clone(), playlist_id.clone(), state.clone(), show.clone(), button.clone());
                gtk::glib::spawn_future_local(async move {
                    let repo = app.repo.clone();
                    let ok = runtime::run(async move { repo.set_podcast_saved(&playlist_id, target).await }).await.is_ok();
                    button.set_sensitive(true);
                    if ok {
                        state.set(target);
                        show();
                        super::library::reload(&app);
                    } else {
                        app.toast("ライブラリを変更できませんでした");
                    }
                });
            }
        }
    });
    button
}

/// エピソードの行（画像・題・「番組 • 日付」か「日付 • 長さ」・説明2行・聴いたところのバー）。押すと on_play、右クリックと「…」で曲のメニュー。
pub fn episode_row(app: &Rc<App>, episode: &PodcastEpisode, show_show: bool, on_play: Rc<dyn Fn()>) -> gtk::ListBoxRow {
    track::ensure_style();
    let line = gtk::Box::builder().spacing(12).margin_top(8).margin_bottom(8).build();
    let art = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).width_request(if show_show { 64 } else { 112 }).height_request(if show_show { 64 } else { 63 }).valign(gtk::Align::Start).css_classes(["artwork", "artwork-frame"]).build();
    art.set_overflow(gtk::Overflow::Hidden);
    if let Some(url) = episode.song.thumbnail_url.as_deref() {
        let target = art.clone();
        images::texture(url, 224, move |texture| target.set_paintable(Some(&texture)));
    }
    line.append(&art);
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(3).hexpand(true).build();
    let title = gtk::Label::builder().label(&episode.song.title).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["heading"]).build();
    text.append(&title);
    let detail_row = gtk::Box::builder().spacing(4).build();
    let mark = gtk::Image::builder().icon_name("folder-download-symbolic").pixel_size(12).tooltip_text("ダウンロード済み").css_classes(["dim-label"]).visible(false).build();
    {
        let (downloads, id) = (app.downloads.clone(), episode.song.video_id.clone());
        app.downloads.watch(&mark, move |mark| mark.set_visible(downloads.state(&id) == crate::downloads::SongState::Done));
    }
    detail_row.append(&mark);
    let detail = if show_show { episode.subtitle() } else { episode.date_and_length() };
    if !detail.is_empty() {
        detail_row.append(&gtk::Label::builder().label(detail).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["caption", "dim-label"]).build());
    }
    text.append(&detail_row);
    if !show_show && let Some(description) = episode.description.as_deref().map(str::trim).filter(|d| !d.is_empty()) {
        let one = description.replace("\r\n", "\n").replace(['\r', '\n'], " ");
        text.append(&gtk::Label::builder().label(one).xalign(0.0).wrap(true).wrap_mode(gtk::pango::WrapMode::WordChar).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["dim-label"]).build());
    }
    if let Some(percent) = episode.progress_percent {
        text.append(&gtk::ProgressBar::builder().fraction(f64::from(percent.min(100)) / 100.0).width_request(160).halign(gtk::Align::Start).margin_top(2).tooltip_text(if percent >= 100 { "聴いた".to_string() } else { format!("{percent}% 聴いた") }).build());
    }
    line.append(&text);
    let state = gtk::Image::builder().icon_name("media-playback-start-symbolic").pixel_size(20).valign(gtk::Align::Center).opacity(0.0).build();
    line.append(&state);
    let row = gtk::ListBoxRow::builder().child(&line).build();
    let menu = super::song_menu::button(app, &episode.song, None, Some(row.upcast_ref()));
    menu.set_opacity(0.0);
    line.append(&menu);
    // 乗せたときだけ再生の絵と「…」を出し、今の回はスピーカーの絵とアクセントの色
    let current = Rc::new(Cell::new(false));
    let refresh = {
        let (state, menu, current) = (state.clone(), menu.downgrade(), current.clone());
        move |row: &gtk::ListBoxRow| {
            let hover = row.state_flags().contains(gtk::StateFlags::PRELIGHT);
            state.set_opacity(if hover || current.get() { 1.0 } else { 0.0 });
            if let Some(menu) = menu.upgrade() {
                menu.set_opacity(if hover || menu.is_active() { 1.0 } else { 0.0 });
            }
        }
    };
    {
        let refresh = refresh.clone();
        row.connect_state_flags_changed(move |row, _| refresh(row));
    }
    {
        let (playback, id, weak) = (app.playback.clone(), episode.song.video_id.clone(), row.downgrade());
        app.playback.watch(&title, move |title| {
            let now = playback.current().is_some_and(|s| s.video_id == id);
            current.set(now);
            state.set_icon_name(Some(if now { "audio-volume-high-symbolic" } else { "media-playback-start-symbolic" }));
            if now {
                title.add_css_class("accent-text");
                state.add_css_class("accent-text");
            } else {
                title.remove_css_class("accent-text");
                state.remove_css_class("accent-text");
            }
            if let Some(row) = weak.upgrade() {
                refresh(&row);
            }
        });
    }
    row.set_activatable(true);
    let click = gtk::GestureClick::builder().button(1).build();
    click.connect_released(move |gesture, _, _, _| {
        gesture.set_state(gtk::EventSequenceState::Claimed);
        on_play();
    });
    line.add_controller(click);
    row
}

/// 番組のカード（検索の結果・マイライブラリの棚）。押すと番組のページ。on_open は開く前に呼ぶ（検索の履歴に残す）。
pub fn card(app: &Rc<App>, summary: &PodcastSummary, on_open: Option<Rc<dyn Fn()>>) -> gtk::Widget {
    track::ensure_style();
    let art = images::square(track::CARD_SIZE);
    art.load(summary.thumbnail_url.as_deref(), track::CARD_SIZE as u32 * 2);
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).width_request(track::CARD_SIZE).css_classes(["media-card"]).build();
    column.append(&art.root);
    column.append(&gtk::Label::builder().label(&summary.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).width_request(track::CARD_SIZE).css_classes(["heading"]).build());
    column.append(&gtk::Label::builder().label(summary.author.as_deref().unwrap_or("ポッドキャスト")).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).width_request(track::CARD_SIZE).css_classes(["caption", "dim-label"]).build());
    column.set_cursor_from_name(Some("pointer"));
    let click = gtk::GestureClick::new();
    let (app, id, title) = (app.clone(), summary.browse_id.clone(), summary.title.clone());
    click.connect_released(move |gesture, _, _, _| {
        gesture.set_state(gtk::EventSequenceState::Claimed);
        if let Some(f) = &on_open {
            f();
        }
        app.push(show(&app, &id, &title));
    });
    column.add_controller(click);
    column.upcast()
}

/// RSS の番組を探して足す: Apple の公開の目録で探すか、RSS の URL を打ち込む。結果を押すと番組のページ（そこで登録）。
pub fn find_feed(app: &Rc<App>) -> adw::NavigationPage {
    let column = column();
    column.append(&track::page_title("RSS の番組を探す"));
    column.append(&gtk::Label::builder().label("番組名で探すか、RSS の URL（https://…）を入れてください。YouTube に無い番組も聴けます").xalign(0.0).wrap(true).css_classes(["dim-label"]).build());
    let entry = gtk::SearchEntry::builder().placeholder_text("番組名・RSS の URL").build();
    column.append(&entry);
    let results = adw::Bin::new();
    column.append(&results);
    let app = app.clone();
    entry.connect_activate(move |entry| {
        let text = entry.text().trim().to_string();
        if text.is_empty() {
            return;
        }
        results.set_child(Some(&adw::Spinner::builder().width_request(24).height_request(24).build()));
        let (app, results) = (app.clone(), results.clone());
        gtk::glib::spawn_future_local(async move {
            // URL ならその番組を開く
            if let Some(url) = feed::normalize_feed_url(&text) {
                results.set_child(None::<&gtk::Widget>);
                app.push(show(&app, &feed::show_browse_id(&url), "RSS の番組"));
                return;
            }
            let client = app.podcasts.client.clone();
            let found = runtime::run(async move { client.search(&text).await }).await;
            match found {
                Some(shows) if !shows.is_empty() => {
                    let cards = shows.iter().map(|s| card(&app, s, None)).collect();
                    results.set_child(Some(&track::card_grid(cards)));
                }
                Some(_) => results.set_child(Some(&gtk::Label::builder().label("見つかりませんでした").css_classes(["dim-label"]).build())),
                None => results.set_child(Some(&gtk::Label::builder().label("探せませんでした（通信を確かめてください）").css_classes(["dim-label"]).build())),
            }
        });
    });
    page("RSS の番組を探す", "feed-search", &scrolling(&column))
}
