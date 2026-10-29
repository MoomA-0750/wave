//! ポッドキャストの画面（Windows の PodcastPage・ライブラリのポッドキャストと同じ中身）:
//! 番組のページ（YouTube の MPSP… と RSS の rss:…。保存・登録、エピソードの一覧と続き）、マイライブラリの「ポッドキャスト」
//! （新しいエピソード・保存した番組・RSS で登録した番組・RSS の番組を探して足す）。

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use wave_core::music::feed;
use wave_core::music::models::{ArtistRef, Song};
use wave_core::music::podcast::{PodcastEpisode, PodcastPage, PodcastSummary};

use super::images;
use super::shell::App;
use crate::runtime;

fn page(title: &str, tag: &str, child: &impl IsA<gtk::Widget>) -> adw::NavigationPage {
    adw::NavigationPage::builder().title(title).tag(tag).child(child).build()
}

fn holder() -> adw::Bin {
    adw::Bin::builder().child(&adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).valign(gtk::Align::Center).build()).build()
}

fn failure(text: &str) -> gtk::Widget {
    adw::StatusPage::builder().icon_name("network-error-symbolic").title(text).vexpand(true).build().upcast()
}

fn column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).margin_top(20).margin_bottom(24).margin_start(20).margin_end(20).build()
}

fn scrolling(column: &gtk::Box) -> gtk::Widget {
    let clamp = adw::Clamp::builder().maximum_size(960).tightening_threshold(720).child(column).build();
    gtk::ScrolledWindow::builder().child(&clamp).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build().upcast()
}

/// 番組のページ。browse_id が rss: なら RSS の番組（登録）、MPSP… なら YouTube の番組（保存）。
pub fn show(app: &Rc<App>, browse_id: &str, title: &str) -> adw::NavigationPage {
    let bin = holder();
    let (app, target, id) = (app.clone(), bin.clone(), browse_id.to_string());
    gtk::glib::spawn_future_local(async move {
        let content: gtk::Widget = match feed::feed_url_of(&id) {
            Some(url) => match app.podcasts.show(url, false).await {
                Some(show) => {
                    let library = app.podcasts.library.clone();
                    let page = show.to_page(Some(library.is_subscribed(url)), feed::PAGE_SIZE, |u| library.position(u));
                    show_content(&app, page)
                }
                None => failure("番組を読めませんでした"),
            },
            None => {
                let repo = app.repo.clone();
                let browse = id.clone();
                match runtime::run(async move { repo.podcast(&browse).await }).await {
                    Ok(page) => show_content(&app, page),
                    Err(e) => failure(&format!("番組を読めませんでした（{e}）")),
                }
            }
        };
        target.set_child(Some(&content));
    });
    page(title, "podcast", &bin)
}

