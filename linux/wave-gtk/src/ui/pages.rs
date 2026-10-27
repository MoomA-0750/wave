//! 中央に積むページ（ホーム・検索の結果・プレイリスト）。並びは Mac・Windows と同じ。

use std::rc::Rc;

use adw::prelude::*;
use wave_core::music::models::{HomeItem, Song};

use super::images;
use super::shell::App;
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

/// ホーム: 棚ごとに見出しと横に流れるカード。
pub fn home(app: &Rc<App>) -> adw::NavigationPage {
    let bin = holder();
    let app = app.clone();
    let target = bin.clone();
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let result = runtime::run(async move { repo.home(None, None).await }).await;
        let content: gtk::Widget = match result {
            Ok(home) => {
                let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(24).margin_top(16).margin_bottom(24).build();
                for shelf in home.shelves {
                    column.append(&shelf_widget(&app, &shelf.title, shelf.strapline.as_deref(), shelf.items));
                }
                gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build().upcast()
            }
            Err(e) => failure(&format!("ホームを読めませんでした（{e}）")),
        };
        target.set_child(Some(&content));
    });
    page("ホーム", "home", &bin)
}

/// 見出しと、横に流れるカードの棚。
fn shelf_widget(app: &Rc<App>, title: &str, strapline: Option<&str>, items: Vec<HomeItem>) -> gtk::Widget {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 8);
    let header = gtk::Box::new(gtk::Orientation::Vertical, 2);
    header.set_margin_start(20);
    if let Some(strapline) = strapline {
        header.append(&gtk::Label::builder().label(strapline).xalign(0.0).css_classes(["caption", "dim-label"]).build());
    }
    header.append(&gtk::Label::builder().label(title).xalign(0.0).css_classes(["title-2"]).build());
    column.append(&header);
    let row = gtk::Box::builder().spacing(16).margin_start(20).margin_end(20).valign(gtk::Align::Start).build();
    for item in items {
        row.append(&card(app, item));
    }
    // 横に流れる棚は、中身の高さをそのまま使う（測らないと2行目の補足が切れる）
    column.append(&gtk::ScrolledWindow::builder().child(&row).vscrollbar_policy(gtk::PolicyType::Never).propagate_natural_height(true).build());
    column.upcast()
}

const CARD_SIZE: i32 = 160;

/// ホームの棚のカード（ジャケット・名前・補足）。押すと曲は鳴らし、プレイリストは開く。
fn card(app: &Rc<App>, item: HomeItem) -> gtk::Widget {
    let picture = images::square(CARD_SIZE);
    if matches!(item, HomeItem::Artist(_)) {
        picture.add_css_class("round");
    }
    picture.load(item.thumbnail_url(), CARD_SIZE as u32 * 2);
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).width_request(CARD_SIZE).build();
    column.append(&picture.root);
    column.append(&gtk::Label::builder().label(item.title()).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["heading"]).build());
    if let Some(subtitle) = item.subtitle() {
        column.append(&gtk::Label::builder().label(subtitle).xalign(0.0).wrap(true).lines(2).ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(1).css_classes(["caption", "dim-label"]).build());
    }
    let button = gtk::Button::builder().child(&column).css_classes(["flat", "card-button"]).build();
    let app = app.clone();
    button.connect_clicked(move |_| match &item {
        HomeItem::Song(song) => app.playback.play_list(vec![song.clone()], 0),
        HomeItem::Playlist(p) => app.push(playlist(&app, &p.playlist_id, &p.title)),
        HomeItem::Album(a) => app.push(album(&app, &a.browse_id, &a.title)),
        HomeItem::Artist(a) => app.push(artist(&app, &a.browse_id, &a.name)),
    });
    button.upcast()
}

/// 曲の一覧（検索の結果・プレイリスト）。行を押すと、その一覧をキューにしてその曲から鳴らす。
pub fn song_list(app: &Rc<App>, songs: Vec<Song>) -> gtk::ListBox {
    song_list_in(app, songs, None)
}

