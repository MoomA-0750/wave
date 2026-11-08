//! wave の Linux（GNOME）版。画面は GTK4 + libadwaita で作り、YouTube Music とのやり取りは wave-core に任せる。

mod login;
mod ui;
mod video;

// 画面に依らない部分は wave-desktop（QML 版と共有）。crate::playback などの名前のまま使う
use wave_desktop::{ai_chat, browser_login, connect, discord_status, downloads, keyring, mpris, playback, runtime, settings};

use adw::prelude::*;
use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

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
    // 窓が前面にあるか（端末の連携が、裏でメディアキーを押したときにほかの端末の再生を黙って引き取らないのに使う）
    connect::set_foreground(|| {
        gtk::gio::Application::default()
            .and_then(|app| app.downcast::<gtk::Application>().ok())
            .and_then(|app| app.active_window())
            .is_some_and(|w| w.is_active())
    });
    // ランチャー・Alt+Tab のアイコン（scripts/install.sh で入れた絵）
    gtk::Window::set_default_icon_name(APP_ID);
    let repo = MusicRepository::new(InnerTube::new(&language(), &region()));
    // 曲名を元の表記で（設定）。最初の読み取りから効かせる
    repo.client().set_original_titles(settings::Settings::load().original_titles);
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
    // ふだんのブラウザでログインしてあれば、それを読んで使う（アプリの中の窓でログインしなくて済む）
    let from_browser = gtk::Button::builder().label("ブラウザのログインを使う").halign(gtk::Align::Center).css_classes(["pill"]).build();
    let buttons = gtk::Box::new(gtk::Orientation::Vertical, 12);
    buttons.append(&button);
    buttons.append(&from_browser);
    let page = adw::StatusPage::builder()
        .icon_name("audio-x-generic-symbolic")
        .title("wave")
        .description("YouTube Music のアカウントでログインしてください")
        .child(&buttons)
        .build();
    toolbar.set_content(Some(&page));
    {
        let (window, toolbar, repo) = (window.clone(), toolbar.clone(), repo.clone());
        from_browser.connect_clicked(move |b| {
            let (b, window, toolbar, repo) = (b.clone(), window.clone(), toolbar.clone(), repo.clone());
            gtk::glib::spawn_future_local(async move {
                b.set_sensitive(false);
                let ok = login_from_browser(&window, &repo).await;
                b.set_sensitive(true);
                if ok {
                    show_home(&toolbar, &repo).await;
                }
            });
        });
    }
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

