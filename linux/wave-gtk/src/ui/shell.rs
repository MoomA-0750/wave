//! 窓の並び（Mac・Windows と同じ）: 上の帯に戻る・ホーム・検索の欄、左にマイライブラリ、中央にページ、
//! 右に再生中の欄（しまえる）、下に再生の操作の帯。部品は libadwaita のもの。

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use wave_core::music::repository::{LIKED_SONGS, MusicRepository};

use super::{images, pages};
use crate::playback::Playback;
use crate::runtime;

pub struct App {
    pub repo: MusicRepository,
    pub lyrics: wave_core::lyrics::service::LyricsService,
    pub playback: Rc<Playback>,
    nav: adw::NavigationView,
    toasts: adw::ToastOverlay,
    /// 曲に重ねる MV の流し手（gtk4paintablesink が無ければ None）。
    pub video: Option<crate::video::VideoPlayer>,
    /// ダウンロード（取った曲・丸ごと取ったもの）。
    pub downloads: Rc<crate::downloads::Downloads>,
    /// 端末の連携。
    pub connect: Rc<crate::connect::Connect>,
    /// 映像を窓いっぱいに出す層（gtk4paintablesink が無ければ None）。
    video_layer: std::cell::RefCell<Option<gtk::Widget>>,
    /// Claude との会話。
    pub ai: Rc<crate::ai_chat::AiChat>,
    /// 上の帯の Claude のボタン（右の欄を Claude にする。下の帯を作るときにつなぐ）。
    claude_toggle: std::cell::RefCell<Option<gtk::ToggleButton>>,
    /// 自分で編集できるプレイリスト（曲のメニューの「プレイリストに追加」に並べる）。
    pub editable_playlists: std::cell::RefCell<Vec<wave_core::music::models::PlaylistSummary>>,
}

impl App {
    /// 窓（ダイアログの親）。
    pub fn window(&self) -> Option<gtk::Window> {
        self.nav.root().and_downcast::<gtk::Window>()
    }

    /// 画面の下に短い知らせを出す。
    pub fn toast(&self, text: &str) {
        self.toasts.add_toast(adw::Toast::new(text));
    }

    /// ページを積む。同じタグのページが下に残っていると AdwNavigationView が積むのを断るので、
    /// そのときは番号を付けて重ならないようにする（アルバム → アーティスト → 別のアルバム、設定を2回開く、など）。
    pub fn push(&self, page: adw::NavigationPage) {
        if let Some(tag) = page.tag() {
            let mut n = 2;
            while self.nav.find_page(&page_tag(&tag, n - 1)).is_some() {
                page.set_tag(Some(&page_tag(&tag, n)));
                n += 1;
            }
        }
        self.nav.push(&page);
    }

    /// 今いちばん上のページの種類（タグの番号を除いた名前）。
    pub fn visible_kind(&self) -> Option<String> {
        visible_kind(&self.nav)
    }
}

/// 右の欄の幅。
const SIDE_WIDTH: i32 = 340;

/// 種類 kind の n 枚目のページのタグ（1枚目は種類の名前のまま）。
fn page_tag(kind: &str, n: usize) -> String {
    if n <= 1 { kind.to_string() } else { format!("{kind}#{n}") }
}

fn visible_kind(nav: &adw::NavigationView) -> Option<String> {
    nav.visible_page().and_then(|p| p.tag()).map(|t| t.split('#').next().unwrap_or("").to_string())
}

const STYLE: &str = "
.artwork { border-radius: 8px; }
.artwork-frame { background-color: alpha(currentColor, 0.08); }
.artwork.round { border-radius: 9999px; }
.pane { background-color: @view_bg_color; border-radius: 12px; }
.card-button { padding: 6px; border-radius: 12px; }
.play-large { min-width: 48px; min-height: 48px; }
.player-bar { padding: 8px 16px; }
.song-list { background: transparent; }
.lyric-line { font-size: 24px; font-weight: 800; opacity: 0.4; }
.lyric-line.lyric-current { opacity: 1; color: @accent_color; }
.video { background-color: black; }
.video-layer { background-color: black; }
.ai-user { background-color: alpha(@accent_bg_color, 0.15); border-radius: 12px; padding: 6px 10px; }
.like-button:checked { color: @accent_color; background: transparent; }
.lyric-plain { font-size: 17px; line-height: 1.6; }
";

/// 窓の中身（上の帯を含む）を作る。
pub fn build(
    repo: MusicRepository,
    lyrics: wave_core::lyrics::service::LyricsService,
    playback: Rc<Playback>,
    video: Option<crate::video::VideoPlayer>,
    downloads: Rc<crate::downloads::Downloads>,
    connect: Rc<crate::connect::Connect>,
    on_logout: Rc<dyn Fn()>,
) -> (gtk::Widget, Rc<App>) {
    let css = gtk::CssProvider::new();
    css.load_from_string(STYLE);
    gtk::style_context_add_provider_for_display(&gtk::gdk::Display::default().expect("display"), &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);

    let nav = adw::NavigationView::new();
    let toasts = adw::ToastOverlay::new();
    let app = Rc::new(App { repo, lyrics, playback, nav: nav.clone(), toasts: toasts.clone(), video, downloads, connect, video_layer: Default::default(), ai: Default::default(), claude_toggle: Default::default(), editable_playlists: Default::default() });
    {
        let app = app.clone();
        gtk::glib::spawn_future_local(async move {
            app.ai.load().await;
            // 確かめ用: WAVE_DEMO_AI_ASK の頼みを、曲が流れ始めるのを待ってから送る
            if let Ok(ask) = std::env::var("WAVE_DEMO_AI_ASK") {
                gtk::glib::timeout_future_seconds(6).await;
                app.ai.send(&app, &ask);
            }
        });
    }
    {
        // 端末の連携の知らせはトーストで出す
        let weak = Rc::downgrade(&app);
        app.connect.on_notice(move |text| {
            if let Some(app) = weak.upgrade() {
                app.toast(text);
            }
        });
    }
    nav.add(&pages::home(&app));

    let toolbar = adw::ToolbarView::new();
    let bar = header(&app);
    bar.pack_end(&account_button(&app, on_logout));
    toolbar.add_top_bar(&bar);
    let body = gtk::Box::builder().spacing(8).margin_start(8).margin_end(8).build();
    body.append(&pane(&library(&app), Some(300)));
    let center = pane(&nav, None);
    center.set_hexpand(true);
    body.append(&center);
    // 右の欄は再生中とキューを切り替える（下の帯のキューのボタン。Mac・Windows と同じ）
    let side_stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).build();
    side_stack.add_named(&now_playing(&app), Some("now"));
    side_stack.add_named(&queue(&app), Some("queue"));
    side_stack.add_named(&super::ai::panel(&app), Some("ai"));
    // 中身ごとに幅を変えず、いつも同じ幅にする（Claude の長い文に合わせて広がらないように）
    side_stack.set_hhomogeneous(false);
    let side_width = adw::Clamp::builder().maximum_size(SIDE_WIDTH).tightening_threshold(SIDE_WIDTH).child(&side_stack).build();
    let side = pane(&side_width, Some(SIDE_WIDTH));
    body.append(&side);
    toasts.set_child(Some(&body));
    toolbar.set_content(Some(&toasts));
    toolbar.add_bottom_bar(&player_bar(&app, &side, &side_stack));
    // 映像を窓いっぱいに出す層（右の欄の映像の「拡大」で出す。Mac・Windows と同じ）
    let root = gtk::Overlay::builder().child(&toolbar).build();
    if let Some(layer) = video_layer(&app) {
        root.add_overlay(&layer);
        *app.video_layer.borrow_mut() = Some(layer);
    }
    (root.upcast(), app)
}