/// 自分のプレイリストの曲の一覧（曲のメニューに「このプレイリストから削除」を出す）。set_video_ids は曲ごとのプレイリストの中の項目の ID。
pub fn song_list_in(app: &Rc<App>, songs: Vec<Song>, playlist: Option<(String, Vec<Option<String>>)>) -> gtk::ListBox {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list-separate", "song-list"]).build();
    // 外した行があっても正しい曲を流せるよう、行ごとに曲を覚えておき、押した時点で残っている行から並べ直す
    let shown: Rc<std::cell::RefCell<Vec<(gtk::ListBoxRow, Song)>>> = Rc::default();
    for (i, song) in songs.iter().enumerate() {
        let removal = playlist.as_ref().and_then(|(playlist_id, set_ids)| {
            let set_video_id = set_ids.get(i).cloned().flatten()?;
            let (list, shown) = (list.downgrade(), shown.clone());
            let row_index = i;
            Some(super::song_menu::Removal {
                playlist_id: playlist_id.clone(),
                set_video_id,
                on_removed: Rc::new(move || {
                    let mut rows = shown.borrow_mut();
                    if let (Some(list), Some(pos)) = (list.upgrade(), rows.iter().position(|(r, _)| r.widget_name() == format!("song-{row_index}"))) {
                        list.remove(&rows[pos].0);
                        rows.remove(pos);
                    }
                }),
            })
        });
        let row = song_row(app, song, removal);
        row.set_widget_name(&format!("song-{i}"));
        list.append(&row);
        shown.borrow_mut().push((row.upcast(), song.clone()));
    }
    let app = app.clone();
    list.connect_row_activated(move |_, row| {
        let rows = shown.borrow();
        let songs: Vec<Song> = rows.iter().map(|(_, s)| s.clone()).collect();
        if let Some(index) = rows.iter().position(|(r, _)| r == row) {
            app.playback.play_list(songs, index);
        }
    });
    list
}

fn song_row(app: &Rc<App>, song: &Song, removal: Option<super::song_menu::Removal>) -> adw::ActionRow {
    let picture = images::square(44);
    picture.load(song.thumbnail_url.as_deref(), 88);
    let row = adw::ActionRow::builder()
        .title(gtk::glib::markup_escape_text(&song.title))
        .subtitle(gtk::glib::markup_escape_text(&song.subtitle()))
        .title_lines(1)
        .subtitle_lines(1)
        .activatable(true)
        .build();
    row.add_prefix(&picture.root);
    if let Some(seconds) = song.duration_seconds {
        row.add_suffix(&gtk::Label::builder().label(format_seconds(seconds)).css_classes(["dim-label", "numeric"]).build());
    }
    // 取った曲・取っている途中の曲の印
    let mark = gtk::Image::builder().pixel_size(16).css_classes(["dim-label"]).visible(false).build();
    let id = song.video_id.clone();
    let downloads = app.downloads.clone();
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
    row.add_suffix(&mark);
    row.add_suffix(&super::song_menu::button(app, song, removal, Some(row.upcast_ref())));
    row
}

pub fn format_seconds(seconds: u32) -> String {
    let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60);
    if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m}:{s:02}") }
}

/// 検索の結果（曲）。
pub fn search(app: &Rc<App>, query: &str) -> adw::NavigationPage {
    let bin = holder();
    let app = app.clone();
    let target = bin.clone();
    let q = query.to_string();
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let query = q.clone();
        let result = runtime::run(async move { repo.search_songs(&query, None).await }).await;
        let content: gtk::Widget = match result {
            Ok(page) if page.songs.is_empty() => adw::StatusPage::builder().icon_name("edit-find-symbolic").title("見つかりませんでした").vexpand(true).build().upcast(),
            Ok(page) => {
                let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(16).margin_bottom(24).margin_start(20).margin_end(20).build();
                column.append(&gtk::Label::builder().label("曲").xalign(0.0).css_classes(["title-2"]).build());
                column.append(&song_list(&app, page.songs));
                gtk::ScrolledWindow::builder().child(&clamp(&column)).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build().upcast()
            }
            Err(e) => failure(&format!("検索できませんでした（{e}）")),
        };
        target.set_child(Some(&content));
    });
    page(&format!("「{query}」の検索"), "search", &bin)
}

