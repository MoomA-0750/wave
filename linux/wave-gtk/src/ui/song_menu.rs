//! 曲のメニュー（行の右端の「…」と右クリック。Mac・Windows と同じ並び）: 次に再生・キューに追加・ダウンロード・プレイリストに追加（新しいプレイリストも）・
//! 高く評価した曲に追加（評価済みなら削除）・
//! このプレイリストから削除・曲のラジオ・アルバムへ・アーティストへ・リンクをコピー。
//! RSS のエピソードは YouTube の曲ではないので、プレイリストに追加・高く評価・ラジオ・共有を出さない（ダウンロードは出す。Mac と同じ）。

use std::rc::Rc;

use adw::prelude::*;
use gtk::gio;
use wave_core::music::models::{AddToPlaylistResult, Song};

use super::pages;
use super::shell::App;
use crate::runtime;

/// 「このプレイリストから削除」に要るもの（プレイリストの ID と、曲の項目の ID と、外したあとに呼ぶもの）。
pub struct Removal {
    pub playlist_id: String,
    pub set_video_id: String,
    pub on_removed: Rc<dyn Fn()>,
}

/// 行の右端に置く「…」のボタン。right_click_target に渡した部品では右クリックでも同じメニューを開く。
pub fn button(app: &Rc<App>, song: &Song, removal: Option<Removal>, right_click_target: Option<&gtk::Widget>) -> gtk::MenuButton {
    let button = gtk::MenuButton::builder().icon_name("view-more-symbolic").tooltip_text("その他").valign(gtk::Align::Center).css_classes(["flat", "circular"]).build();
    // メニューは開くときに作る（自分のプレイリストの一覧は後から届くので、開いた時点のものを並べる）
    let (app, song) = (app.clone(), song.clone());
    let removal = removal.map(Rc::new);
    let weak = button.downgrade();
    button.set_create_popup_func(move |button| {
        let (menu, actions) = build(&app, &song, removal.clone());
        button.insert_action_group("song", Some(&actions));
        button.set_menu_model(Some(&menu));
    });
    if let Some(target) = right_click_target {
        let click = gtk::GestureClick::builder().button(3).build();
        click.connect_pressed(move |gesture, _, _, _| {
            gesture.set_state(gtk::EventSequenceState::Claimed);
            if let Some(button) = weak.upgrade() {
                button.popup();
            }
        });
        target.add_controller(click);
    }
    button
}

/// 部品の上の (x, y) に曲のメニューを開く（カードの右クリック）。
pub fn popup(app: &Rc<App>, song: &Song, anchor: &gtk::Widget, x: f64, y: f64) {
    let (menu, actions) = build(app, song, None);
    let popover = gtk::PopoverMenu::from_model(Some(&menu));
    popover.insert_action_group("song", Some(&actions));
    popover.set_parent(anchor);
    popover.set_has_arrow(false);
    popover.set_pointing_to(Some(&gtk::gdk::Rectangle::new(x as i32, y as i32, 1, 1)));
    popover.connect_closed(|p| {
        let p = p.clone();
        // メニューの項目の動きが終わってから外す
        gtk::glib::idle_add_local_once(move || p.unparent());
    });
    popover.popup();
}