/// 窓いっぱいの映像の層。右下に「全画面」（窓を全画面にする・戻す）と「×」（元の画面に戻る）。Esc でも戻る。ダブルクリックで全画面。
fn video_layer(app: &Rc<App>) -> Option<gtk::Widget> {
    let paintable = app.video.as_ref()?.paintable().clone();
    let picture = gtk::Picture::builder().paintable(&paintable).content_fit(gtk::ContentFit::Contain).hexpand(true).vexpand(true).build();
    let fullscreen = gtk::Button::builder().icon_name("view-fullscreen-symbolic").tooltip_text("全画面").css_classes(["osd", "circular"]).build();
    let close = gtk::Button::builder().icon_name("window-close-symbolic").tooltip_text("元の画面に戻る (Esc)").css_classes(["osd", "circular"]).build();
    let buttons = gtk::Box::builder().spacing(8).halign(gtk::Align::End).valign(gtk::Align::End).margin_end(16).margin_bottom(16).build();
    buttons.append(&fullscreen);
    buttons.append(&close);
    let layer = gtk::Overlay::builder().child(&picture).visible(false).css_classes(["video-layer"]).build();
    layer.add_overlay(&buttons);
    let toggle_fullscreen = {
        let layer = layer.clone();
        move || {
            if let Some(window) = layer.root().and_downcast::<gtk::Window>() {
                if window.is_fullscreen() { window.unfullscreen() } else { window.fullscreen() }
            }
        }
    };
    let t = toggle_fullscreen.clone();
    fullscreen.connect_clicked(move |_| t());
    let double = gtk::GestureClick::new();
    let t = toggle_fullscreen.clone();
    double.connect_pressed(move |_, n, _, _| {
        if n == 2 {
            t();
        }
    });
    picture.add_controller(double);
    {
        let app = app.clone();
        close.connect_clicked(move |_| app.expand_video(false));
    }
    let keys = gtk::EventControllerKey::new();
    keys.set_propagation_phase(gtk::PropagationPhase::Capture);
    {
        let app = app.clone();
        keys.connect_key_pressed(move |_, key, _, _| {
            if key == gtk::gdk::Key::Escape && app.video_layer.borrow().as_ref().is_some_and(|l| l.is_visible()) {
                app.expand_video(false);
                return gtk::glib::Propagation::Stop;
            }
            gtk::glib::Propagation::Proceed
        });
    }
    // 窓が出てからキーを受ける（層そのものは焦点を持たないので、窓の側で受ける）
    layer.connect_realize(move |layer| {
        if let Some(window) = layer.root().and_downcast::<gtk::Window>() {
            window.add_controller(keys.clone());
        }
    });
    Some(layer.upcast())
}

