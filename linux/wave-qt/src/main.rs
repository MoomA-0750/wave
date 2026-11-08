//! wave の Linux 版の QML の画面（Qt Quick ＋ Material）。Omarchy（Hyprland）など GNOME でない机で使う。
//! 中身は GTK 版と同じ wave-desktop。Rust の中身は cxx-qt で QML に渡す（WaveApp・Theme）。
//! 起動の入口は選び役（scripts/wave）で、WAVE_UI と XDG_CURRENT_DESKTOP でこちらか GTK 版かを選ぶ。

mod app;
mod bridge;
mod extra;
mod more;
mod theme;
mod view;

use cxx_qt_lib::{QGuiApplication, QQmlApplicationEngine, QQuickStyle, QString, QUrl};

fn main() {
    let mut app = QGuiApplication::new();
    bridge::qobject::wave_setup_application();
    // MV の絵を描く部品（VideoFrame）
    bridge::qobject::wave_register_video_item();
    // 部品は Material（Omarchy のテーマの色と丸みは Theme が QML に渡す）
    QQuickStyle::set_style(&QString::from("Material"));
    let mut engine = QQmlApplicationEngine::new();
    if let Some(engine) = engine.as_mut() {
        engine.load(&QUrl::from("qrc:/qt/qml/wave/qml/Main.qml"));
    }
    // 確かめ用: WAVE_SNAPSHOT に PNG の行き先があれば、WAVE_SNAPSHOT_DELAY_MS（既定 4000）後に窓を書き出して閉じる
    if let Some(path) = std::env::var_os("WAVE_SNAPSHOT") {
        let delay = std::env::var("WAVE_SNAPSHOT_DELAY_MS").ok().and_then(|v| v.parse().ok()).unwrap_or(4000);
        if let Some(engine) = engine.as_mut() {
            bridge::qobject::wave_snapshot_later(engine, &QString::from(&path.to_string_lossy().to_string()), delay);
        }
    }
    if let Some(app) = app.as_mut() {
        app.exec();
    }
}
