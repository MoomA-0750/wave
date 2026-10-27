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
/// 検索の結果。上の切り替えで「曲・アルバム・アーティスト・ライブ」に絞る（Mac・Windows と同じ）。続きは下の「もっと見る」で読む。
pub fn search(app: &Rc<App>, query: &str) -> adw::NavigationPage {
    let tabs = adw::ToggleGroup::builder().halign(gtk::Align::Start).css_classes(["round"]).build();
    for (name, label) in [("songs", "曲"), ("albums", "アルバム"), ("artists", "アーティスト"), ("live", "ライブ")] {
        tabs.add(adw::Toggle::builder().label(label).name(name).build());
    }
    tabs.set_active_name(Some("songs"));
    let stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).vexpand(true).build();
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(16).margin_start(20).margin_end(20).build();
    column.append(&tabs);
    column.append(&stack);
    // 種類ごとの結果は、初めて開いたときに読む
    let open = {
        let (app, stack, query) = (app.clone(), stack.clone(), query.to_string());
        move |kind: &str| {
            if stack.child_by_name(kind).is_none() {
                stack.add_named(&search_results(&app, &query, kind), Some(kind));
            }
            stack.set_visible_child_name(kind);
        }
    };
    open("songs");
    tabs.connect_active_name_notify(move |tabs| {
        if let Some(name) = tabs.active_name() {
            open(&name);
        }
    });
    page(&format!("「{query}」の検索"), "search", &column)
}

/// 1つの種類の検索の結果（続きを読める一覧）。
fn search_results(app: &Rc<App>, query: &str, kind: &str) -> gtk::Widget {
    let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_bottom(24).build();
    let more = gtk::Button::builder().label("もっと見る").halign(gtk::Align::Center).visible(false).css_classes(["pill"]).build();
    let body = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    body.append(&list);
    body.append(&more);
    let bin = adw::Bin::builder().child(&spinner()).vexpand(true).build();
    let scroller = gtk::ScrolledWindow::builder().child(&clamp(&body)).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
    let continuation: Rc<std::cell::RefCell<Option<String>>> = Rc::default();
    let load: Rc<dyn Fn(bool)> = {
        let (app, query, kind, list, more, bin, scroller, continuation) =
            (app.clone(), query.to_string(), kind.to_string(), list.clone(), more.clone(), bin.clone(), scroller.clone(), continuation.clone());
        Rc::new(move |next: bool| {
            let token = if next { continuation.borrow().clone() } else { None };
            if next && token.is_none() {
                return;
            }
            more.set_sensitive(false);
            let (app, query, kind, list, more, bin, scroller, continuation) =
                (app.clone(), query.clone(), kind.clone(), list.clone(), more.clone(), bin.clone(), scroller.clone(), continuation.clone());
            gtk::glib::spawn_future_local(async move {
                let repo = app.repo.clone();
                let (q, k, t) = (query.clone(), kind.clone(), token.clone());
                let result = runtime::run(async move {
                    let t = t.as_deref();
                    match k.as_str() {
                        "albums" => repo.search_albums(&q, t).await.map(|p| (SearchRows::Albums(p.items), p.continuation)),
                        "artists" => repo.search_artists(&q, t).await.map(|p| (SearchRows::Artists(p.items), p.continuation)),
                        "live" => repo.search_live(&q, t).await.map(|p| (SearchRows::Songs(p.songs), p.continuation)),
                        _ => repo.search_songs(&q, t).await.map(|p| (SearchRows::Songs(p.songs), p.continuation)),
                    }
                })
                .await;
                more.set_sensitive(true);
                match result {
                    Ok((rows, next_token)) => {
                        if !next && rows.is_empty() {
                            bin.set_child(Some(&adw::StatusPage::builder().icon_name("edit-find-symbolic").title("見つかりませんでした").vexpand(true).build()));
                            return;
                        }
                        list.append(&rows.widget(&app));
                        more.set_visible(next_token.is_some());
                        *continuation.borrow_mut() = next_token;
                        bin.set_child(Some(&scroller));
                    }
                    // 読めなかったときは、0件とは分けて理由を出す
                    Err(e) if !next => bin.set_child(Some(&failure(&search_failure(&e)))),
                    Err(e) => app.toast(&search_failure(&e)),
                }
            });
        })
    };
    {
        let load = load.clone();
        more.connect_clicked(move |_| load(true));
    }
    load(false);
    bin.upcast()
}