/// 映像の小窓: 小さな別の窓に映像と前・再生・次を出す。指を乗せたときだけ操作を出す（Mac と同じ）。閉じても音は続ける。
fn open_mini_window(app: &Rc<App>) {
    let Some(video) = app.video.as_ref() else { return };
    let picture = gtk::Picture::builder().paintable(video.paintable()).content_fit(gtk::ContentFit::Contain).hexpand(true).vexpand(true).build();
    let previous = gtk::Button::builder().icon_name("media-skip-backward-symbolic").tooltip_text("前の曲").css_classes(["osd", "circular"]).build();
    let play = gtk::Button::builder().icon_name("media-playback-pause-symbolic").tooltip_text("再生・一時停止").css_classes(["osd", "circular"]).build();
    let next = gtk::Button::builder().icon_name("media-skip-forward-symbolic").tooltip_text("次の曲").css_classes(["osd", "circular"]).build();
    let back = gtk::Button::builder().icon_name("go-up-symbolic").tooltip_text("wave に戻す").css_classes(["osd", "circular"]).build();
    let controls = gtk::Box::builder().spacing(8).halign(gtk::Align::Center).valign(gtk::Align::End).margin_bottom(10).visible(false).build();
    for b in [&previous, &play, &next, &back] {
        controls.append(b);
    }
    let overlay = gtk::Overlay::builder().child(&picture).css_classes(["video-layer"]).build();
    overlay.add_overlay(&controls);
    let window = gtk::Window::builder().title("wave の小窓").default_width(426).default_height(240).child(&overlay).build();
    if let Some(parent) = app.window() {
        window.set_transient_for(None::<&gtk::Window>);
        window.set_application(parent.application().as_ref());
    }
    // 指を乗せたときだけ操作を出す
    let motion = gtk::EventControllerMotion::new();
    let c = controls.clone();
    motion.connect_enter(move |_, _, _| c.set_visible(true));
    let c = controls.clone();
    motion.connect_leave(move |_| c.set_visible(false));
    overlay.add_controller(motion);
    let playback = app.playback.clone();
    previous.connect_clicked(move |_| playback.previous());
    let playback = app.playback.clone();
    play.connect_clicked(move |_| playback.toggle());
    let playback = app.playback.clone();
    next.connect_clicked(move |_| playback.next());
    let (w, parent) = (window.clone(), app.window());
    back.connect_clicked(move |_| {
        if let Some(parent) = &parent {
            parent.present();
        }
        w.close();
    });
    let playback = app.playback.clone();
    let p = play.clone();
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(500), move || {
        if !p.is_mapped() && p.root().is_none() {
            return gtk::glib::ControlFlow::Break;
        }
        p.set_icon_name(if playback.player().is_playing() { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" });
        gtk::glib::ControlFlow::Continue
    });
    window.present();
}

impl App {
    /// 映像を窓いっぱいに出す・やめる（やめたら窓の全画面も戻す）。
    pub fn expand_video(&self, on: bool) {
        let Some(layer) = self.video_layer.borrow().clone() else { return };
        layer.set_visible(on);

        if !on && let Some(window) = layer.root().and_downcast::<gtk::Window>() {
            window.unfullscreen();
        }
    }
}

impl App {
    /// 確かめ用（WAVE_DEMO_QUERY）: その語で検索のページを開き、1曲目から流す。
    /// WAVE_DEMO_SIDE=lyrics なら歌詞のページも開く。WAVE_DEMO_PAGE が artist・album なら、続けてその曲のアーティスト・アルバムのページを開く。
    pub fn demo(self: &Rc<Self>, query: &str) {
        self.push(pages::search(self, query));
        let (app, query) = (self.clone(), query.to_string());
        gtk::glib::spawn_future_local(async move {
            let repo = app.repo.clone();
            if let Ok(page) = runtime::run(async move { repo.search_songs(&query, None).await }).await {
                let first = page.songs.first().cloned();
                app.playback.play_list(page.songs, 0);
                // 確かめ用: WAVE_DEMO_DOWNLOAD があれば1曲目を取り、ダウンロードのページを開く
                if std::env::var("WAVE_DEMO_DOWNLOAD").is_ok()
                    && let Some(song) = first.clone()
                {
                    app.downloads.download_song(&song);
                    app.push(pages::downloads(&app));
                }
                if std::env::var("WAVE_DEMO_SIDE").as_deref() == Ok("lyrics") {
                    app.push(super::lyrics::page(&app));
                }
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
        // 中の部品（入力の欄など）が横に広がりたがっても、欄の幅は変えない（明示しないと子の hexpand が伝わる）
        frame.set_hexpand(false);
    }
    frame.upcast()
}

/// 上の帯: 左に戻る、中央（窓の左右の中央）にホームと検索の欄。
fn header(app: &Rc<App>) -> adw::HeaderBar {
    let back = gtk::Button::builder().icon_name("go-previous-symbolic").tooltip_text("戻る").visible(false).build();
    let home = gtk::Button::builder().icon_name("go-home-symbolic").tooltip_text("ホーム").css_classes(["circular"]).build();
    let browse = gtk::Button::builder().icon_name("view-grid-symbolic").tooltip_text("見つける（気分・ジャンル）").css_classes(["circular"]).build();
    let search = gtk::SearchEntry::builder().placeholder_text("何を聴きたいですか？").width_request(440).build();
    let center = gtk::Box::builder().spacing(8).build();
    center.append(&home);
    center.append(&browse);
    center.append(&search);
    // Claude は検索の欄の右（Mac・Windows と同じ）。右の欄に出す
    let claude = gtk::ToggleButton::builder().icon_name("chat-message-new-symbolic").tooltip_text("Claude に聞く (Ctrl+J)").css_classes(["circular", "flat"]).build();
    center.append(&claude);
    *app.claude_toggle.borrow_mut() = Some(claude.clone());
    let shortcut = gtk::ShortcutController::new();
    shortcut.set_scope(gtk::ShortcutScope::Managed);
    let toggle = claude.clone();
    shortcut.add_shortcut(gtk::Shortcut::new(
        gtk::ShortcutTrigger::parse_string("<Control>j"),
        Some(gtk::CallbackAction::new(move |_, _| {
            toggle.set_active(!toggle.is_active());
            gtk::glib::Propagation::Stop
        })),
    ));
    center.add_controller(shortcut);
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
    {
        let app = app.clone();
        browse.connect_clicked(move |_| {
            if app.visible_kind().as_deref() != Some("moods") {
                app.push(pages::moods(&app));
            }
        });
    }
    let back_button = back.clone();
    app.nav.connect_visible_page_notify(move |nav| back_button.set_visible(nav.navigation_stack().n_items() > 1));
    let suggestions = suggestions_popover(&app, &search);
    let app = app.clone();
    search.connect_activate(move |entry| {
        suggestions.popdown();
        let query = entry.text().trim().to_string();
        if query.is_empty() {
            return;
        }
        // 検索のページは1枚だけ積む（打ち直したら置き換える）
        if app.visible_kind().as_deref() == Some("search") {
            app.nav.pop();
        }
        app.push(pages::search(&app, &query));
    });
    bar
}

/// 入力の欄（中の文字の部品も含めて）に手が乗っているか。
fn focused(entry: &gtk::SearchEntry) -> bool {
    entry.root().and_then(|r| r.focus()).is_some_and(|f| f == *entry.upcast_ref::<gtk::Widget>() || f.is_ancestor(entry))
}

/// 打ち込み中の候補（語・アーティスト・アルバム・曲）。語を押すとその語で検索し、アーティスト・アルバムはそのページ、曲は鳴らす。
fn suggestions_popover(app: &Rc<App>, entry: &gtk::SearchEntry) -> gtk::Popover {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).width_request(440).css_classes(["navigation-sidebar"]).build();
    // 打ち続けられるよう、候補を出しても入力の欄から手を離させない
    let popover = gtk::Popover::builder().child(&list).autohide(false).has_arrow(false).position(gtk::PositionType::Bottom).can_focus(false).build();
    popover.set_parent(entry);
    let request = Rc::new(Cell::new(0u64));
    // 検索した語（打ち直すまで候補を出し直さない。候補を押して欄の文字を替えたときも）
    let searched: Rc<RefCell<String>> = Rc::default();
    {
        let (request, searched, popover) = (request.clone(), searched.clone(), popover.clone());
        entry.connect_activate(move |entry| {
            request.set(request.get() + 1);
            *searched.borrow_mut() = entry.text().trim().to_string();
            popover.popdown();
        });
    }
    let (app_c, popover_c, list_c) = (app.clone(), popover.clone(), list.clone());
    entry.connect_search_changed(move |entry| {
        let input = entry.text().trim().to_string();
        let id = request.get() + 1;
        request.set(id);
        if input.is_empty() || !focused(entry) || *searched.borrow() == input {
            popover_c.popdown();
            return;
        }
        let (app, popover, list, request, entry) = (app_c.clone(), popover_c.clone(), list_c.clone(), request.clone(), entry.clone());
        gtk::glib::spawn_future_local(async move {
            let repo = app.repo.clone();
            let Ok(found) = runtime::run(async move { repo.suggestions(&input).await }).await else { return };
            // 打ち直した後に古い候補を出さない
            if request.get() != id || !focused(&entry) {
                return;
            }
            while let Some(row) = list.first_child() {
                list.remove(&row);
            }
            let close = |popover: &gtk::Popover, entry: &gtk::SearchEntry| {
                popover.popdown();
                entry.set_text("");
            };
            for query in found.queries.into_iter().take(6) {
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&query)).activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name("system-search-symbolic"));
                let (entry, popover) = (entry.clone(), popover.clone());
                row.connect_activated(move |_| {
                    popover.popdown();
                    entry.set_text(&query);
                    entry.emit_activate();
                });
                list.append(&row);
            }
            for artist in found.artists.into_iter().take(2) {
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&artist.name)).subtitle("アーティスト").activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name("avatar-default-symbolic"));
                let (app, entry, popover) = (app.clone(), entry.clone(), popover.clone());
                row.connect_activated(move |_| {
                    close(&popover, &entry);
                    app.push(pages::artist(&app, &artist.browse_id, &artist.name));
                });
                list.append(&row);
            }
            for album in found.albums.into_iter().take(2) {
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&album.title)).subtitle(gtk::glib::markup_escape_text(album.subtitle.as_deref().unwrap_or("アルバム"))).activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name("media-optical-symbolic"));
                let (app, entry, popover) = (app.clone(), entry.clone(), popover.clone());
                row.connect_activated(move |_| {
                    close(&popover, &entry);
                    app.push(pages::album(&app, &album.browse_id, &album.title));
                });
                list.append(&row);
            }
            for song in found.songs.into_iter().take(3) {
                let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&song.title)).subtitle(gtk::glib::markup_escape_text(&song.artists_text())).activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name("audio-x-generic-symbolic"));
                let (app, entry, popover) = (app.clone(), entry.clone(), popover.clone());
                row.connect_activated(move |_| {
                    close(&popover, &entry);
                    app.playback.play_list(vec![song.clone()], 0);
                });
                list.append(&row);
            }
            if list.first_child().is_some() {
                popover.popup();
            } else {
                popover.popdown();
            }
        });
    });
    // 欄から離れたら・Esc で閉じる
    let focus = gtk::EventControllerFocus::new();
    let popover_f = popover.clone();
    focus.connect_leave(move |_| {
        let popover = popover_f.clone();
        // 候補を押したときは、押し終わってから閉じる
        gtk::glib::timeout_add_local_once(std::time::Duration::from_millis(200), move || popover.popdown());
    });
    entry.add_controller(focus);
    let popover_s = popover.clone();
    entry.connect_stop_search(move |_| popover_s.popdown());
    popover
}

