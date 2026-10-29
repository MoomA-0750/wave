//! wave の Linux（GNOME）版。画面は GTK4 + libadwaita で作り、YouTube Music とのやり取りは wave-core に任せる。

mod ai_chat;
mod connect;
mod downloads;
mod keyring;
mod login;
mod mpris;
mod playback;
mod player;
mod queue_store;
mod releases;
mod runtime;
mod settings;
mod ui;
mod video;

use adw::prelude::*;
use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;
use wave_core::stream::player::PlayerCipherProvider;
use wave_core::stream::resolver::StreamResolver;

const APP_ID: &str = "xyz.mooma0750.wave";

fn main() -> gtk::glib::ExitCode {
    let app = adw::Application::builder().application_id(APP_ID).build();
    app.connect_activate(build_window);
    app.run()
}

/// 通知を押したとき（新作のアルバム・アーティストを開く）。
fn add_open_actions(app: &adw::Application) {
    for (name, album) in [("open-album", true), ("open-artist", false)] {
        let action = gtk::gio::SimpleAction::new(name, Some(gtk::glib::VariantTy::STRING));
        action.connect_activate(move |_, parameter| {
            let Some(id) = parameter.and_then(|p| p.get::<String>()) else { return };
            let Some(app) = APP.with(|a| a.borrow().clone()) else { return };
            if let Some(window) = app.window() {
                window.present();
            }
            if album {
                app.push(ui::pages::album(&app, &id, "アルバム"));
            } else {
                app.push(ui::pages::artist(&app, &id, "アーティスト"));
            }
        });
        app.add_action(&action);
    }
}

fn build_window(app: &adw::Application) {
    add_open_actions(app);
    // ランチャー・Alt+Tab のアイコン（scripts/install.sh で入れた絵）
    gtk::Window::set_default_icon_name(APP_ID);
    let repo = MusicRepository::new(InnerTube::new(&language(), &region()));
    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&adw::HeaderBar::new());
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title("wave")
        .default_width(1280)
        .default_height(800)
        .content(&toolbar)
        .build();
    toolbar.set_content(Some(&loading_page()));
    window.present();
    let (window, toolbar) = (window.clone(), toolbar.clone());
    gtk::glib::spawn_future_local(async move {
        let saved = keyring::load().await;
        let logged_in = saved.is_some();
        repo.client().set_credentials(saved);
        if logged_in || std::env::var_os("WAVE_SKIP_LOGIN").is_some() {
            show_home(&toolbar, &repo).await;
        } else {
            show_login(&window, &toolbar, &repo);
        }
        snapshot_if_asked(&window);
    });
}

fn loading_page() -> gtk::Widget {
    adw::Spinner::builder().width_request(48).height_request(48).halign(gtk::Align::Center).valign(gtk::Align::Center).build().upcast()
}

/// ログインしていないときの入り口。押すとログインの窓を開き、済んだらホームを出す。
fn show_login(window: &adw::ApplicationWindow, toolbar: &adw::ToolbarView, repo: &MusicRepository) {
    let button = gtk::Button::builder().label("ログイン").halign(gtk::Align::Center).css_classes(["suggested-action", "pill"]).build();
    let page = adw::StatusPage::builder()
        .icon_name("audio-x-generic-symbolic")
        .title("wave")
        .description("YouTube Music のアカウントでログインしてください")
        .child(&button)
        .build();
    toolbar.set_content(Some(&page));
    let (window, toolbar, repo) = (window.clone(), toolbar.clone(), repo.clone());
    button.connect_clicked(move |_| {
        let (toolbar, repo) = (toolbar.clone(), repo.clone());
        login::open(&window, move |credentials| {
            let Some(credentials) = credentials else { return };
            repo.client().set_credentials(Some(credentials));
            let (toolbar, repo) = (toolbar.clone(), repo.clone());
            gtk::glib::spawn_future_local(async move { show_home(&toolbar, &repo).await });
        });
    });
}