/// ブラウザに残っているログインを選んで読み、キーリングに保存する。使えるようになったら true。
async fn login_from_browser(window: &adw::ApplicationWindow, repo: &MusicRepository) -> bool {
    let home = gtk::glib::home_dir();
    let profiles = gtk::gio::spawn_blocking(move || browser_login::find(&home)).await.unwrap_or_default();
    if profiles.is_empty() {
        let dialog = adw::AlertDialog::new(
            Some("ブラウザにログインが見つかりません"),
            Some("ふだんのブラウザで music.youtube.com にログインしてから、もう一度「ブラウザのログインを使う」を押してください。\n\n読めるブラウザ: Firefox・Zen・LibreWolf・Floorp・Waterfox・Chrome・Chromium・Brave・Edge・Vivaldi・Opera"),
        );
        dialog.add_responses(&[("close", "閉じる"), ("open", "ブラウザで開く")]);
        dialog.set_response_appearance("open", adw::ResponseAppearance::Suggested);
        dialog.set_default_response(Some("open"));
        if dialog.choose_future(Some(window)).await == "open" {
            gtk::UriLauncher::new("https://music.youtube.com/").launch(Some(window), None::<&gtk::gio::Cancellable>, |_| {});
        }
        return false;
    }
    let dialog = adw::AlertDialog::new(
        Some("どのブラウザのログインを使いますか"),
        Some("選んだブラウザの YouTube の cookie を読んで、この PC のキーリングに保存します。ブラウザの側のログインはそのまま残ります。"),
    );
    dialog.add_response("cancel", "やめる");
    for (i, profile) in profiles.iter().enumerate() {
        dialog.add_response(&format!("p{i}"), &profile.label);
    }
    dialog.set_response_appearance("p0", adw::ResponseAppearance::Suggested);
    dialog.set_default_response(Some("p0"));
    dialog.set_close_response("cancel");
    let chosen = dialog.choose_future(Some(window)).await;
    let Some(profile) = chosen.strip_prefix('p').and_then(|i| i.parse::<usize>().ok()).and_then(|i| profiles.get(i)) else { return false };
    let result = async {
        let cookie = browser_login::read_cookie(profile).await?;
        let (visitor, data_sync) = browser_login::page_config(cookie.clone()).await?;
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64);
        let credentials = wave_core::credentials::Credentials::from_login(&cookie, visitor.as_deref(), data_sync.as_deref(), now)
            .ok_or_else(|| "ログインの cookie が見つかりませんでした".to_string())?;
        eprintln!("browser login visitor={} dataSync={}", visitor.is_some(), data_sync.is_some());
        if !keyring::save(&credentials).await {
            return Err("ログイン情報を保存できませんでした".to_string());
        }
        Ok(credentials)
    }
    .await;
    match result {
        Ok(credentials) => {
            repo.client().set_credentials(Some(credentials));
            true
        }
        Err(message) => {
            let dialog = adw::AlertDialog::new(Some(&format!("{}のログインを使えませんでした", profile.label)), Some(&message));
            dialog.add_response("close", "閉じる");
            dialog.choose_future(Some(window)).await;
            false
        }
    }
}