/// 左の欄: マイライブラリ（先頭に高く評価した曲、続いてライブラリのプレイリスト）。
fn library(app: &Rc<App>) -> gtk::Widget {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    column.append(&gtk::Label::builder().label("マイライブラリ").xalign(0.0).margin_top(16).margin_bottom(8).margin_start(16).css_classes(["title-3"]).build());
    let list = gtk::ListBox::builder().css_classes(["navigation-sidebar"]).build();
    let liked = library_row("高く評価した曲", "自動のプレイリスト", None);
    list.append(&liked);
    // ダウンロード（高く評価した曲と同じく、いつも先頭の方に出す）
    let downloads_row = library_row_icon("ダウンロード", "この PC に保存", "folder-download-symbolic");
    list.append(&downloads_row);
    let scroller = gtk::ScrolledWindow::builder().child(&list).hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).build();
    column.append(&scroller);

    // 行の順の行き先（2行目のダウンロードはプレイリストではないので、印の ID にする）
    let entries: Rc<std::cell::RefCell<Vec<(String, String)>>> =
        Rc::new(std::cell::RefCell::new(vec![(LIKED_SONGS.to_string(), "高く評価した曲".to_string()), (DOWNLOADS_ENTRY.to_string(), String::new())]));
    {
        let (app, entries) = (app.clone(), entries.clone());
        list.connect_row_activated(move |_, row| {
            match entries.borrow().get(row.index() as usize).cloned() {
                Some((id, _)) if id == DOWNLOADS_ENTRY => app.push(pages::downloads(&app)),
                Some((id, title)) => app.push(pages::playlist(&app, &id, &title)),
                None => {}
            }
        });
    }
    let (app, list) = (app.clone(), list.clone());
    gtk::glib::spawn_future_local(async move {
        let repo = app.repo.clone();
        let Ok(playlists) = runtime::run(async move { repo.library_playlists().await }).await else { return };
        *app.editable_playlists.borrow_mut() = playlists.iter().filter(|p| p.editable).cloned().collect();
        for p in playlists.into_iter().filter(|p| p.playlist_id != LIKED_SONGS) {
            list.append(&library_row(&p.title, p.subtitle.as_deref().unwrap_or(""), p.thumbnail_url.as_deref()));
            entries.borrow_mut().push((p.playlist_id, p.title));
        }
    });
    column.upcast()
}

/// マイライブラリの「ダウンロード」の行の印（YouTube の ID とは重ならない形）。
const DOWNLOADS_ENTRY: &str = "wave:downloads";

fn library_row_icon(title: &str, subtitle: &str, icon: &str) -> gtk::ListBoxRow {
    let row = gtk::Box::builder().spacing(12).margin_top(4).margin_bottom(4).build();
    row.append(&gtk::Image::builder().icon_name(icon).pixel_size(24).width_request(48).height_request(48).css_classes(["artwork", "accent"]).build());
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).build();
    text.append(&gtk::Label::builder().label(title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build());
    text.append(&gtk::Label::builder().label(subtitle).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).css_classes(["caption", "dim-label"]).build());
    row.append(&text);
    gtk::ListBoxRow::builder().child(&row).build()
}