fn build(app: &Rc<App>, song: &Song, removal: Option<Rc<Removal>>) -> (gio::Menu, gio::SimpleActionGroup) {
    let actions = gio::SimpleActionGroup::new();
    let menu = gio::Menu::new();
    let add = |name: &str, f: Box<dyn Fn(Option<&gtk::glib::Variant>)>, with_target: bool| {
        let action = gio::SimpleAction::new(name, with_target.then_some(gtk::glib::VariantTy::STRING));
        action.connect_activate(move |_, target| f(target));
        actions.add_action(&action);
    };

    let queue_section = gio::Menu::new();
    queue_section.append(Some("次に再生"), Some("song.play-next"));
    queue_section.append(Some("キューに追加"), Some("song.add-queue"));
    // RSS のエピソードは YouTube の曲ではない（プレイリスト・評価・ラジオ・共有のリンクが無い。ADR-0012）
    let external = wave_core::music::feed::is_external(&song.video_id);
    // ダウンロード（取っていれば「ダウンロードを削除」。ライブ配信は取らない。RSS のエピソードは取れる）
    if !song.is_live {
        let state = app.downloads.state(&song.video_id);
        let single = app.downloads.manifest().is_single(&song.video_id);
        if single || state == crate::downloads::SongState::Done && !app.downloads.manifest().is_wanted(&song.video_id) {
            queue_section.append(Some("ダウンロードを削除"), Some("song.undownload"));
        } else if state == crate::downloads::SongState::None {
            queue_section.append(Some("ダウンロード"), Some("song.download"));
        }
        let (app_d, song_d) = (app.clone(), song.clone());
        add("download", Box::new(move |_| {
            app_d.downloads.download_song(&song_d);
            app_d.toast("ダウンロードに追加しました");
        }), false);
        let (app_u, id) = (app.clone(), song.video_id.clone());
        add("undownload", Box::new(move |_| {
            app_u.downloads.remove_song(&id);
            app_u.toast("ダウンロードを削除しました");
        }), false);
    }
    menu.append_section(None, &queue_section);
    {
        let (app, song) = (app.clone(), song.clone());
        add("play-next", Box::new(move |_| {
            // ほかの端末を映している間は、その端末のキューへの追加として知らせる（連携の側が出す）
            let remote = app.connect.mirror().is_some();
            app.playback.play_next(song.clone());
            if !remote {
                app.toast("次に再生します");
            }
        }), false);
    }
    {
        let (app, song) = (app.clone(), song.clone());
        add("add-queue", Box::new(move |_| {
            // ほかの端末を映している間は、その端末のキューへの追加として知らせる（連携の側が出す）
            let remote = app.connect.mirror().is_some();
            app.playback.add_to_queue(song.clone());
            if !remote {
                app.toast("キューに追加しました");
            }
        }), false);
    }

    // ログインしていなければ出さない（押せないものは出さない。Mac・Windows と同じ）
    if app.repo.client().is_logged_in() && (!external || removal.is_some()) {
        let playlist_section = gio::Menu::new();
        let submenu = gio::Menu::new();
        submenu.append(Some("新しいプレイリスト…"), Some("song.new-playlist"));
        let editable = app.editable_playlists.borrow().clone();
        if !editable.is_empty() {
            let list = gio::Menu::new();
            for p in &editable {
                let item = gio::MenuItem::new(Some(&p.title), None);
                item.set_action_and_target_value(Some("song.add-to"), Some(&p.playlist_id.to_variant()));
                list.append_item(&item);
            }
            submenu.append_section(None, &list);
        }
        if !external {
            playlist_section.append_submenu(Some("プレイリストに追加"), &submenu);
        }
        // 高く評価した曲に足す・外す。高く評価した曲の一覧を読んであればそれで、無ければ開いた時点の評価を読んでから外すほうに替える
        if !external {
            match super::track::liked_known(&song.video_id) {
                Some(true) => playlist_section.append(Some("高く評価した曲から削除"), Some("song.unlike")),
                Some(false) => playlist_section.append(Some("高く評価した曲に追加"), Some("song.like")),
                None => {
                    playlist_section.append(Some("高く評価した曲に追加"), Some("song.like"));
                    let (repo, id, section) = (app.repo.clone(), song.video_id.clone(), playlist_section.clone());
                    gtk::glib::spawn_future_local(async move {
                        let status = runtime::run(async move { repo.like_status(&id).await }).await;
                        if let Ok(Some(wave_core::music::models::LikeStatus::Like)) = status {
                            for i in 0..section.n_items() {
                                if section.item_attribute_value(i, "action", None).and_then(|v| v.get::<String>()).as_deref() == Some("song.like") {
                                    section.remove(i);
                                    section.insert(i, Some("高く評価した曲から削除"), Some("song.unlike"));
                                    break;
                                }
                            }
                        }
                    });
                }
            }
            for (name, liked) in [("like", true), ("unlike", false)] {
                let (app, song) = (app.clone(), song.clone());
                add(name, Box::new(move |_| {
                    let (app, song) = (app.clone(), song.clone());
                    gtk::glib::spawn_future_local(async move {
                        let (repo, id) = (app.repo.clone(), song.video_id.clone());
                        let ok = runtime::run(async move { repo.set_liked(&id, liked).await }).await.is_ok();
                        if ok {
                            super::track::set_liked(&song.video_id, liked);
                        }
                        app.toast(match (ok, liked) {
                            (true, true) => "高く評価した曲に追加しました",
                            (true, false) => "高く評価した曲から削除しました",
                            (false, _) => "評価を変えられませんでした",
                        });
                    });
                }), false);
            }
        }
        if removal.is_some() {
            playlist_section.append(Some("このプレイリストから削除"), Some("song.remove"));
        }
        menu.append_section(None, &playlist_section);
        {
            let (app, song, editable) = (app.clone(), song.clone(), editable.clone());
            add("add-to", Box::new(move |target| {
                let Some(id) = target.and_then(|t| t.get::<String>()) else { return };
                let title = editable.iter().find(|p| p.playlist_id == id).map(|p| p.title.clone()).unwrap_or_default();
                add_to(&app, &song, &id, &title, false);
            }), true);
        }
        {
            let (app, song) = (app.clone(), song.clone());
            add("new-playlist", Box::new(move |_| new_playlist(&app, &song)), false);
        }
        if let Some(removal) = removal {
            let (app, song) = (app.clone(), song.clone());
            add("remove", Box::new(move |_| {
                let (app, song, removal) = (app.clone(), song.clone(), removal.clone());
                gtk::glib::spawn_future_local(async move {
                    let (repo, playlist, set_id, video) = (app.repo.clone(), removal.playlist_id.clone(), removal.set_video_id.clone(), song.video_id.clone());
                    if runtime::run(async move { repo.remove_from_playlist(&playlist, &video, &set_id).await }).await {
                        app.toast("プレイリストから削除しました");
                        (removal.on_removed)();
                    } else {
                        app.toast("プレイリストから削除できませんでした");
                    }
                });
            }), false);
        }
    }

    let go_section = gio::Menu::new();
    if !external {
        go_section.append(Some("曲のラジオを再生"), Some("song.radio"));
        let (app, song) = (app.clone(), song.clone());
        add("radio", Box::new(move |_| app.playback.play_list(vec![song.clone()], 0)), false);
    }
    if let Some((album_id, album_title)) = song.album.as_ref().and_then(|a| Some((a.browse_id.clone()?, a.title.clone()))) {
        go_section.append(Some("アルバムへ移動"), Some("song.album"));
        let app = app.clone();
        add("album", Box::new(move |_| app.push(pages::album(&app, &album_id, &album_title))), false);
    }
    let artists: Vec<(String, String)> = song.artists.iter().filter_map(|a| Some((a.browse_id.clone()?, a.name.clone()))).collect();
    for (id, name) in &artists {
        let label = if artists.len() > 1 { format!("アーティストへ移動: {name}") } else { "アーティストへ移動".to_string() };
        let item = gio::MenuItem::new(Some(&label), None);
        item.set_action_and_target_value(Some("song.artist"), Some(&id.to_variant()));
        go_section.append_item(&item);
    }
    if !artists.is_empty() {
        let app = app.clone();
        add("artist", Box::new(move |target| {
            let Some(id) = target.and_then(|t| t.get::<String>()) else { return };
            let name = artists.iter().find(|(a, _)| *a == id).map(|(_, n)| n.clone()).unwrap_or_default();
            app.push(pages::artist(&app, &id, &name));
        }), true);
    }
    menu.append_section(None, &go_section);

    if !external {
        let share = gio::Menu::new();
        share.append(Some("曲のリンクをコピー"), Some("song.copy-link"));
        menu.append_section(None, &share);
        let (app, link) = (app.clone(), format!("https://music.youtube.com/watch?v={}", song.video_id));
        add("copy-link", Box::new(move |_| {
            if let Some(display) = gtk::gdk::Display::default() {
                display.clipboard().set_text(&link);
            }
            app.toast("リンクをコピーしました");
        }), false);
    }
    (menu, actions)
}