/// ログインできたら、Mac・Windows と同じ並びの画面に入れ替える（上の帯も画面の側が持つ）。
async fn show_home(toolbar: &adw::ToolbarView, repo: &MusicRepository) {
    // 解読の準備（プレイヤーの JS）は音と映像で1つを共有する
    let resolver = StreamResolver::new(repo.client().clone(), PlayerCipherProvider::default());
    let video = video::VideoPlayer::new(resolver.clone());
    let downloads = downloads::Downloads::new(resolver.clone(), repo.clone());
    let playback = playback::Playback::new(player::Player::new(resolver, Some(downloads.store())).with_live(repo.live().clone()), repo.clone());
    // 聴いた曲を数える（スマートダウンロードの「いつも聴いている」に使う）
    downloads.track_listens(&playback);
    // 前回のキューを戻し、以後は変わるたびに書く（同じログインのときだけ）
    queue_store::attach(&playback, repo.client().obtained_at());
    let on_logout: std::rc::Rc<dyn Fn()> = {
        let (toolbar, repo, playback, downloads) = (toolbar.clone(), repo.clone(), playback.clone(), downloads.clone());
        std::rc::Rc::new(move || {
            playback.clear();
            queue_store::clear();
            // ログアウトしたら、取った曲と Claude との会話も消す（ほかの端末と同じ）
            downloads.clear();
            if let Some(app) = APP.with(|a| a.borrow().clone()) {
                app.ai.wipe();
                app.releases.clear();
            }
            let (toolbar, repo) = (toolbar.clone(), repo.clone());
            gtk::glib::spawn_future_local(async move {
                keyring::delete().await;
                repo.client().set_credentials(None);
                // 入れ物（読み込み中の上の帯）に戻し、ログインの入り口を出す
                if let Some(window) = toolbar_window(&toolbar) {
                    window.set_content(Some(&toolbar));
                    show_login(&window, &toolbar, &repo);
                }
            });
        })
    };
    let window = toolbar.root().and_downcast::<adw::ApplicationWindow>();
    if let Some(window) = &window {
        WINDOW.with(|w| *w.borrow_mut() = Some(window.clone()));
    }
    let lyrics = wave_core::lyrics::service::LyricsService::new(
        repo.clone(),
        wave_core::lyrics::lrclib::LrclibClient::default(),
        wave_core::lyrics::timed::TimedLyricsClient::new(&language(), &region()),
    );
    // 端末の連携（中継の場所が設定にあるときだけ）
    let relay_url = downloads.settings.borrow().relay_url.clone();
    let connect = connect::Connect::start(repo.clone(), playback.clone(), relay_url).await;
    let releases = releases::Releases::start(repo.clone(), downloads.settings.borrow().release_notices);
    let (shell, app) = ui::shell::build(repo.clone(), lyrics, playback.clone(), video, downloads, connect, releases, on_logout);
    if let Some(window) = toolbar.root().and_downcast::<adw::ApplicationWindow>() {
        // GNOME のメディアの操作・メディアキーから操作できるようにする（窓がある間だけ出す）
        let (raise, quit) = (window.clone(), window.clone());
        // ログアウトして入り直したら、古い方を外してから置き換える
        MPRIS.with(|m| *m.borrow_mut() = None);
        let started = mpris::start(playback, move || raise.present(), move || quit.close());
        MPRIS.with(|m| *m.borrow_mut() = Some(started));
    }
    APP.with(|a| *a.borrow_mut() = Some(app.clone()));
    if let Ok(query) = std::env::var("WAVE_DEMO_QUERY") {
        app.demo(&query);
    }
    // 確かめ用: WAVE_DEMO_MOOD=moods で見つける、lofi で Lo-Fi のページ、ほかはその params の気分・ジャンルのページを開く
    match std::env::var("WAVE_DEMO_MOOD").as_deref() {
        Ok("moods") => app.push(ui::pages::moods(&app)),
        Ok("lofi") => app.push(ui::pages::mood_category(&app, "Lo-Fi", wave_core::music::lofi::PARAMS)),
        Ok(params) => app.push(ui::pages::mood_category(&app, "気分・ジャンル", params)),
        Err(_) => {}
    }
    // 入れ物（読み込み中の上の帯）ごと、画面の上の帯を持つ並びに替える
    if let Some(window) = toolbar.root().and_downcast::<adw::ApplicationWindow>() {
        window.set_content(Some(&shell));
    }
}