fn show_content(app: &Rc<App>, page: PodcastPage) -> gtk::Widget {
    let column = column();
    let header = gtk::Box::builder().spacing(20).build();
    let art = images::square(180);
    art.load(page.thumbnail_url.as_deref(), 360);
    header.append(&art.root);
    let info = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).valign(gtk::Align::End).build();
    info.append(&gtk::Label::builder().label(&page.title).xalign(0.0).wrap(true).css_classes(["title-1"]).build());
    if let Some(author) = &page.author {
        info.append(&gtk::Label::builder().label(author).xalign(0.0).css_classes(["dim-label"]).build());
    }
    let buttons = gtk::Box::builder().spacing(12).build();
    let episodes: Rc<RefCell<Vec<PodcastEpisode>>> = Rc::new(RefCell::new(page.episodes.clone()));
    let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("最新のエピソードから再生").css_classes(["circular", "suggested-action", "play-large"]).build();
    {
        let (app, episodes) = (app.clone(), episodes.clone());
        play.connect_clicked(move |_| {
            let songs: Vec<Song> = episodes.borrow().iter().map(|e| e.song.clone()).collect();
            app.playback.play_list(songs, 0);
        });
    }
    buttons.append(&play);
    // 保存（YouTube）・登録（RSS）
    if let Some(saved) = page.saved {
        let rss = feed::feed_url_of(&page.browse_id).map(String::from);
        let is_rss = rss.is_some();
        let label = move |on: bool| match (is_rss, on) {
            (true, true) => "登録済み",
            (true, false) => "登録",
            (false, true) => "保存済み",
            (false, false) => "ライブラリに保存",
        };
        let save = gtk::ToggleButton::builder().label(label(saved)).active(saved).valign(gtk::Align::Center).css_classes(["pill"]).build();
        let (app, summary, playlist_id) = (app.clone(), page.summary(), page.playlist_id());
        save.connect_toggled(move |button| {
            let on = button.is_active();
            button.set_label(label(on));
            match &rss {
                Some(url) => {
                    if on {
                        let mut subscription = feed::FeedSubscription::new(url, &summary.title);
                        subscription.author = summary.author.clone();
                        subscription.thumbnail_url = summary.thumbnail_url.clone();
                        app.podcasts.library.subscribe(subscription);
                        app.toast("番組を登録しました");
                    } else {
                        app.podcasts.library.unsubscribe(url);
                        app.toast("登録をやめました");
                    }
                }
                None => {
                    let (app, playlist_id) = (app.clone(), playlist_id.clone());
                    gtk::glib::spawn_future_local(async move {
                        let repo = app.repo.clone();
                        let ok = runtime::run(async move { repo.set_podcast_saved(&playlist_id, on).await }).await.is_ok();
                        app.toast(match (ok, on) {
                            (true, true) => "ライブラリに保存しました",
                            (true, false) => "ライブラリから外しました",
                            (false, _) => "保存を変えられませんでした",
                        });
                    });
                }
            }
        });
        buttons.append(&save);
    }
    info.append(&buttons);
    header.append(&info);
    column.append(&header);
    if let Some(description) = page.description.as_deref().filter(|d| !d.trim().is_empty()) {
        let text: String = description.chars().take(600).collect();
        column.append(&gtk::Label::builder().label(text).wrap(true).xalign(0.0).css_classes(["dim-label"]).build());
    }
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list-separate", "song-list"]).build();
    for episode in page.episodes.iter() {
        list.append(&episode_row(episode, &episode.date_and_length()));
    }
    {
        let (app, episodes) = (app.clone(), episodes.clone());
        list.connect_row_activated(move |_, row| {
            let songs: Vec<Song> = episodes.borrow().iter().map(|e| e.song.clone()).collect();
            app.playback.play_list(songs, row.index().max(0) as usize);
        });
    }
    column.append(&list);
    // 確かめ用: WAVE_DEMO_PODCAST_PLAY があれば、開いたらすぐ最新の回を流す
    if std::env::var_os("WAVE_DEMO_PODCAST_PLAY").is_some() {
        let songs: Vec<Song> = episodes.borrow().iter().map(|e| e.song.clone()).collect();
        app.playback.play_list(songs, 0);
    }
    // 続き（YouTube は続きのトークン、RSS は読んだ番組の続き）
    let more = gtk::Button::builder().label("もっと見る").halign(gtk::Align::Center).visible(page.continuation.is_some()).css_classes(["pill"]).build();
    let token = Rc::new(RefCell::new(page.continuation.clone()));
    {
        let (app, list, episodes, token, browse_id, title) = (app.clone(), list.clone(), episodes.clone(), token.clone(), page.browse_id.clone(), page.title.clone());
        more.connect_clicked(move |button| {
            let Some(t) = token.borrow().clone() else { return };
            button.set_sensitive(false);
            let (app, list, episodes, token, browse_id, title, button) = (app.clone(), list.clone(), episodes.clone(), token.clone(), browse_id.clone(), title.clone(), button.clone());
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
                        runtime::run(async move { repo.podcast_episodes(&t, &show, Some(&browse)).await }).await.ok()
                    }
                };
                button.set_sensitive(true);
                let Some(next) = next else {
                    app.toast("続きを読めませんでした");
                    return;
                };
                for episode in &next.items {
                    list.append(&episode_row(episode, &episode.date_and_length()));
                }
                episodes.borrow_mut().extend(next.items);
                button.set_visible(next.continuation.is_some());
                *token.borrow_mut() = next.continuation;
            });
        });
    }
    column.append(&more);
    scrolling(&column)
}