fn library_row(title: &str, subtitle: &str, thumbnail: Option<&str>) -> gtk::ListBoxRow {
    let row = gtk::Box::builder().spacing(12).margin_top(4).margin_bottom(4).build();
    let picture = images::square(48);
    picture.load(thumbnail, 96);
    if thumbnail.is_none() {
        // 高く評価した曲は画像の代わりに印
        let icon = gtk::Image::builder().icon_name("starred-symbolic").pixel_size(24).width_request(48).height_request(48).css_classes(["artwork", "accent"]).build();
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

/// 右の欄の上: ジャケットか、MV のある曲では映像（Mac・Windows と同じ「曲｜動画」の切り替え）。
/// 映像は音の位置に合わせ続け、MV に無い区間ではジャケットに戻す。
fn media_area(app: &Rc<App>, art: &images::Art) -> gtk::Widget {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 8);
    let Some(video_player) = app.video.as_ref() else {
        column.append(&art.root);
        return column.upcast();
    };
    let switch = adw::ToggleGroup::builder().halign(gtk::Align::Center).visible(false).css_classes(["round"]).build();
    switch.add(adw::Toggle::builder().label("曲").name("song").build());
    switch.add(adw::Toggle::builder().label("動画").name("video").build());
    switch.set_active_name(Some("song"));
    let picture = gtk::Picture::builder().paintable(video_player.paintable()).content_fit(gtk::ContentFit::Contain).width_request(308).height_request(174).css_classes(["artwork", "video"]).build();
    picture.set_overflow(gtk::Overflow::Hidden);
    // 映像の右下の「拡大」で、窓いっぱいに出す
    let expand = gtk::Button::builder().icon_name("view-fullscreen-symbolic").tooltip_text("拡大").halign(gtk::Align::End).valign(gtk::Align::End).margin_end(6).margin_bottom(6).css_classes(["osd", "circular"]).build();
    {
        let app = app.clone();
        expand.connect_clicked(move |_| app.expand_video(true));
    }
    // 小窓（小さな別の窓。常に手前にするのは GNOME の窓のメニューで。Wayland ではアプリから手前に固定できない）
    let mini = gtk::Button::builder().icon_name("view-restore-symbolic").tooltip_text("小窓").css_classes(["osd", "circular"]).build();
    {
        let app = app.clone();
        mini.connect_clicked(move |_| open_mini_window(&app));
    }
    let corner = gtk::Box::builder().spacing(6).halign(gtk::Align::End).valign(gtk::Align::End).margin_end(6).margin_bottom(6).build();
    expand.set_margin_end(0);
    expand.set_margin_bottom(0);
    corner.append(&mini);
    corner.append(&expand);
    let video_box = gtk::Overlay::builder().child(&picture).build();
    video_box.add_overlay(&corner);
    // 映像のときは横長の枠にする（正方形のままだと上下に黒い帯が出る）
    let stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).vhomogeneous(false).build();
    stack.add_named(&art.root, Some("art"));
    stack.add_named(&video_box, Some("video"));
    column.append(&switch);
    column.append(&stack);

    // 今の曲の映像（曲が変わるたびに next から探す）
    let current: Rc<std::cell::RefCell<Option<wave_core::music::models::MusicVideo>>> = Rc::default();
    let looked_up: Rc<std::cell::RefCell<Option<String>>> = Rc::default();
    let want_video = || std::env::var("WAVE_DEMO_VIDEO").is_ok();
    {
        let (app, switch, current, looked_up) = (app.clone(), switch.clone(), current.clone(), looked_up.clone());
        let playback = app.playback.clone();
        playback.on_change(move || {
            let id = app.playback.current().map(|s| s.video_id);
            if *looked_up.borrow() == id {
                return;
            }
            *looked_up.borrow_mut() = id.clone();
            *current.borrow_mut() = None;
            switch.set_visible(false);
            if let Some(video) = app.video.as_ref() {
                video.stop();
            }
            let Some(id) = id else { return };
            let (repo, switch, current, looked_up) = (app.repo.clone(), switch.clone(), current.clone(), looked_up.clone());
            gtk::glib::spawn_future_local(async move {
                let wanted = id.clone();
                let found = runtime::run(async move { repo.music_video(&wanted).await }).await.ok().flatten();
                if looked_up.borrow().as_deref() != Some(id.as_str()) {
                    return;
                }
                switch.set_visible(found.is_some());
                *current.borrow_mut() = found;
                // 確かめ用: WAVE_DEMO_VIDEO があれば動画にする
                if switch.is_visible() && want_video() {
                    switch.set_active_name(Some("video"));
                }
            });
        });
    }
    // 動画にしたら読み込み、曲に戻したら止める
    {
        let (app, current, stack) = (app.clone(), current.clone(), stack.clone());
        switch.connect_active_name_notify(move |switch| {
            let on = switch.active_name().as_deref() == Some("video");
            let Some(video) = app.video.as_ref() else { return };
            if !on {
                video.stop();
                stack.set_visible_child_name("art");
                app.expand_video(false);
                return;
            }
            let Some(mv) = current.borrow().clone() else { return };
            let app = app.clone();
            gtk::glib::spawn_future_local(async move {
                if let Some(video) = app.video.as_ref()
                    && !video.load(&mv).await
                {
                    app.toast("動画を読み込めませんでした");
                }
            });
        });
    }
    // 音の位置に合わせ続ける（MV に無い区間ではジャケットに戻す）
    let (app, weak_switch) = (app.clone(), switch.downgrade());
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(300), move || {
        let Some(switch) = weak_switch.upgrade() else { return gtk::glib::ControlFlow::Break };
        if switch.active_name().as_deref() != Some("video") || !switch.is_visible() {
            stack.set_visible_child_name("art");
            return gtk::glib::ControlFlow::Continue;
        }
        let (Some(video), Some(mv)) = (app.video.as_ref(), current.borrow().clone()) else { return gtk::glib::ControlFlow::Continue };
        let player = app.playback.player();
        let showing = video.sync(&mv, player.position_ms().unwrap_or(0), player.is_playing());
        stack.set_visible_child_name(if showing { "video" } else { "art" });
        gtk::glib::ControlFlow::Continue
    });
    column.upcast()
}

