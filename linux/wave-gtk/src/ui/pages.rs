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
        let result = runtime::run(async move {
            let (home, releases) = tokio::join!(repo.home(None, None), repo.new_releases());
            home.map(|h| (h, releases.unwrap_or_default()))
        })
        .await;
        let content: gtk::Widget = match result {
            Ok((home, new_releases)) => {
                let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(24).margin_top(16).margin_bottom(24).build();
                // 先頭に、登録したアーティストの新作と、YouTube Music の新作（ホームに同じ新作の棚が来ていれば出さない）。Windows と同じ
                let subscribed: Vec<HomeItem> = wave_core::music::releases::diversified(&app.releases.recent(), 2)
                    .into_iter()
                    .take(wave_core::music::releases::NEW_RELEASES_COUNT)
                    .filter_map(|r| {
                        Some(HomeItem::Album(wave_core::music::models::AlbumSummary { browse_id: r.album_browse_id?, title: r.title, subtitle: Some(r.artist_name), thumbnail_url: r.thumbnail_url }))
                    })
                    .collect();
                if !subscribed.is_empty() {
                    column.append(&shelf_widget(&app, "登録したアーティストの新作", None, subscribed));
                }
                if !wave_core::music::releases::duplicates_new_releases(&new_releases, &home.shelves)
                    && let Some(shelf) = wave_core::music::releases::new_releases_shelf("新作", new_releases)
                {
                    column.append(&shelf_widget(&app, &shelf.title, None, shelf.items));
                }
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

/// 見つける: 気分・ジャンルのタイル（Lo-Fi を含む）。押すとそのタイルの棚のページ（Windows の検索の空のページと同じ中身）。
pub fn moods(app: &Rc<App>) -> adw::NavigationPage {
    let bin = holder();
    let app = app.clone();
    let target = bin.clone();
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let result = runtime::run(async move { repo.moods().await }).await;
        let content: gtk::Widget = match result {
            Ok(sections) if !sections.is_empty() => {
                let column = page_column();
                column.append(&gtk::Label::builder().label("見つける").xalign(0.0).css_classes(["title-1"]).build());
                for section in sections {
                    column.append(&gtk::Label::builder().label(&section.title).xalign(0.0).margin_top(8).css_classes(["title-3"]).build());
                    let grid = gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).min_children_per_line(2).max_children_per_line(6).column_spacing(10).row_spacing(10).homogeneous(true).build();
                    for category in section.categories {
                        grid.append(&mood_tile(&app, category));
                    }
                    column.append(&grid);
                }
                scrolling_column(&column)
            }
            Ok(_) => failure("気分・ジャンルを読めませんでした"),
            Err(e) => failure(&format!("気分・ジャンルを読めませんでした（{e}）")),
        };
        target.set_child(Some(&content));
    });
    page("見つける", "moods", &bin)
}