fn add_to(app: &Rc<App>, song: &Song, playlist_id: &str, title: &str, known_empty: bool) {
    let (app, song, id, title) = (app.clone(), song.clone(), playlist_id.to_string(), title.to_string());
    gtk::glib::spawn_future_local(async move {
        let (repo, video) = (app.repo.clone(), song.video_id.clone());
        let playlist = id.clone();
        let result = runtime::run(async move { repo.add_to_playlist(&playlist, &video, known_empty).await }).await;
        app.toast(&match result {
            AddToPlaylistResult::Added => format!("「{title}」に追加しました"),
            AddToPlaylistResult::AlreadyInPlaylist => format!("「{title}」にはもう入っています"),
            AddToPlaylistResult::NotChecked => "プレイリストを確かめられなかったので、追加しませんでした".to_string(),
            AddToPlaylistResult::Failed => "追加できませんでした".to_string(),
        });
    });
}

/// 名前を聞いて非公開のプレイリストを作り、その曲を入れる。
fn new_playlist(app: &Rc<App>, song: &Song) {
    create_playlist(app, Some(song.clone()), None);
}

/// プレイリストを作る（名前を聞く）。song があれば作ったプレイリストに入れる。欄は曲から作るとき「マイプレイリスト」入りで、空なら作れない（Mac と同じ）。
/// 作れたら on_created を呼ぶ（マイライブラリを読み直す）。
pub fn create_playlist(app: &Rc<App>, song: Option<Song>, on_created: Option<Rc<dyn Fn()>>) {
    let entry = gtk::Entry::builder().placeholder_text("プレイリスト名").text(if song.is_some() { "マイプレイリスト" } else { "" }).activates_default(true).build();
    let dialog = adw::AlertDialog::builder().heading("プレイリストを作成").extra_child(&entry).default_response("create").close_response("cancel").build();
    dialog.add_response("cancel", "キャンセル");
    dialog.add_response("create", "作成");
    dialog.set_response_appearance("create", adw::ResponseAppearance::Suggested);
    let sync = {
        let dialog = dialog.clone();
        move |entry: &gtk::Entry| dialog.set_response_enabled("create", !entry.text().trim().is_empty())
    };
    sync(&entry);
    entry.connect_changed(sync);
    let (app, entry_c) = (app.clone(), entry.clone());
    let parent = app.window();
    dialog.connect_response(None, move |_, response| {
        let title = entry_c.text().trim().to_string();
        if response != "create" || title.is_empty() {
            return;
        }
        let (app, song, on_created) = (app.clone(), song.clone(), on_created.clone());
        gtk::glib::spawn_future_local(async move {
            let repo = app.repo.clone();
            let name = title.clone();
            match runtime::run(async move { repo.create_playlist(&name).await }).await {
                Ok(id) => {
                    match song {
                        // 作ったばかりで空なので、重複を確かめずに入れる
                        Some(song) => add_to(&app, &song, &id, &title, true),
                        None => app.toast(&format!("「{title}」を作成しました")),
                    }
                    if let Some(f) = on_created {
                        f();
                    }
                }
                Err(_) => app.toast("プレイリストを作れませんでした"),
            }
        });
    });
    dialog.present(parent.as_ref());
    entry.grab_focus();
}