/// ログインできたら、Mac・Windows と同じ並びの画面に入れ替える（上の帯も画面の側が持つ）。
async fn show_home(toolbar: &adw::ToolbarView, repo: &MusicRepository) {
    let services = wave_desktop::services::Services::start(repo.clone()).await;
    // 映像（MV）の流し手は GTK の絵（gtk4paintablesink）を使うので、画面の側で作る。解読の準備は音と共有する
    let video = video::VideoPlayer::new(services.resolver.clone());
    let playback = services.playback.clone();
    let on_logout: std::rc::Rc<dyn Fn()> = {
        let (toolbar, repo, services) = (toolbar.clone(), repo.clone(), std::rc::Rc::downgrade(&services));
        std::rc::Rc::new(move || {
            let Some(services) = services.upgrade() else { return };
            let (toolbar, repo) = (toolbar.clone(), repo.clone());
            gtk::glib::spawn_future_local(async move {
                // キュー・取った曲・Claude との会話などを消し、キーリングのログイン情報も消す（wave-desktop の Services）
                services.log_out().await;
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
    // 設定の「ログアウト」からも同じ後片付けを呼べるようにする
    LOGOUT.with(|l| *l.borrow_mut() = Some(on_logout.clone()));
    let (shell, app) = ui::shell::build(services.clone(), video, on_logout);
    if let Some(window) = toolbar.root().and_downcast::<adw::ApplicationWindow>() {
        // GNOME のメディアの操作・メディアキーから操作できるようにする（窓がある間だけ出す）
        let (raise, quit) = (window.clone(), window.clone());
        // ログアウトして入り直したら、古い方を外してから置き換える
        MPRIS.with(|m| *m.borrow_mut() = None);
        let started = mpris::start(playback, move || raise.present(), move || quit.close());
        MPRIS.with(|m| *m.borrow_mut() = Some(started));
    }
    APP.with(|a| *a.borrow_mut() = Some(app.clone()));
    discord_status::start(&services);
    if let Ok(query) = std::env::var("WAVE_DEMO_QUERY") {
        app.demo(&query);
    }
    // 確かめ用: WAVE_DEMO_PODCAST に番組の browseId（MPSP… か rss:…）、podcasts ならマイライブラリのポッドキャストを開く
    match std::env::var("WAVE_DEMO_PODCAST").as_deref() {
        Ok("podcasts") => app.push(ui::podcasts::library(&app)),
        Ok(id) => app.push(ui::podcasts::show(&app, id, "ポッドキャスト")),
        Err(_) => {}
    }
    // 確かめ用: WAVE_DEMO_PLAYLIST にプレイリストの ID（LM は高く評価した曲）があれば開く
    if let Ok(id) = std::env::var("WAVE_DEMO_PLAYLIST") {
        app.push(ui::pages::playlist(&app, &id, "プレイリスト"));
    }
    // 確かめ用: WAVE_DEMO_MOOD=moods で見つける、lofi で Lo-Fi のページ、ほかはその params の気分・ジャンルのページを開く
    match std::env::var("WAVE_DEMO_MOOD").as_deref() {
        Ok("moods") => app.push(ui::pages::moods(&app)),
        Ok("lofi") => app.push(ui::pages::mood_category(&app, "Lo-Fi", wave_core::music::lofi::PARAMS)),
        Ok(params) => app.push(ui::pages::mood_category(&app, "気分・ジャンル", params)),
        Err(_) => {}
    }
    // 確かめ用: WAVE_DEMO_DEVICES があれば端末の連携のページを開く。offer・code・wrong・show・working・done・done-api・failed なら、
    // 中継につながずに加入の手順のその様子を出す（家の記録は変えない）
    if let Ok(state) = std::env::var("WAVE_DEMO_DEVICES") {
        use connect::PairingView;
        let view = match state.as_str() {
            "offer" => Some(PairingView::ShowingOffer("wave-pair:eyJ2IjoxLCJwa0EiOiJkZW1vIiwiZUEiOiJkZW1vIiwidG9rZW4iOiJkZW1vIn0".into())),
            "code" => Some(PairingView::EnterCode(None)),
            "wrong" => Some(PairingView::EnterCode(Some("数字が違います".into()))),
            "show" => Some(PairingView::ShowCode { code: "123456".into(), peer: "Pixel 3".into() }),
            "working" => Some(PairingView::Working("相手が加えるのを待っています…".into())),
            "done" => Some(PairingView::Done { peer: "Pixel 3".into(), grant_device_id: None }),
            "done-api" => Some(PairingView::Done { peer: "wave API".into(), grant_device_id: Some("demo".into()) }),
            "failed" => Some(PairingView::Failed("時間内に読み取られませんでした".into())),
            _ => None,
        };
        if let Some(view) = view {
            app.connect.show_demo_pairing(view);
        }
        ui::devices::open_page(&app);
    }
    // 確かめ用: WAVE_DEMO_PAGE=settings で設定のページを開く
    if std::env::var("WAVE_DEMO_PAGE").as_deref() == Ok("settings") {
        app.push(ui::settings::page(&app));
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
    /// ログアウトの後片付け（右上のメニューと設定の「ログアウト」から呼ぶ）。
    static LOGOUT: std::cell::RefCell<Option<std::rc::Rc<dyn Fn()>>> = const { std::cell::RefCell::new(None) };
    /// GNOME のメディアの操作への口（1つだけ）。
    static MPRIS: std::cell::RefCell<Option<std::rc::Rc<mpris::Mpris>>> = const { std::cell::RefCell::new(None) };
}

/// ログアウトする（キーリングのログイン情報・取った曲・キュー・Claude との会話を消し、ログインの入り口に戻る）。
pub fn log_out() {
    if let Some(logout) = LOGOUT.with(|l| l.borrow().clone()) {
        logout();
    }
}

fn toolbar_window(toolbar: &adw::ToolbarView) -> Option<adw::ApplicationWindow> {
    toolbar.root().and_downcast::<adw::ApplicationWindow>().or_else(|| WINDOW.with(|w| w.borrow().clone()))
}

use wave_desktop::services::{language, region};

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