thread_local! {
    /// 窓（ログアウトして入れ物に戻すとき、入れ物はもう窓に付いていないので覚えておく）。
    static WINDOW: std::cell::RefCell<Option<adw::ApplicationWindow>> = const { std::cell::RefCell::new(None) };
    /// 今の画面の中身（ログアウトのときに Claude の履歴を消すのに使う）。
    static APP: std::cell::RefCell<Option<std::rc::Rc<ui::shell::App>>> = const { std::cell::RefCell::new(None) };
    /// GNOME のメディアの操作への口（1つだけ）。
    static MPRIS: std::cell::RefCell<Option<std::rc::Rc<mpris::Mpris>>> = const { std::cell::RefCell::new(None) };
}

fn toolbar_window(toolbar: &adw::ToolbarView) -> Option<adw::ApplicationWindow> {
    toolbar.root().and_downcast::<adw::ApplicationWindow>().or_else(|| WINDOW.with(|w| w.borrow().clone()))
}

/// 表示の言語（YouTube から届く文字の言語）。LANG が日本語なら ja、ほかは en。
fn language() -> String {
    let lang = std::env::var("LANG").unwrap_or_default();
    if lang.starts_with("ja") { "ja".into() } else { "en".into() }
}

/// 地域。LANG の地域の部分（ja_JP.UTF-8 なら JP）。読めなければ JP。
fn region() -> String {
    std::env::var("LANG")
        .ok()
        .and_then(|l| l.split(['_', '.']).nth(1).map(str::to_uppercase))
        .filter(|r| r.len() == 2)
        .unwrap_or_else(|| "JP".into())
}

/// 確かめ用: 環境変数 WAVE_SNAPSHOT に PNG の行き先があれば、描き終わるのを待って窓を書き出し、アプリを閉じる
/// （scripts/shot.sh が見えない画面の mutter の中で使う。作業中の画面に窓を出さずに見た目を確かめる）。
fn snapshot_if_asked(window: &adw::ApplicationWindow) {
    let Some(path) = std::env::var_os("WAVE_SNAPSHOT") else { return };
    let delay = std::env::var("WAVE_SNAPSHOT_DELAY_MS").ok().and_then(|v| v.parse().ok()).unwrap_or(1500);
    let window = window.clone();
    gtk::glib::timeout_add_local_once(std::time::Duration::from_millis(delay), move || {
        let tries = std::cell::Cell::new(0);
        // 描き直しの最中だと絵が空のことがある（映像を流しているときなど）。0.5 秒あけて取り直す
        gtk::glib::timeout_add_local(std::time::Duration::from_millis(500), move || {
            let paintable = gtk::WidgetPaintable::new(Some(&window));
            let (width, height) = (window.width() as f64, window.height() as f64);
            let snapshot = gtk::Snapshot::new();
            paintable.snapshot(&snapshot, width, height);
            // 窓の絵が空なら、窓の中身を直接描かせる（映像の絵を含むと WidgetPaintable が空を返すことがあった）
            let node = snapshot.to_node().or_else(|| {
                let direct = gtk::Snapshot::new();
                let mut child = window.first_child();
                while let Some(widget) = child {
                    window.snapshot_child(&widget, &direct);
                    child = widget.next_sibling();
                }
                direct.to_node()
            });
            match (node, window.native().and_then(|n| n.renderer())) {
                (Some(node), Some(renderer)) => {
                    let texture = renderer.render_texture(&node, None);
                    if let Err(e) = texture.save_to_png(&path) {
                        eprintln!("snapshot failed: {e}");
                    }
                }
                (node, renderer) => {
                    tries.set(tries.get() + 1);
                    if tries.get() < 10 {
                        return gtk::glib::ControlFlow::Continue;
                    }
                    eprintln!("snapshot failed: node={} renderer={} mapped={} visible={} size={}x{}", node.is_some(), renderer.is_some(), window.is_mapped(), window.is_visible(), window.width(), window.height());
                }
            }
            if let Some(app) = window.application() {
                app.quit();
            }
            gtk::glib::ControlFlow::Break
        });
    });
}