/// 評価済みの曲の ✓ ボタンから開く小さなメニュー（Mac の「プレイリストに追加」の画面に当たる）:
/// プレイリストに追加（新しいプレイリストも）と、高く評価した曲から削除。外したら on_unlike を呼ぶ。
pub fn popup_saved(app: &Rc<App>, song: &Song, anchor: &impl IsA<gtk::Widget>, on_unlike: impl Fn() + 'static) {
    let actions = gio::SimpleActionGroup::new();
    let menu = gio::Menu::new();
    let add_section = gio::Menu::new();
    add_section.append(Some("新しいプレイリスト…"), Some("saved.new-playlist"));
    let editable = app.editable_playlists.borrow().clone();
    for p in &editable {
        let item = gio::MenuItem::new(Some(&p.title), None);
        item.set_action_and_target_value(Some("saved.add-to"), Some(&p.playlist_id.to_variant()));
        add_section.append_item(&item);
    }
    menu.append_submenu(Some("プレイリストに追加"), &add_section);
    menu.append(Some("高く評価した曲から削除"), Some("saved.unlike"));
    {
        let (app, song) = (app.clone(), song.clone());
        let action = gio::SimpleAction::new("new-playlist", None);
        action.connect_activate(move |_, _| new_playlist(&app, &song));
        actions.add_action(&action);
    }
    {
        let (app, song) = (app.clone(), song.clone());
        let action = gio::SimpleAction::new("add-to", Some(gtk::glib::VariantTy::STRING));
        action.connect_activate(move |_, target| {
            let Some(id) = target.and_then(|t| t.get::<String>()) else { return };
            let title = editable.iter().find(|p| p.playlist_id == id).map(|p| p.title.clone()).unwrap_or_default();
            add_to(&app, &song, &id, &title, false);
        });
        actions.add_action(&action);
    }
    {
        let (app, song) = (app.clone(), song.clone());
        let on_unlike = Rc::new(on_unlike);
        let action = gio::SimpleAction::new("unlike", None);
        action.connect_activate(move |_, _| {
            let (app, id, on_unlike) = (app.clone(), song.video_id.clone(), on_unlike.clone());
            gtk::glib::spawn_future_local(async move {
                let repo = app.repo.clone();
                let video = id.clone();
                match runtime::run(async move { repo.set_liked(&id, false).await }).await {
                    Ok(_) => {
                        super::track::set_liked(&video, false);
                        on_unlike();
                        app.toast("高く評価した曲から削除しました");
                    }
                    Err(_) => app.toast("高く評価した曲から削除できませんでした"),
                }
            });
        });
        actions.add_action(&action);
    }
    let popover = gtk::PopoverMenu::from_model(Some(&menu));
    popover.insert_action_group("saved", Some(&actions));
    popover.set_parent(anchor);
    popover.connect_closed(|p| {
        let p = p.clone();
        // メニューの項目の動きが終わってから外す
        gtk::glib::idle_add_local_once(move || p.unparent());
    });
    popover.popup();
}