/// エピソードの行（絵・題・補足・聴いたところ）。押すと一覧の中のその回から流す（行の並びのまま）。
fn episode_row(episode: &PodcastEpisode, subtitle: &str) -> adw::ActionRow {
    let row = adw::ActionRow::builder()
        .title(gtk::glib::markup_escape_text(&episode.song.title))
        .subtitle(gtk::glib::markup_escape_text(subtitle))
        .title_lines(2)
        .subtitle_lines(1)
        .activatable(true)
        .build();
    let art = images::square(48);
    art.load(episode.song.thumbnail_url.as_deref(), 96);
    row.add_prefix(&art.root);
    if let Some(percent) = episode.progress_percent {
        let label = if percent >= 100 { "聴いた".to_string() } else { format!("{percent}%") };
        row.add_suffix(&gtk::Label::builder().label(label).css_classes(["caption", "dim-label"]).build());
    }
    row
}

/// 番組のカード（押すと番組のページ）。
fn show_card(app: &Rc<App>, summary: &PodcastSummary) -> gtk::Widget {
    let art = images::square(140);
    art.load(summary.thumbnail_url.as_deref(), 280);
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).width_request(140).build();
    column.append(&art.root);
    column.append(&gtk::Label::builder().label(&summary.title).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["heading"]).build());
    if let Some(author) = &summary.author {
        column.append(&gtk::Label::builder().label(author).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["caption", "dim-label"]).build());
    }
    let button = gtk::Button::builder().child(&column).css_classes(["flat", "card-button"]).build();
    let (app, id, title) = (app.clone(), summary.browse_id.clone(), summary.title.clone());
    button.connect_clicked(move |_| app.push(show(&app, &id, &title)));
    button.upcast()
}

fn shelf(app: &Rc<App>, title: &str, shows: &[PodcastSummary]) -> gtk::Widget {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 8);
    column.append(&gtk::Label::builder().label(title).xalign(0.0).css_classes(["title-3"]).build());
    let row = gtk::Box::builder().spacing(16).valign(gtk::Align::Start).build();
    for summary in shows {
        row.append(&show_card(app, summary));
    }
    column.append(&gtk::ScrolledWindow::builder().child(&row).vscrollbar_policy(gtk::PolicyType::Never).propagate_natural_height(true).build());
    column.upcast()
}

/// マイライブラリの「ポッドキャスト」: 新しいエピソード（YouTube と RSS を合わせて）・保存した番組・RSS で登録した番組・番組を探して足す。
pub fn library(app: &Rc<App>) -> adw::NavigationPage {
    let bin = holder();
    let (app, target) = (app.clone(), bin.clone());
    gtk::glib::spawn_future_local(async move {
        let column = column();
        let title_row = gtk::Box::builder().spacing(12).build();
        title_row.append(&gtk::Label::builder().label("ポッドキャスト").xalign(0.0).hexpand(true).css_classes(["title-1"]).build());
        let add = gtk::Button::builder().label("RSS の番組を探して足す").valign(gtk::Align::Center).css_classes(["pill"]).build();
        {
            let app = app.clone();
            add.connect_clicked(move |_| app.push(find_feed(&app)));
        }
        title_row.append(&add);
        column.append(&title_row);
        let repo = app.repo.clone();
        let (new_episodes, saved) = runtime::run(async move { tokio::join!(repo.new_episodes(), repo.library_podcasts()) }).await;
        let feeds = app.podcasts.subscribed_shows().await;
        // 新しいエピソード（保存した番組が無ければ空として出す。失敗とは出さない）
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64);
        let library = app.podcasts.library.clone();
        let youtube = new_episodes.map(|p| p.items).unwrap_or_default();
        let feed_refs: Vec<&wave_core::music::feed::FeedShow> = feeds.iter().map(|s| s.as_ref()).collect();
        let merged = merge_new(&youtube, &feed_refs, now, &library);
        column.append(&gtk::Label::builder().label("新しいエピソード").xalign(0.0).css_classes(["title-3"]).build());
        if merged.is_empty() {
            column.append(&gtk::Label::builder().label("新しいエピソードはありません").xalign(0.0).css_classes(["dim-label"]).build());
        } else {
            let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list-separate", "song-list"]).build();
            for episode in merged.iter().take(30) {
                list.append(&episode_row(episode, &episode.subtitle()));
            }
            let songs: Vec<Song> = merged.iter().take(30).map(|e| e.song.clone()).collect();
            let app = app.clone();
            list.connect_row_activated(move |_, row| app.playback.play_list(songs.clone(), row.index().max(0) as usize));
            column.append(&list);
        }
        let saved = saved.unwrap_or_default();
        if !saved.is_empty() {
            column.append(&shelf(&app, "保存した番組", &saved));
        }
        let subscribed: Vec<PodcastSummary> = app.podcasts.library.state().subscriptions.iter().map(|s| s.summary()).collect();
        if !subscribed.is_empty() {
            column.append(&shelf(&app, "RSS で登録した番組", &subscribed));
        }
        target.set_child(Some(&scrolling(&column)));
    });
    page("ポッドキャスト", "podcasts", &bin)
}