/// プレイリストのページ（頭にジャケット・名前・再生のボタン、下に曲）。
pub fn playlist(app: &Rc<App>, playlist_id: &str, title: &str) -> adw::NavigationPage {
    let bin = holder();
    let app = app.clone();
    let target = bin.clone();
    let id = playlist_id.to_string();
    let id_for_list = id.clone();
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let result = runtime::run(async move { repo.playlist(&id, None).await }).await;
        let content: gtk::Widget = match result {
            Ok(page) => {
                let column = page_column();
                let kind = if id_for_list == wave_core::music::repository::LIKED_SONGS { wave_core::download::manifest::KIND_LIKED } else { wave_core::download::manifest::KIND_PLAYLIST };
                let collection = Collection { kind, id: id_for_list.clone() };
                // 丸ごと取るときは最後のページまで読んだ曲にする（最初のページだけだと途中までしか取らない）
                let all_songs = if page.continuation.is_some() { full_playlist(&app, &id_for_list, &page).await } else { page.songs.clone() };
                column.append(&page_header_with(&app, page.thumbnail_url.as_deref(), page.title.as_deref().unwrap_or(""), page.subtitle.as_deref(), false, all_songs, Some(collection)));
                // 自分のプレイリストなら、曲のメニューから外せる
                let removable = page.editable.then(|| (id_for_list.clone(), page.set_video_ids.clone()));
                column.append(&song_list_in(&app, page.songs, removable));
                scrolling_column(&column)
            }
            // 通信できなくても、丸ごと取ったプレイリストは目録から出す
            Err(e) => offline_collection(&app, &id_for_list).unwrap_or_else(|| failure(&format!("プレイリストを読めませんでした（{e}）"))),
        };
        target.set_child(Some(&content));
    });
    page(title, "playlist", &bin)
}

/// ページの頭（ジャケット・名前・補足・再生のボタン）。round ならアーティストの丸い写真。
/// 丸ごと取るときの種類と ID（プレイリスト・アルバム・高く評価した曲）。
struct Collection {
    kind: &'static str,
    id: String,
}

fn page_header(app: &Rc<App>, thumbnail: Option<&str>, title: &str, subtitle: Option<&str>, round: bool, songs: Vec<Song>) -> gtk::Widget {
    page_header_with(app, thumbnail, title, subtitle, round, songs, None)
}

fn page_header_with(app: &Rc<App>, thumbnail: Option<&str>, title: &str, subtitle: Option<&str>, round: bool, songs: Vec<Song>, collection: Option<Collection>) -> gtk::Widget {
    let header = gtk::Box::builder().spacing(20).build();
    let picture = images::square(180);
    if round {
        picture.add_css_class("round");
    }
    picture.load(thumbnail, 360);
    header.append(&picture.root);
    let info = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).valign(gtk::Align::End).build();
    info.append(&gtk::Label::builder().label(title).xalign(0.0).wrap(true).css_classes(["title-1"]).build());
    if let Some(subtitle) = subtitle {
        info.append(&gtk::Label::builder().label(subtitle).xalign(0.0).wrap(true).css_classes(["dim-label"]).build());
    }
    if !songs.is_empty() {
        let buttons = gtk::Box::builder().spacing(12).build();
        let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").halign(gtk::Align::Start).css_classes(["circular", "suggested-action", "play-large"]).build();
        let app_for_play = app.clone();
        let songs_for_play = songs.clone();
        play.connect_clicked(move |_| app_for_play.playback.play_list(songs_for_play.clone(), 0));
        buttons.append(&play);
        if let Some(collection) = collection {
            buttons.append(&download_button(app, collection, title, thumbnail, songs));
        }
        info.append(&buttons);
    }
    header.append(&info);
    header.upcast()
}