/// キューの行の右クリックのメニュー（Mac のキューの行と同じ）: 曲のメニューの頭に「キューから削除」を足す（今の曲には出さない）。
/// x・y は anchor の中の押した位置。
pub fn popup_queue(app: &Rc<App>, song: &Song, item_id: Option<String>, anchor: &impl IsA<gtk::Widget>, x: f64, y: f64) {
    let (menu, actions) = build(app, song, None);
    if let Some(item_id) = item_id {
        let section = gio::Menu::new();
        section.append(Some("キューから削除"), Some("song.remove-queue"));
        menu.prepend_section(None, &section);
        let action = gio::SimpleAction::new("remove-queue", None);
        let app = app.clone();
        action.connect_activate(move |_, _| {
            app.playback.remove_items(std::slice::from_ref(&item_id));
        });
        actions.add_action(&action);
    }
    let popover = gtk::PopoverMenu::from_model(Some(&menu));
    popover.insert_action_group("song", Some(&actions));
    popover.set_parent(anchor);
    popover.set_has_arrow(false);
    popover.set_pointing_to(Some(&gtk::gdk::Rectangle::new(x as i32, y as i32, 1, 1)));
    popover.connect_closed(|p| {
        let p = p.clone();
        // メニューの項目の動きが終わってから外す
        gtk::glib::idle_add_local_once(move || p.unparent());
    });
    popover.popup();
}