fn merge_new(youtube: &[PodcastEpisode], feeds: &[&wave_core::music::feed::FeedShow], now: i64, library: &wave_core::music::feed_library::FeedLibrary) -> Vec<PodcastEpisode> {
    // new_episodes::merge は番組を値で受けるので、参照の並びを渡す形に合わせる
    wave_core::music::new_episodes::merge_refs(youtube, feeds, now, |u| library.position(u))
}

/// RSS の番組を探して足す: Apple の公開の目録で探すか、RSS の URL を打ち込む。結果を押すと番組のページ（そこで登録）。
pub fn find_feed(app: &Rc<App>) -> adw::NavigationPage {
    let column = column();
    column.append(&gtk::Label::builder().label("RSS の番組を探す").xalign(0.0).css_classes(["title-1"]).build());
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
                    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).build();
                    for summary in shows {
                        let row = adw::ActionRow::builder()
                            .title(gtk::glib::markup_escape_text(&summary.title))
                            .subtitle(gtk::glib::markup_escape_text(summary.author.as_deref().unwrap_or("")))
                            .activatable(true)
                            .build();
                        let art = images::square(48);
                        art.load(summary.thumbnail_url.as_deref(), 96);
                        row.add_prefix(&art.root);
                        let (app, id, title) = (app.clone(), summary.browse_id.clone(), summary.title.clone());
                        row.connect_activated(move |_| app.push(show(&app, &id, &title)));
                        list.append(&row);
                    }
                    results.set_child(Some(&list));
                }
                Some(_) => results.set_child(Some(&gtk::Label::builder().label("見つかりませんでした").css_classes(["dim-label"]).build())),
                None => results.set_child(Some(&gtk::Label::builder().label("探せませんでした（通信を確かめてください）").css_classes(["dim-label"]).build())),
            }
        });
    });
    page("RSS の番組を探す", "feed-search", &scrolling(&column))
}

/// 検索の結果の行（番組・エピソード）。
pub fn search_shows(app: &Rc<App>, shows: Vec<PodcastSummary>) -> gtk::Widget {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).build();
    for summary in shows {
        let row = adw::ActionRow::builder()
            .title(gtk::glib::markup_escape_text(&summary.title))
            .subtitle(gtk::glib::markup_escape_text(summary.author.as_deref().unwrap_or("ポッドキャスト")))
            .activatable(true)
            .build();
        let art = images::square(48);
        art.load(summary.thumbnail_url.as_deref(), 96);
        row.add_prefix(&art.root);
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let (app, id, title) = (app.clone(), summary.browse_id.clone(), summary.title.clone());
        row.connect_activated(move |_| app.push(show(&app, &id, &title)));
        list.append(&row);
    }
    list.upcast()
}

pub fn search_episodes(app: &Rc<App>, episodes: Vec<PodcastEpisode>) -> gtk::Widget {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list-separate", "song-list"]).build();
    for episode in &episodes {
        list.append(&episode_row(episode, &episode.subtitle()));
    }
    let songs: Vec<Song> = episodes.into_iter().map(|e| e.song).collect();
    let app = app.clone();
    list.connect_row_activated(move |_, row| app.playback.play_list(vec![songs[row.index().max(0) as usize].clone()], 0));
    list.upcast()
}
