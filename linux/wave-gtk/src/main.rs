//! wave の Linux（GNOME）版。画面は GTK4 + libadwaita で作り、YouTube Music とのやり取りは wave-core に任せる。

mod keyring;
mod login;
mod player;
mod runtime;

use adw::prelude::*;
use wave_core::innertube::InnerTube;
use wave_core::music::repository::MusicRepository;

const APP_ID: &str = "xyz.mooma0750.wave";

fn main() -> gtk::glib::ExitCode {
    let app = adw::Application::builder().application_id(APP_ID).build();
    app.connect_activate(build_window);
    app.run()
}

fn build_window(app: &adw::Application) {
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

/// 仮のホーム: 棚の見出しと中身の名前を並べる（並びは後で Mac・Windows と同じにする）。
async fn show_home(toolbar: &adw::ToolbarView, repo: &MusicRepository) {
    toolbar.set_content(Some(&loading_page()));
    let core = repo.clone();
    let result = runtime::run(async move { core.home(None, None).await }).await;
    let list = gtk::Box::new(gtk::Orientation::Vertical, 12);
    list.set_margin_top(24);
    list.set_margin_bottom(24);
    list.set_margin_start(24);
    list.set_margin_end(24);
    match result {
        Ok(page) => {
            for shelf in page.shelves {
                list.append(&gtk::Label::builder().label(&shelf.title).xalign(0.0).css_classes(["title-2"]).build());
                let names: Vec<&str> = shelf.items.iter().map(|i| i.title()).collect();
                list.append(&gtk::Label::builder().label(names.join(" ・ ")).xalign(0.0).wrap(true).build());
            }
        }
        Err(e) => list.append(&gtk::Label::new(Some(&format!("ホームを読めませんでした（{e}）")))),
    }
    let scroller = gtk::ScrolledWindow::builder().child(&list).build();
    toolbar.set_content(Some(&scroller));
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
        let paintable = gtk::WidgetPaintable::new(Some(&window));
        let (width, height) = (window.width() as f64, window.height() as f64);
        let snapshot = gtk::Snapshot::new();
        paintable.snapshot(&snapshot, width, height);
        if let (Some(node), Some(renderer)) = (snapshot.to_node(), window.native().and_then(|n| n.renderer())) {
            let texture = renderer.render_texture(&node, None);
            if let Err(e) = texture.save_to_png(&path) {
                eprintln!("snapshot failed: {e}");
            }
        }
        if let Some(app) = window.application() {
            app.quit();
        }
    });
}