enum SearchRows {
    Songs(Vec<Song>),
    Albums(Vec<wave_core::music::models::AlbumSummary>),
    Artists(Vec<wave_core::music::models::ArtistSummary>),
}

impl SearchRows {
    fn is_empty(&self) -> bool {
        match self {
            SearchRows::Songs(v) => v.is_empty(),
            SearchRows::Albums(v) => v.is_empty(),
            SearchRows::Artists(v) => v.is_empty(),
        }
    }

    fn widget(self, app: &Rc<App>) -> gtk::Widget {
        match self {
            SearchRows::Songs(songs) => song_list(app, songs).upcast(),
            SearchRows::Albums(albums) => {
                let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).build();
                for album in albums {
                    let row = summary_row(&album.title, album.subtitle.as_deref(), album.thumbnail_url.as_deref(), false);
                    let (app, id, title) = (app.clone(), album.browse_id, album.title);
                    row.connect_activated(move |_| app.push(self::album(&app, &id, &title)));
                    list.append(&row);
                }
                list.upcast()
            }
            SearchRows::Artists(artists) => {
                let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).build();
                for artist in artists {
                    let row = summary_row(&artist.name, artist.subtitle.as_deref(), artist.thumbnail_url.as_deref(), true);
                    let (app, id, name) = (app.clone(), artist.browse_id, artist.name);
                    row.connect_activated(move |_| app.push(self::artist(&app, &id, &name)));
                    list.append(&row);
                }
                list.upcast()
            }
        }
    }
}

/// アルバム・アーティストの行（押すとそのページ）。round ならアーティストの丸い写真。
fn summary_row(title: &str, subtitle: Option<&str>, thumbnail: Option<&str>, round: bool) -> adw::ActionRow {
    let row = adw::ActionRow::builder()
        .title(gtk::glib::markup_escape_text(title))
        .subtitle(gtk::glib::markup_escape_text(subtitle.unwrap_or("")))
        .title_lines(1)
        .subtitle_lines(1)
        .activatable(true)
        .build();
    let art = super::images::square(48);
    if round {
        art.add_css_class("round");
    }
    art.load(thumbnail, 96);
    row.add_prefix(&art.root);
    row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
    row
}

fn search_failure(e: &wave_core::innertube::Failure) -> String {
    use wave_core::innertube::Failure;
    match e {
        Failure::NotLoggedIn => "ログインしていません".into(),
        Failure::Network => "通信できませんでした".into(),
        Failure::Parse => "応答を読み取れませんでした".into(),
        other => format!("検索できませんでした（{other}）"),
    }
}

/// 最近再生した曲（再生履歴。「今日」「昨日」などの区切りごと。Windows と同じくアカウントのメニューから）。
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
                column.append(&gtk::Label::builder().label("最近再生した曲").xalign(0.0).css_classes(["title-1"]).build());
                for (title, songs) in sections {
                    if let Some(title) = title {
                        column.append(&gtk::Label::builder().label(title).xalign(0.0).margin_top(8).css_classes(["title-3"]).build());
                    }
                    column.append(&song_list(&app, songs));
                }
                scrolling_column(&column)
            }
            Err(wave_core::innertube::Failure::NotLoggedIn) => failure("再生履歴を見るにはログインしてください"),
            Err(e) => failure(&format!("再生履歴を読み込めませんでした（{e}）")),
        };
        target.set_child(Some(&content));
    });
    page("最近再生した曲", "history", &bin)
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
            if done < wanted.len() && !app.downloads.network_allows() {
                status.push_str("・今の通信では取らない設定です（設定で変えられます）");
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
            // スマートダウンロード（分類ごとに分けて出す）
            use wave_core::download::smart::SmartCategory;
            for (category, label) in [(SmartCategory::Frequent, "いつも聴いている"), (SmartCategory::Likely, "好きそう"), (SmartCategory::Recommended, "おすすめ")] {
                let songs: Vec<Song> = manifest.smart.iter().filter(|t| t.category == category).map(|t| t.song.clone()).collect();
                if songs.is_empty() {
                    continue;
                }
                body.append(&gtk::Label::builder().label(format!("スマートダウンロード: {label}")).xalign(0.0).css_classes(["title-3"]).build());
                body.append(&song_list(&app, songs));
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