/// 右の欄: 再生中の曲（大きなジャケット・曲名・アーティスト）と次の曲。
fn now_playing(app: &Rc<App>) -> gtk::Widget {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(16).margin_bottom(16).margin_start(16).margin_end(16).build();
    let picture = images::square(308);
    let title = gtk::Label::builder().xalign(0.0).wrap(true).css_classes(["title-3"]).build();
    let artist = gtk::Label::builder().xalign(0.0).wrap(true).css_classes(["dim-label"]).build();
    let next_title = gtk::Label::builder().label("次の曲").xalign(0.0).margin_top(12).css_classes(["heading"]).build();
    let next_list = gtk::ListBox::builder().css_classes(["boxed-list"]).selection_mode(gtk::SelectionMode::None).build();
    column.append(&media_area(app, &picture));
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
    let shown = Rc::new(RefCell::new(None::<(String, Option<String>)>));
    let refresh = {
        let (app, stack, next_list) = (app.clone(), stack.clone(), next_list.clone());
        move || {
            // ほかの端末で鳴っていれば、それを出す
            let (song, upcoming) = match app.connect.mirror() {
                Some(mirror) => (Some(mirror.song), mirror.next.into_iter().collect()),
                None => (app.playback.current(), app.playback.upcoming()),
            };
            let Some(song) = song else {
                *shown.borrow_mut() = None;
                stack.set_visible_child_name("empty");
                return;
            };
            // 連携の知らせは毎秒来るので、曲と次の曲が同じなら描き直さない
            let key = (song.video_id.clone(), upcoming.first().map(|s: &wave_core::music::models::Song| s.video_id.clone()));
            if shown.borrow().as_ref() == Some(&key) {
                return;
            }
            *shown.borrow_mut() = Some(key);
            stack.set_visible_child_name("song");
            picture.load(song.thumbnail_url.as_deref(), 616);
            title.set_label(&song.title);
            artist.set_label(&song.artists_text());
            while let Some(row) = next_list.first_child() {
                next_list.remove(&row);
            }
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
    app.playback.on_change(refresh.clone());
    app.connect.on_change(refresh);
    stack.upcast()
}

/// 上の帯の右端: アカウントの写真。押すと名前とログアウト。
fn account_button(app: &Rc<App>, on_logout: Rc<dyn Fn()>) -> gtk::MenuButton {
    let avatar = adw::Avatar::builder().size(30).show_initials(true).build();
    let name = gtk::Label::builder().css_classes(["heading"]).margin_bottom(6).build();
    let history = gtk::Button::builder().label("最近再生した曲").css_classes(["flat"]).build();
    let settings = gtk::Button::builder().label("設定").css_classes(["flat"]).build();
    let logout = gtk::Button::builder().label("ログアウト").css_classes(["flat"]).build();
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).margin_top(6).margin_bottom(6).margin_start(6).margin_end(6).build();
    column.append(&name);
    column.append(&history);
    column.append(&settings);
    column.append(&logout);
    let popover = gtk::Popover::builder().child(&column).build();
    let button = gtk::MenuButton::builder().child(&avatar).popover(&popover).tooltip_text("アカウント").css_classes(["flat", "circular"]).build();
    {
        let (app, popover) = (app.clone(), popover.clone());
        settings.connect_clicked(move |_| {
            popover.popdown();
            app.push(super::settings::page(&app));
        });
    }
    {
        let (app, popover) = (app.clone(), popover.clone());
        history.connect_clicked(move |_| {
            popover.popdown();
            app.push(super::pages::history(&app));
        });
    }
    logout.connect_clicked(move |_| {
        popover.popdown();
        on_logout();
    });
    let repo = app.repo.clone();
    gtk::glib::spawn_future_local(async move {
        let Ok(account) = runtime::run(async move { repo.account().await }).await else { return };
        if let Some(text) = &account.name {
            avatar.set_text(Some(text));
            name.set_label(text);
        }
        if let Some(url) = account.photo_url {
            images::texture(&url, 88, move |texture| avatar.set_custom_image(Some(&texture)));
        }
    });
    button
}

/// 右の欄のキュー: 今の曲と、次に流れる曲（押すとその曲へ）。
fn queue(app: &Rc<App>) -> gtk::Widget {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).margin_top(16).margin_bottom(16).margin_start(12).margin_end(12).build();
    column.append(&gtk::Label::builder().label("キュー").xalign(0.0).margin_start(4).css_classes(["title-3"]).build());
    let now_label = gtk::Label::builder().label("再生中").xalign(0.0).margin_start(4).css_classes(["heading"]).build();
    let now_list = gtk::ListBox::builder().css_classes(["boxed-list"]).selection_mode(gtk::SelectionMode::None).build();
    let next_label = gtk::Label::builder().label("次に流れる曲").xalign(0.0).margin_start(4).margin_top(8).css_classes(["heading"]).build();
    let next_list = gtk::ListBox::builder().css_classes(["boxed-list"]).selection_mode(gtk::SelectionMode::None).build();
    for widget in [now_label.upcast_ref::<gtk::Widget>(), now_list.upcast_ref(), next_label.upcast_ref(), next_list.upcast_ref()] {
        column.append(widget);
    }
    // 次に流れる曲の何番目の行が、キューの何番目か
    let offset = Rc::new(Cell::new(0usize));
    {
        let (app, offset) = (app.clone(), offset.clone());
        next_list.connect_row_activated(move |_, row| app.playback.play_at(offset.get() + row.index() as usize));
    }
    let refresh = {
        let app = app.clone();
        move || {
            for list in [&now_list, &next_list] {
                while let Some(row) = list.first_child() {
                    list.remove(&row);
                }
            }
            let (songs, index) = app.playback.queue();
            let start = index.map_or(0, |i| i + 1);
            offset.set(start);
            if let Some(current) = index.and_then(|i| songs.get(i)) {
                now_list.append(&queue_row(current, false));
            }
            now_label.set_visible(index.is_some());
            now_list.set_visible(index.is_some());
            next_label.set_visible(songs.len() > start);
            next_list.set_visible(songs.len() > start);
            for song in songs.iter().skip(start) {
                next_list.append(&queue_row(song, true));
            }
        }
    };
    refresh();
    app.playback.on_change(refresh);
    gtk::ScrolledWindow::builder().child(&column).hscrollbar_policy(gtk::PolicyType::Never).build().upcast()
}

fn queue_row(song: &wave_core::music::models::Song, activatable: bool) -> adw::ActionRow {
    let row = adw::ActionRow::builder()
        .title(gtk::glib::markup_escape_text(&song.title))
        .subtitle(gtk::glib::markup_escape_text(&song.artists_text()))
        .title_lines(1)
        .subtitle_lines(1)
        .activatable(activatable)
        .build();
    let art = images::square(40);
    art.load(song.thumbnail_url.as_deref(), 80);
    row.add_prefix(&art.root);
    row
}

/// 評価のボタン: 曲が変わったら評価を読み直し、押されたら書き込む（失敗したら元に戻す）。
fn like_behaviour(app: &Rc<App>, like: &gtk::ToggleButton) {
    // 評価済みは塗った星、まだなら輪郭の星
    like.connect_active_notify(|b| b.set_icon_name(if b.is_active() { "starred-symbolic" } else { "non-starred-symbolic" }));
    // 読み直した値を入れている間は書き込まない
    let updating = Rc::new(Cell::new(false));
    let shown: Rc<std::cell::RefCell<Option<String>>> = Rc::default();
    {
        let (app, like, updating, shown) = (app.clone(), like.clone(), updating.clone(), shown.clone());
        let playback = app.playback.clone();
        playback.on_change(move || {
            let id = app.playback.current().map(|s| s.video_id);
            if *shown.borrow() == id {
                return;
            }
            *shown.borrow_mut() = id.clone();
            updating.set(true);
            like.set_active(false);
            like.set_sensitive(false);
            updating.set(false);
            let Some(id) = id else { return };
            let (repo, like, updating, shown) = (app.repo.clone(), like.clone(), updating.clone(), shown.clone());
            gtk::glib::spawn_future_local(async move {
                let wanted = id.clone();
                let status = runtime::run(async move { repo.like_status(&wanted).await }).await;
                if shown.borrow().as_deref() != Some(id.as_str()) {
                    return;
                }
                if let Ok(status) = status {
                    updating.set(true);
                    like.set_active(status == Some(wave_core::music::models::LikeStatus::Like));
                    updating.set(false);
                    like.set_sensitive(status.is_some());
                }
            });
        });
    }
    let app = app.clone();
    like.connect_toggled(move |button| {
        if updating.get() {
            return;
        }
        let Some(song) = app.playback.current() else { return };
        let (repo, liked, button, updating) = (app.repo.clone(), button.is_active(), button.clone(), updating.clone());
        button.set_tooltip_text(Some(if liked { "高く評価を外す" } else { "高く評価" }));
        gtk::glib::spawn_future_local(async move {
            let id = song.video_id.clone();
            if runtime::run(async move { repo.set_liked(&id, liked).await }).await.is_err() {
                updating.set(true);
                button.set_active(!liked);
                updating.set(false);
            }
        });
    });
}