/// 丸ごと取るボタン（取っていない／途中（何曲中何曲）／取り終えた）。押すと取る・やめる。
fn download_button(app: &Rc<App>, collection: Collection, title: &str, thumbnail: Option<&str>, songs: Vec<Song>) -> gtk::Widget {
    let button = gtk::ToggleButton::builder().icon_name("folder-download-symbolic").tooltip_text("ダウンロード").valign(gtk::Align::Center).css_classes(["circular", "flat"]).build();
    let label = gtk::Label::builder().css_classes(["caption", "dim-label"]).valign(gtk::Align::Center).build();
    let row = gtk::Box::builder().spacing(6).build();
    row.append(&button);
    row.append(&label);
    let updating = Rc::new(std::cell::Cell::new(false));
    {
        let (downloads, id, ids, button, updating) = (app.downloads.clone(), collection.id.clone(), songs.iter().map(|s| s.video_id.clone()).collect::<Vec<_>>(), button.clone(), updating.clone());
        app.downloads.watch(&label, move |label| {
            let on = downloads.manifest().collection(&id).is_some();
            updating.set(true);
            button.set_active(on);
            updating.set(false);
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
    let (app, title, thumbnail) = (app.clone(), title.to_string(), thumbnail.map(String::from));
    button.connect_toggled(move |b| {
        if updating.get() {
            return;
        }
        if b.is_active() {
            app.downloads.download_collection(collection.kind, &collection.id, &title, thumbnail.as_deref(), songs.clone());
        } else {
            app.downloads.remove_collection(&collection.id);
        }
    });
    row.upcast()
}

/// 縦に流れるページの入れ物（余白と幅の上限）。
fn scrolling_column(column: &gtk::Box) -> gtk::Widget {
    gtk::ScrolledWindow::builder().child(&clamp(column)).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build().upcast()
}

fn page_column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).margin_top(20).margin_bottom(24).margin_start(20).margin_end(20).build()
}

/// アルバムのページ（頭にジャケット・名前・アーティスト、下に曲）。
pub fn album(app: &Rc<App>, browse_id: &str, title: &str) -> adw::NavigationPage {
    let bin = holder();
    let (app, target, id) = (app.clone(), bin.clone(), browse_id.to_string());
    let id_for_offline = id.clone();
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let content: gtk::Widget = match runtime::run(async move { repo.album(&id).await }).await {
            Ok(page) => {
                let column = page_column();
                let collection = Collection { kind: wave_core::download::manifest::KIND_ALBUM, id: page.browse_id.clone() };
                column.append(&page_header_with(&app, page.thumbnail_url.as_deref(), &page.title, page.subtitle.as_deref(), false, page.songs.clone(), Some(collection)));
                column.append(&song_list(&app, page.songs));
                scrolling_column(&column)
            }
            // 通信できなくても、丸ごと取ったアルバムは目録から出す
            Err(e) => offline_collection(&app, &id_for_offline).unwrap_or_else(|| failure(&format!("アルバムを読めませんでした（{e}）"))),
        };
        target.set_child(Some(&content));
    });
    page(title, "album", &bin)
}

/// アーティストのページ（頭に丸い写真と名前、人気の曲、アルバムの棚、動画の棚）。
pub fn artist(app: &Rc<App>, browse_id: &str, name: &str) -> adw::NavigationPage {
    let bin = holder();
    let (app, target, id) = (app.clone(), bin.clone(), browse_id.to_string());
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let content: gtk::Widget = match runtime::run(async move { repo.artist(&id).await }).await {
            Ok(page) => {
                let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(24).margin_bottom(24).build();
                let top = page_column();
                top.append(&page_header(&app, page.thumbnail_url.as_deref(), &page.name, None, true, page.top_songs.clone()));
                if !page.top_songs.is_empty() {
                    top.append(&gtk::Label::builder().label("人気の曲").xalign(0.0).css_classes(["title-2"]).build());
                    top.append(&song_list(&app, page.top_songs));
                }
                if let Some(all) = page.all_songs_playlist_id.clone() {
                    let button = gtk::Button::builder().label("すべての曲").halign(gtk::Align::Start).css_classes(["pill"]).build();
                    let (app, name) = (app.clone(), page.name.clone());
                    button.connect_clicked(move |_| app.push(playlist(&app, &all, &name)));
                    top.append(&button);
                }
                column.append(&clamp(&top));
                for shelf in page.album_shelves {
                    column.append(&shelf_widget(&app, &shelf.title, None, shelf.albums.into_iter().map(HomeItem::Album).collect()));
                }
                if !page.videos.is_empty() {
                    column.append(&shelf_widget(&app, "動画", None, page.videos.into_iter().map(HomeItem::Song).collect()));
                }
                gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build().upcast()
            }
            Err(e) => failure(&format!("アーティストを読めませんでした（{e}）")),
        };
        target.set_child(Some(&content));
    });
    page(name, "artist", &bin)
}

/// プレイリストの曲を最後のページまで読む（読めなかったらそこまで）。
async fn full_playlist(app: &Rc<App>, id: &str, first: &wave_core::music::models::PlaylistPage) -> Vec<Song> {
    let (repo, id, mut songs, mut token) = (app.repo.clone(), id.to_string(), first.songs.clone(), first.continuation.clone());
    runtime::run(async move {
        for _ in 0..50 {
            let Some(t) = token.clone() else { break };
            let Ok(next) = repo.playlist(&id, Some(&t)).await else { break };
            songs.extend(next.songs);
            token = next.continuation.filter(|n| *n != t);
        }
        songs
    })
    .await
}