/// 気分・ジャンルのタイル（左に YouTube Music の帯の色）。
fn mood_tile(app: &Rc<App>, category: wave_core::music::models::MoodCategory) -> gtk::Widget {
    let label = gtk::Label::builder().label(&category.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["heading"]).build();
    let stripe = gtk::Box::builder().width_request(6).css_classes(["mood-stripe"]).build();
    if let Some(color) = category.color {
        let provider = gtk::CssProvider::new();
        provider.load_from_string(&format!(".mood-stripe {{ background-color: #{:06x}; border-radius: 3px; }}", color & 0x00ff_ffff));
        #[allow(deprecated)] // 1つの部品だけの色。テーマ全体には足さない
        stripe.style_context().add_provider(&provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
    }
    let row = gtk::Box::builder().spacing(10).margin_top(14).margin_bottom(14).margin_end(10).build();
    row.append(&stripe);
    row.append(&label);
    let button = gtk::Button::builder().child(&row).css_classes(["card"]).build();
    let app = app.clone();
    button.connect_clicked(move |_| app.push(mood_category(&app, &category.title, &category.params)));
    button.upcast()
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
                let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(24).margin_top(16).margin_bottom(24).build();
                column.append(&gtk::Label::builder().label(&heading).xalign(0.0).margin_start(20).css_classes(["title-1"]).build());
                for shelf in home.shelves {
                    column.append(&shelf_widget(&app, &shelf.title, shelf.strapline.as_deref(), shelf.items));
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
    let tabs = adw::ToggleGroup::builder().halign(gtk::Align::Start).homogeneous(false).css_classes(["round"]).build();
    for (name, label) in [("songs", "曲"), ("albums", "アルバム"), ("artists", "アーティスト"), ("live", "ライブ"), ("podcasts", "ポッドキャスト"), ("episodes", "エピソード"), ("lyrics", "歌詞")] {
        // 既定の名前の文字は縮めると切れるので、切れない文字を中身にする
        tabs.add(adw::Toggle::builder().child(&gtk::Label::new(Some(label))).name(name).build());
    }
    tabs.set_active_name(Some("songs"));
    let stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).vexpand(true).build();
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(16).margin_start(20).margin_end(20).build();
    // 種類が多く欄に収まらないときは、名前を切らずに横に送れるようにする
    column.append(&gtk::ScrolledWindow::builder().child(&tabs).vscrollbar_policy(gtk::PolicyType::Never).propagate_natural_height(true).build());
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
    // 確かめ用: WAVE_DEMO_SEARCH_TAB にある種類（lyrics など）を開く
    if let Ok(kind) = std::env::var("WAVE_DEMO_SEARCH_TAB") {
        tabs.set_active_name(Some(&kind));
    }
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
                let (repo, lyrics) = (app.repo.clone(), app.lyrics.clone());
                let (q, k, t) = (query.clone(), kind.clone(), token.clone());
                let result = runtime::run(async move {
                    let t = t.as_deref();
                    match k.as_str() {
                        "albums" => repo.search_albums(&q, t).await.map(|p| (SearchRows::Albums(p.items), p.continuation)),
                        "artists" => repo.search_artists(&q, t).await.map(|p| (SearchRows::Artists(p.items), p.continuation)),
                        "live" => repo.search_live(&q, t).await.map(|p| (SearchRows::Songs(p.songs), p.continuation)),
                        "podcasts" => repo.search_podcasts(&q, t).await.map(|p| (SearchRows::Podcasts(p.items), p.continuation)),
                        "episodes" => repo.search_episodes(&q, t).await.map(|p| (SearchRows::Episodes(p.items), p.continuation)),
                        // 歌詞の一節から（検索の上位の曲の歌詞と照らし合わせる。続きは無い）
                        "lyrics" => {
                            let lyrics = lyrics.clone();
                            wave_core::lyrics::search::search(&q, |q| async move { repo.search_top_songs(&q).await }, |song| {
                                let lyrics = lyrics.clone();
                                async move { lyrics.lyrics(&song).await }
                            })
                            .await
                            .map(|hits| (SearchRows::Lyrics(hits), None))
                        }
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
    Podcasts(Vec<wave_core::music::podcast::PodcastSummary>),
    Episodes(Vec<wave_core::music::podcast::PodcastEpisode>),
    Lyrics(Vec<wave_core::lyrics::search::LyricsHit>),
}

impl SearchRows {
    fn is_empty(&self) -> bool {
        match self {
            SearchRows::Songs(v) => v.is_empty(),
            SearchRows::Albums(v) => v.is_empty(),
            SearchRows::Artists(v) => v.is_empty(),
            SearchRows::Podcasts(v) => v.is_empty(),
            SearchRows::Episodes(v) => v.is_empty(),
            SearchRows::Lyrics(v) => v.is_empty(),
        }
    }

    fn widget(self, app: &Rc<App>) -> gtk::Widget {
        match self {
            SearchRows::Songs(songs) => song_list(app, songs).upcast(),
            SearchRows::Podcasts(shows) => super::podcasts::search_shows(app, shows),
            SearchRows::Episodes(episodes) => super::podcasts::search_episodes(app, episodes),
            SearchRows::Lyrics(hits) => {
                // 合った歌詞の行を引用して添える（大体合っているときは先頭に「≈ 」。Windows・Android と同じ）
                let quotes: Vec<Option<String>> = hits.iter().map(|h| h.matched.as_ref().map(|m| format!("{}\u{201C}{}\u{201D}", if m.exact { "" } else { "≈ " }, m.line))).collect();
                let list = song_list(app, hits.into_iter().map(|h| h.song).collect());
                for (i, quote) in quotes.into_iter().enumerate() {
                    if let (Some(quote), Some(row)) = (quote, list.row_at_index(i as i32).and_downcast::<adw::ActionRow>()) {
                        row.set_subtitle_lines(2);
                        row.set_subtitle(&format!("{}\n{}", row.subtitle().unwrap_or_default(), gtk::glib::markup_escape_text(&quote)));
                    }
                }
                list.upcast()
            }
            SearchRows::Albums(albums) => {
                let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).build();
                for album in albums {
                    let row = summary_row(&album.title, album.subtitle.as_deref(), album.thumbnail_url.as_deref(), false);
                    let (app, id, title) = (app.clone(), album.browse_id, album.title);
                    let subtitle = album.subtitle.clone();
                    let thumbnail = album.thumbnail_url.clone();
                    row.connect_activated(move |_| {
                        app.remember_search(crate::settings::SearchHistoryEntry::album(&id, &title, subtitle.as_deref(), thumbnail.as_deref()));
                        app.push(self::album(&app, &id, &title));
                    });
                    list.append(&row);
                }
                list.upcast()
            }
            SearchRows::Artists(artists) => {
                let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["boxed-list"]).build();
                for artist in artists {
                    let row = summary_row(&artist.name, artist.subtitle.as_deref(), artist.thumbnail_url.as_deref(), true);
                    let (app, id, name) = (app.clone(), artist.browse_id, artist.name);
                    let thumbnail = artist.thumbnail_url.clone();
                    row.connect_activated(move |_| {
                        app.remember_search(crate::settings::SearchHistoryEntry::artist(&id, &name, thumbnail.as_deref()));
                        app.push(self::artist(&app, &id, &name));
                    });
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

/// プレイリストのページ（頭にジャケット・名前・再生のボタン、中の検索・並べ替え・種類の絞り込み、下に曲）。
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
                // 最後のページまで読む（丸ごと取る・絞り込む・並べ替えるのは全部の曲）
                let all = full_playlist_entries(&app, &id_for_list, &page).await;
                let songs: Vec<Song> = all.iter().map(|(s, _)| s.clone()).collect();
                let view = Rc::new(std::cell::RefCell::new(songs.clone()));
                let source = (id_for_list != wave_core::music::repository::LIKED_SONGS).then(|| id_for_list.clone());
                column.append(&page_header_view(&app, page.thumbnail_url.as_deref(), page.title.as_deref().unwrap_or(""), page.subtitle.as_deref(), false, view.clone(), songs, Some(collection), source));
                column.append(&playlist_tools(&app, &id_for_list, all, page.editable, view));
                scrolling_column(&column)
            }
            // 通信できなくても、丸ごと取ったプレイリストは目録から出す
            Err(e) => offline_collection(&app, &id_for_list).unwrap_or_else(|| failure(&format!("プレイリストを読めませんでした（{e}）"))),
        };
        target.set_child(Some(&content));
    });
    page(title, "playlist", &bin)
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SortKey {
    Custom,
    Added,
    Published,
    Title,
    Artist,
    Album,
    Duration,
}

const SORT_LABELS: [(SortKey, &str); 7] = [
    (SortKey::Custom, "カスタム順"),
    (SortKey::Added, "追加した日"),
    (SortKey::Published, "リリース日"),
    (SortKey::Title, "タイトル"),
    (SortKey::Artist, "アーティスト"),
    (SortKey::Album, "アルバム"),
    (SortKey::Duration, "再生時間"),
];

/// プレイリストの中の検索（曲名・アーティスト・アルバム）・並べ替え（追加した日とリリース日は YouTube Music に並べてもらう）・
/// 種類（曲・MV・その他の動画）の絞り込みと、その結果の曲の一覧。view には今出している曲を入れる（頭の再生のボタンが流す）。
fn playlist_tools(app: &Rc<App>, playlist_id: &str, all: Vec<(Song, Option<String>)>, editable: bool, view: Rc<std::cell::RefCell<Vec<Song>>>) -> gtk::Widget {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    let bar = gtk::Box::builder().spacing(8).build();
    let filter = gtk::SearchEntry::builder().placeholder_text("プレイリストの中を検索").hexpand(true).build();
    let kinds = adw::ToggleGroup::builder().homogeneous(false).css_classes(["round"]).build();
    for (name, label) in [("all", "すべて"), ("MUSIC_VIDEO_TYPE_ATV", "曲"), ("MUSIC_VIDEO_TYPE_OMV", "MV"), ("MUSIC_VIDEO_TYPE_UGC", "その他の動画")] {
        kinds.add(adw::Toggle::builder().label(label).name(name).build());
    }
    kinds.set_active_name(Some("all"));
    let sort_label = gtk::Label::new(Some("カスタム順"));
    let sort_list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).css_classes(["navigation-sidebar"]).build();
    let sort_popover = gtk::Popover::builder().child(&sort_list).build();
    let sort = gtk::MenuButton::builder().child(&sort_label).popover(&sort_popover).tooltip_text("並べ替え").css_classes(["flat"]).build();
    bar.append(&filter);
    bar.append(&kinds);
    bar.append(&sort);
    column.append(&bar);
    let list_holder = adw::Bin::new();
    column.append(&list_holder);

    struct State {
        all: Vec<(Song, Option<String>)>,
        /// YouTube Music に並べてもらった曲（追加した日・リリース日のとき）。
        server: Option<Vec<(Song, Option<String>)>>,
        sort: SortKey,
        descending: bool,
    }
    let state = Rc::new(std::cell::RefCell::new(State { all, server: None, sort: SortKey::Custom, descending: false }));
    let render: Rc<dyn Fn()> = {
        let (app, state, filter, kinds, list_holder, view, playlist_id, sort_label) =
            (app.clone(), state.clone(), filter.clone(), kinds.clone(), list_holder.clone(), view.clone(), playlist_id.to_string(), sort_label.clone());
        Rc::new(move || {
            let st = state.borrow();
            let query = filter.text().trim().to_lowercase();
            let kind = kinds.active_name().filter(|k| k != "all").map(|k| k.to_string());
            let base = st.server.as_ref().unwrap_or(&st.all);
            let mut shown: Vec<(Song, Option<String>)> = base
                .iter()
                .filter(|(s, _)| {
                    query.is_empty()
                        || s.title.to_lowercase().contains(&query)
                        || s.artists_text().to_lowercase().contains(&query)
                        || s.album.as_ref().is_some_and(|a| a.title.to_lowercase().contains(&query))
                })
                .filter(|(s, _)| kind.is_none() || s.kind == kind)
                .cloned()
                .collect();
            match st.sort {
                SortKey::Title => shown.sort_by(|a, b| a.0.title.to_lowercase().cmp(&b.0.title.to_lowercase())),
                SortKey::Artist => shown.sort_by(|a, b| a.0.artists_text().to_lowercase().cmp(&b.0.artists_text().to_lowercase())),
                SortKey::Album => shown.sort_by(|a, b| {
                    let album = |s: &Song| s.album.as_ref().map(|x| x.title.to_lowercase()).unwrap_or_default();
                    album(&a.0).cmp(&album(&b.0))
                }),
                SortKey::Duration => shown.sort_by_key(|(s, _)| s.duration_seconds.unwrap_or(0)),
                _ => {}
            }
            // 並べ替えの向き（YouTube Music に並べてもらったものは向きも頼んである）
            if st.descending && !matches!(st.sort, SortKey::Added | SortKey::Published) {
                shown.reverse();
            }
            let label = SORT_LABELS.iter().find(|(k, _)| *k == st.sort).map(|(_, l)| *l).unwrap_or("");
            sort_label.set_label(&if st.sort == SortKey::Custom { label.to_string() } else { format!("{label} {}", if st.descending { "↓" } else { "↑" }) });
            *view.borrow_mut() = shown.iter().map(|(s, _)| s.clone()).collect();
            // 自分のプレイリストなら、曲のメニューから外せる
            let removable = editable.then(|| (playlist_id.clone(), shown.iter().map(|(_, id)| id.clone()).collect()));
            let songs: Vec<Song> = shown.into_iter().map(|(s, _)| s).collect();
            if songs.is_empty() {
                list_holder.set_child(Some(&gtk::Label::builder().label("当てはまる曲はありません").margin_top(24).css_classes(["dim-label"]).build()));
            } else {
                list_holder.set_child(Some(&song_list_in(&app, songs, removable)));
            }
        })
    };
    render();
    {
        let render = render.clone();
        filter.connect_search_changed(move |_| render());
    }
    {
        let render = render.clone();
        kinds.connect_active_name_notify(move |_| render());
    }
    // 並べ替えのメニュー: 同じものをもう一度押すと向きが変わる。追加した日・リリース日は YouTube Music に並べて読み直してもらう
    for (key, label) in SORT_LABELS {
        let row = adw::ActionRow::builder().title(label).activatable(true).build();
        let (app, state, render, popover, playlist_id) = (app.clone(), state.clone(), render.clone(), sort_popover.clone(), playlist_id.to_string());
        row.connect_activated(move |_| {
            popover.popdown();
            {
                let mut st = state.borrow_mut();
                if st.sort == key && key != SortKey::Custom {
                    st.descending = !st.descending;
                } else {
                    st.sort = key;
                    // 追加した日は新しい順から
                    st.descending = key == SortKey::Added;
                }
                st.server = None;
            }
            let order = match key {
                SortKey::Added => Some(wave_core::music::repository::ORDER_ADDED),
                SortKey::Published => Some(wave_core::music::repository::ORDER_PUBLISHED),
                _ => None,
            };
            let Some(order) = order else {
                render();
                return;
            };
            let descending = state.borrow().descending;
            let (app, state, render, playlist_id) = (app.clone(), state.clone(), render.clone(), playlist_id.clone());
            gtk::glib::spawn_future_local(async move {
                let params = wave_core::music::repository::order_params(order, descending);
                let repo = app.repo.clone();
                let (id, p) = (playlist_id.clone(), params.clone());
                let Ok(first) = runtime::run(async move { repo.playlist_in_order(&id, None, Some(&p)).await }).await else {
                    app.toast("並べ替えて読めませんでした");
                    return;
                };
                let entries = full_playlist_entries(&app, &playlist_id, &first).await;
                state.borrow_mut().server = Some(entries);
                render();
            });
        });
        sort_list.append(&row);
    }
    column.upcast()
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
    let view = Rc::new(std::cell::RefCell::new(songs.clone()));
    page_header_view(app, thumbnail, title, subtitle, round, view, songs, collection, None)
}

/// ページの頭。再生・シャッフル・次に再生・キューに追加は view（絞り込んだときは絞った曲）を流す。丸ごと取るのは all。
#[allow(clippy::too_many_arguments)]
fn page_header_view(
    app: &Rc<App>,
    thumbnail: Option<&str>,
    title: &str,
    subtitle: Option<&str>,
    round: bool,
    view: Rc<std::cell::RefCell<Vec<Song>>>,
    all: Vec<Song>,
    collection: Option<Collection>,
    source_id: Option<String>,
) -> gtk::Widget {
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
    if !all.is_empty() {
        let buttons = gtk::Box::builder().spacing(12).build();
        let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").halign(gtk::Align::Start).css_classes(["circular", "suggested-action", "play-large"]).build();
        let shuffle = gtk::Button::builder().icon_name("media-playlist-shuffle-symbolic").tooltip_text("シャッフル再生").valign(gtk::Align::Center).css_classes(["circular", "flat"]).build();
        let next = gtk::Button::builder().icon_name("format-indent-more-symbolic").tooltip_text("次に再生").valign(gtk::Align::Center).css_classes(["circular", "flat"]).build();
        let queue = gtk::Button::builder().icon_name("list-add-symbolic").tooltip_text("キューに追加").valign(gtk::Align::Center).css_classes(["circular", "flat"]).build();
        {
            let (app, view, source) = (app.clone(), view.clone(), source_id.clone());
            play.connect_clicked(move |_| app.playback.play_list_from(view.borrow().clone(), 0, source.clone()));
        }
        {
            let (app, view, source) = (app.clone(), view.clone(), source_id.clone());
            shuffle.connect_clicked(move |_| app.playback.play_shuffled(view.borrow().clone(), source.clone()));
        }
        {
            // 次に再生は、並びが保たれるよう後ろから今の曲の直後へ入れる
            let (app, view) = (app.clone(), view.clone());
            next.connect_clicked(move |_| {
                let songs = view.borrow().clone();
                let count = songs.len();
                for song in songs.into_iter().rev() {
                    app.playback.play_next(song);
                }
                app.toast(&format!("{count} 曲を次に再生します"));
            });
        }
        {
            let (app, view) = (app.clone(), view.clone());
            queue.connect_clicked(move |_| {
                let songs = view.borrow().clone();
                let count = songs.len();
                for song in songs {
                    app.playback.add_to_queue(song);
                }
                app.toast(&format!("{count} 曲をキューに追加しました"));
            });
        }
        buttons.append(&play);
        buttons.append(&shuffle);
        buttons.append(&next);
        buttons.append(&queue);
        if let Some(collection) = collection {
            buttons.append(&download_button(app, collection, title, thumbnail, all));
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
    let artist_id = id.clone();
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
                // 登録したアーティストなら、見張りで見つけた最近の新作（Windows と同じ）
                let fresh: Vec<HomeItem> = app
                    .releases
                    .recent()
                    .into_iter()
                    .filter(|r| r.artist_id == artist_id)
                    .filter_map(|r| Some(HomeItem::Album(wave_core::music::models::AlbumSummary { browse_id: r.album_browse_id?, title: r.title, subtitle: None, thumbnail_url: r.thumbnail_url })))
                    .collect();
                if !fresh.is_empty() {
                    column.append(&shelf_widget(&app, "新作", None, fresh));
                }
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
/// プレイリストの曲を、外すときの項目の ID と一緒に最後のページまで読む。
async fn full_playlist_entries(app: &Rc<App>, id: &str, first: &wave_core::music::models::PlaylistPage) -> Vec<(Song, Option<String>)> {
    let (repo, id) = (app.repo.clone(), id.to_string());
    let mut entries: Vec<(Song, Option<String>)> =
        first.songs.iter().cloned().zip(first.set_video_ids.iter().cloned().chain(std::iter::repeat(None))).collect();
    let mut token = first.continuation.clone();
    runtime::run(async move {
        for _ in 0..50 {
            let Some(t) = token.clone() else { break };
            let Ok(next) = repo.playlist(&id, Some(&t)).await else { break };
            entries.extend(next.songs.into_iter().zip(next.set_video_ids.into_iter().chain(std::iter::repeat(None))));
            token = next.continuation.filter(|n| *n != t);
        }
        entries
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