/// 右の欄の切り替えのボタン（キュー・Claude）: 押すと右の欄をその中身にし（しまってあれば出す）、もう一度押すと再生中に戻す。
/// ほかのボタンで切り替わったら、このボタンも戻す。
fn link_side_toggle(toggle: &gtk::ToggleButton, stack: &gtk::Stack, name: &'static str, side_toggle: &gtk::ToggleButton) {
    let (stack_for_toggle, side_toggle) = (stack.clone(), side_toggle.clone());
    toggle.connect_toggled(move |t| {
        if t.is_active() {
            stack_for_toggle.set_visible_child_name(name);
            side_toggle.set_active(true);
        } else if stack_for_toggle.visible_child_name().as_deref() == Some(name) {
            stack_for_toggle.set_visible_child_name("now");
        }
    });
    let toggle = toggle.clone();
    stack.connect_visible_child_name_notify(move |stack| {
        let shown = stack.visible_child_name().as_deref() == Some(name);
        if toggle.is_active() != shown {
            toggle.set_active(shown);
        }
    });
}

/// 下の帯: 左に曲、中央に操作と位置（窓の左右の中央）、右にキュー・音量・右の欄の出し入れ。
/// シャッフル・繰り返しのボタンの見た目（前の版の端末を映している間は押せない）。
fn show_modes(shuffle: &gtk::ToggleButton, repeat: &gtk::ToggleButton, shuffled: bool, mode: crate::playback::Repeat, usable: bool) {
    use crate::playback::Repeat;
    shuffle.set_active(shuffled);
    repeat.set_active(mode != Repeat::Off);
    repeat.set_icon_name(if mode == Repeat::One { "media-playlist-repeat-song-symbolic" } else { "media-playlist-repeat-symbolic" });
    repeat.set_tooltip_text(Some(match mode {
        Repeat::Off => "繰り返し",
        Repeat::All => "全体を繰り返し中",
        Repeat::One => "1曲を繰り返し中",
    }));
    shuffle.set_sensitive(usable);
    repeat.set_sensitive(usable);
}

/// 再生する端末を選ぶボタン（家の端末の一覧。押した端末へ再生を移す。この PC を押すと、ほかの端末の再生を引き取る）。
fn devices_button(app: &Rc<App>) -> gtk::MenuButton {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).width_request(280).css_classes(["navigation-sidebar"]).build();
    let popover = gtk::Popover::builder().child(&list).build();
    let button = gtk::MenuButton::builder().icon_name("network-wireless-symbolic").tooltip_text("再生する端末").popover(&popover).css_classes(["flat"]).build();
    let app = app.clone();
    popover.connect_show(move |popover| {
        while let Some(row) = list.first_child() {
            list.remove(&row);
        }
        let info = app.connect.info();
        let members = app.connect.members();
        if !info.in_household || members.is_empty() {
            list.append(&gtk::Label::builder().label("まだ連携していません（設定の「端末の連携」から）").wrap(true).margin_top(12).margin_bottom(12).margin_start(12).margin_end(12).css_classes(["dim-label"]).build());
            return;
        }
        let mirror = app.connect.mirror();
        for member in members.into_iter().filter(|m| m.kind.as_deref() != Some("api")) {
            // 今鳴っている端末（手元で鳴っていればこの PC、ほかを映していればその端末）
            let playing_here = if member.is_self { mirror.is_none() && info.is_writer } else { mirror.as_ref().is_some_and(|m| m.device_id == member.device_id) };
            let subtitle = if playing_here {
                "再生中"
            } else if member.is_self || member.online {
                "つながっています"
            } else {
                "オフライン"
            };
            let icon = match member.kind.as_deref() {
                Some("phone") => "phone-symbolic",
                _ if member.is_self => "computer-symbolic",
                Some("pc") => "computer-symbolic",
                _ => "audio-speakers-symbolic",
            };
            let row = adw::ActionRow::builder().title(gtk::glib::markup_escape_text(&member.label)).subtitle(subtitle).activatable(!playing_here && (member.is_self || member.online)).build();
            row.add_prefix(&gtk::Image::from_icon_name(icon));
            if playing_here {
                row.add_suffix(&gtk::Image::from_icon_name("object-select-symbolic"));
            }
            let (app, pop, device_id, is_self) = (app.clone(), popover.clone(), member.device_id.clone(), member.is_self);
            row.connect_activated(move |_| {
                if is_self {
                    app.connect.transfer_here();
                } else {
                    app.connect.play_on_device(&device_id);
                }
                pop.popdown();
            });
            list.append(&row);
        }
    });
    button
}