/// 丸ごと取ったものを、目録の曲の並びで出す（通信できないとき）。
fn offline_collection(app: &Rc<App>, id: &str) -> Option<gtk::Widget> {
    let collection = app.downloads.manifest().collection(id).cloned()?;
    let column = page_column();
    let header = Collection { kind: if collection.kind == wave_core::download::manifest::KIND_ALBUM { wave_core::download::manifest::KIND_ALBUM } else { wave_core::download::manifest::KIND_PLAYLIST }, id: collection.id.clone() };
    column.append(&page_header_with(app, collection.thumbnail_url.as_deref(), &collection.title, Some("オフライン（ダウンロードした曲）"), false, collection.songs.clone(), Some(header)));
    column.append(&song_list(app, collection.songs));
    Some(scrolling_column(&column))
}

/// ダウンロードのページ（マイライブラリの「ダウンロード」から）: 使っている容量・取っている途中の曲・丸ごと取ったもの・1曲ずつ取った曲・すべて削除。
pub fn downloads(app: &Rc<App>) -> adw::NavigationPage {
    let column = page_column();
    let title = gtk::Label::builder().label("ダウンロード").xalign(0.0).css_classes(["title-1"]).build();
    let usage = gtk::Label::builder().xalign(0.0).css_classes(["dim-label"]).build();
    let clear = gtk::Button::builder().label("すべて削除").halign(gtk::Align::Start).css_classes(["destructive-action", "pill"]).build();
    let body = gtk::Box::new(gtk::Orientation::Vertical, 16);
    column.append(&title);
    column.append(&usage);
    column.append(&clear);
    column.append(&body);
    {
        let app = app.clone();
        clear.connect_clicked(move |_| {
            app.downloads.clear();
            app.toast("ダウンロードをすべて削除しました");
        });
    }
    let refresh = {
        let (app, usage) = (app.clone(), usage.clone());
        move |body: &gtk::Box| {
            while let Some(child) = body.first_child() {
                body.remove(&child);
            }
            let manifest = app.downloads.manifest();
            let wanted = manifest.wanted_songs();
            let done = wanted.iter().filter(|s| app.downloads.state(&s.video_id) == crate::downloads::SongState::Done).count();
            let progress = app.downloads.progress.lock().unwrap().clone();
            let mut status = format!("{} 曲中 {} 曲を取りました・{}", wanted.len(), done, crate::downloads::format_bytes(app.downloads.total_bytes()));
            if let Some((_, got, total)) = progress.filter(|(_, _, t)| *t > 0) {
                status.push_str(&format!("（取っている曲 {}%）", got * 100 / total.max(1)));
            }
            usage.set_label(&status);
            if wanted.is_empty() {
                body.append(&adw::StatusPage::builder().icon_name("folder-download-symbolic").title("ダウンロードした曲はありません").description("曲のメニュー、プレイリスト・アルバムのページのボタンから取れます").build());
                return;
            }
            if !manifest.collections.is_empty() {
                body.append(&gtk::Label::builder().label("プレイリスト・アルバム").xalign(0.0).css_classes(["title-3"]).build());
                let list = gtk::ListBox::builder().css_classes(["boxed-list"]).selection_mode(gtk::SelectionMode::None).build();
                for c in &manifest.collections {
                    let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&c.title)).subtitle(format!("{} 曲", c.songs.len())).activatable(true).build();
                    let art = images::square(44);
                    art.load(c.thumbnail_url.as_deref(), 88);
                    row.add_prefix(&art.root);
                    let (app, c) = (app.clone(), c.clone());
                    row.connect_activated(move |_| {
                        if c.kind == wave_core::download::manifest::KIND_ALBUM {
                            app.push(album(&app, &c.id, &c.title));
                        } else {
                            app.push(playlist(&app, &c.id, &c.title));
                        }
                    });
                    list.append(&row);
                }
                body.append(&list);
            }
            if !manifest.singles.is_empty() {
                body.append(&gtk::Label::builder().label("曲").xalign(0.0).css_classes(["title-3"]).build());
                body.append(&song_list(&app, manifest.singles.iter().map(|s| s.song.clone()).collect()));
            }
        }
    };
    refresh(&body);
    app.downloads.watch(&body, refresh);
    adw::NavigationPage::builder().title("ダウンロード").tag("downloads").child(&scrolling_column(&column)).build()
}

/// 広い窓で一覧が横に伸びすぎないようにする。
fn clamp(child: &impl IsA<gtk::Widget>) -> adw::Clamp {
    adw::Clamp::builder().maximum_size(960).tightening_threshold(720).child(child).build()
}
