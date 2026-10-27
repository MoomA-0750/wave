//! wave の Linux（GNOME）版。画面は GTK4 + libadwaita で作り、YouTube Music とのやり取りは wave-core に任せる。

use adw::prelude::*;

const APP_ID: &str = "xyz.mooma0750.wave";

fn main() -> gtk::glib::ExitCode {
    let app = adw::Application::builder().application_id(APP_ID).build();
    app.connect_activate(build_window);
    app.run()
}

fn build_window(app: &adw::Application) {
    let status = adw::StatusPage::builder()
        .icon_name("audio-x-generic-symbolic")
        .title("wave")
        .description(format!("core {}", wave_core::VERSION))
        .build();
    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&adw::HeaderBar::new());
    toolbar.set_content(Some(&status));
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title("wave")
        .default_width(1280)
        .default_height(800)
        .content(&toolbar)
        .build();
    window.present();
    snapshot_if_asked(&window);
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
        window.application().map(|app| app.quit());
    });
}