fn player_bar(app: &Rc<App>, side: &gtk::Widget, side_stack: &gtk::Stack) -> gtk::Widget {
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
    // 評価（高く評価の付け外し。Mac・Windows と同じく曲名の右）
    let like = gtk::ToggleButton::builder().icon_name("non-starred-symbolic").tooltip_text("高く評価").valign(gtk::Align::Center).css_classes(["flat", "circular", "like-button"]).build();
    song.append(&like);
    like_behaviour(app, &like);

    // 中央: 操作と位置
    let previous = gtk::Button::builder().icon_name("media-skip-backward-symbolic").tooltip_text("前の曲").css_classes(["circular", "flat"]).build();
    let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("再生").css_classes(["circular", "suggested-action", "play-large"]).build();
    let next = gtk::Button::builder().icon_name("media-skip-forward-symbolic").tooltip_text("次の曲").css_classes(["circular", "flat"]).build();
    // シャッフルと繰り返し（Mac・Windows と同じく前・次の外側）
    let shuffle = gtk::ToggleButton::builder().icon_name("media-playlist-shuffle-symbolic").tooltip_text("シャッフル").css_classes(["circular", "flat"]).build();
    let repeat = gtk::ToggleButton::builder().icon_name("media-playlist-repeat-symbolic").tooltip_text("繰り返し").css_classes(["circular", "flat"]).build();
    let controls = gtk::Box::builder().spacing(12).halign(gtk::Align::Center).build();
    controls.append(&shuffle);
    controls.append(&previous);
    controls.append(&play);
    controls.append(&next);
    controls.append(&repeat);
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
    let lyrics_toggle = gtk::ToggleButton::builder().icon_name("text-x-generic-symbolic").tooltip_text("歌詞").css_classes(["flat"]).build();
    let queue_toggle = gtk::ToggleButton::builder().icon_name("view-list-symbolic").tooltip_text("キュー").css_classes(["flat"]).build();
    let end = gtk::Box::builder().spacing(8).valign(gtk::Align::Center).build();
    // 端末の連携が使えるときだけ、再生する端末を選ぶボタン
    if app.connect.info().configured {
        end.append(&devices_button(app));
    }
    end.append(&lyrics_toggle);
    end.append(&queue_toggle);
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
        // 押した結果は描き直しで合わせる（押した瞬間の切り替わりは打ち消す）
        let app_for_shuffle = app.clone();
        shuffle.connect_clicked(move |_| app_for_shuffle.connect.toggle_shuffle());
        let app_for_repeat = app.clone();
        repeat.connect_clicked(move |_| app_for_repeat.connect.cycle_repeat());
        let playback = app.playback.clone();
        previous.connect_clicked(move |_| playback.previous());
        let playback = app.playback.clone();
        play.connect_clicked(move |_| playback.toggle());
        let playback = app.playback.clone();
        next.connect_clicked(move |_| playback.next());
        let app_for_volume = app.clone();
        volume.connect_change_value(move |_, _, value| {
            let value = value.clamp(0.0, 1.0);
            // ほかの端末を映している間は、その端末の音量を変える
            if app_for_volume.connect.mirror().is_some() {
                app_for_volume.connect.remote_volume((value * 100.0).round() as i32);
            } else {
                app_for_volume.playback.player().set_volume(value);
            }
            gtk::glib::Propagation::Proceed
        });
        // 右の欄をしまったら、次に出すときは再生中から
        let (side, stack) = (side.clone(), side_stack.clone());
        side_toggle.connect_toggled(move |t| {
            side.set_visible(t.is_active());
            if !t.is_active() {
                stack.set_visible_child_name("now");
            }
        });
        link_side_toggle(&queue_toggle, side_stack, "queue", &side_toggle);
        if let Some(claude) = app.claude_toggle.borrow().as_ref() {
            link_side_toggle(claude, side_stack, "ai", &side_toggle);
        }
    }
    // 歌詞は中央のページに出す（もう一度押すと閉じる。Mac・Windows と同じ）。ページを戻る・ホームで閉じたらボタンも戻す
    {
        let app_for_toggle = app.clone();
        lyrics_toggle.connect_toggled(move |t| {
            let open = app_for_toggle.visible_kind().as_deref() == Some("lyrics");
            if t.is_active() && !open {
                app_for_toggle.push(super::lyrics::page(&app_for_toggle));
            } else if !t.is_active() && open {
                app_for_toggle.nav.pop();
            }
        });
        let toggle = lyrics_toggle.clone();
        app.nav.connect_visible_page_notify(move |nav| {
            let open = visible_kind(nav).as_deref() == Some("lyrics");
            if toggle.is_active() != open {
                toggle.set_active(open);
            }
        });
    }
    // 確かめ用: 起動したら右の欄をキュー（WAVE_DEMO_SIDE=queue）か Claude（=ai）にする
    match std::env::var("WAVE_DEMO_SIDE").as_deref() {
        Ok("queue") => queue_toggle.set_active(true),
        Ok("ai") => {
            if let Some(claude) = app.claude_toggle.borrow().as_ref() {
                claude.set_active(true);
            }
        }
        _ => {}
    }
    // 利用者が動かしたときだけシークする（位置の書き換えでは呼ばれない）
    let dragging = Rc::new(Cell::new(false));
    {
        let (app, dragging) = (app.clone(), dragging.clone());
        seek.connect_change_value(move |_, _, value| {
            let playback = &app.playback;
            if let Some(mirror) = app.connect.mirror() {
                if let Some(seconds) = mirror.song.duration_seconds {
                    app.connect.remote_seek((value.clamp(0.0, 1.0) * f64::from(seconds) * 1000.0) as i64);
                }
            } else if let Some(total) = playback.player().duration_ms() {
                playback.player().seek_ms((value.clamp(0.0, 1.0) * total as f64) as u64);
            }
            dragging.set(false);
            gtk::glib::Propagation::Proceed
        });
    }
    let refresh = {
        let (app, bar, play, next, song, volume) = (app.clone(), bar.clone(), play.clone(), next.clone(), song.clone(), volume.clone());
        let (shuffle, repeat) = (shuffle.clone(), repeat.clone());
        move || {
            let (shuffled, repeat_mode, modes_usable) = match app.connect.mirror() {
                Some(mirror) => (mirror.shuffle.unwrap_or(false), mirror.repeat, mirror.shuffle.is_some()),
                None => (app.playback.shuffled(), app.playback.repeat(), true),
            };
            show_modes(&shuffle, &repeat, shuffled, repeat_mode, modes_usable);
            // ほかの端末で鳴っていれば、それを映す（操作はその端末への命令になる）
            if let Some(mirror) = app.connect.mirror() {
                bar.set_sensitive(true);
                song.set_visible(true);
                art.load(mirror.song.thumbnail_url.as_deref(), 112);
                title.set_label(&mirror.song.title);
                artist.set_label(&format!("{}で再生中 · {}", mirror.label, mirror.song.artists_text()));
                next.set_sensitive(mirror.next.is_some());
                play.set_icon_name(if mirror.playing { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" });
                play.set_tooltip_text(Some(if mirror.playing { "一時停止" } else { "再生" }));
                if let Some(percent) = mirror.volume_percent {
                    volume.set_value(f64::from(percent) / 100.0);
                }
                return;
            }
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
    app.connect.on_change(refresh.clone());
    let app = app.clone();
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(500), move || {
        let player = app.playback.player();
        let (pos, total) = match app.connect.mirror() {
            Some(mirror) => (mirror.position_ms.max(0) as u64, u64::from(mirror.song.duration_seconds.unwrap_or(0)) * 1000),
            // 前回のキューを戻しただけのときは、戻した位置と曲の長さを出す
            None => (
                app.playback.position_ms(),
                player.duration_ms().or_else(|| app.playback.current().and_then(|s| s.duration_seconds).map(|d| u64::from(d) * 1000)).unwrap_or(0),
            ),
        };
        // ライブ配信には長さもシークも無い
        let live = app.connect.mirror().map(|m| m.song.is_live).unwrap_or_else(|| app.playback.current().is_some_and(|s| s.is_live));
        seek.set_sensitive(!live);
        position.set_label(&pages::format_seconds((pos / 1000) as u32));
        duration.set_label(&if live { "ライブ".to_string() } else { pages::format_seconds((total / 1000) as u32) });
        if live {
            seek.set_value(1.0);
        } else if !dragging.get() && total > 0 {
            seek.set_value(pos as f64 / total as f64);
        }
        refresh();
        gtk::glib::ControlFlow::Continue
    });
    bar.upcast()
}
