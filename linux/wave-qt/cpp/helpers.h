#pragma once
// QML 版の小さな手伝い（Qt の C++ の API を Rust から呼ぶ）。

#include <QtCore/QString>
#include <QtQml/QQmlApplicationEngine>

// アプリの名前・窓の app id（GTK 版と同じ xyz.mooma0750.wave。アイコンを共有する）・アイコンのテーマを決める。
void wave_setup_application();
// 窓が前面にあるか（端末の連携が、裏でメディアキーを押したときにほかの端末の再生を黙って引き取らないのに使う）。
bool wave_window_active();
// アプリを閉じる（MPRIS の Quit）。
void wave_quit();
// 確かめ用: delay_ms 後に主の窓を QQuickWindow::grabWindow で PNG に書き出し、アプリを閉じる（画面に窓を出さずに見た目を確かめる）。
void wave_snapshot_later(QQmlApplicationEngine& engine, const QString& path, int delay_ms);
