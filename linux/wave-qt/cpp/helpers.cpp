#include "helpers.h"

#include <QtCore/QTimer>
#include <QtGui/QGuiApplication>
#include <QtGui/QIcon>
#include <QtGui/QImage>
#include <QtGui/QWindow>
#include <QtQuick/QQuickWindow>

void wave_setup_application()
{
    QGuiApplication::setApplicationName(QStringLiteral("wave"));
    QGuiApplication::setApplicationDisplayName(QStringLiteral("wave"));
    QGuiApplication::setDesktopFileName(QStringLiteral("xyz.mooma0750.wave"));
    // アイコンのテーマが決まっていない環境（Qt のテーマの手伝いが無いとき）でも、GTK 版と同じ絵を引けるようにする
    if (QIcon::themeName().isEmpty() || QIcon::themeName() == QStringLiteral("hicolor"))
        QIcon::setThemeName(QStringLiteral("Adwaita"));
    QIcon::setFallbackThemeName(QStringLiteral("Adwaita"));
    QGuiApplication::setWindowIcon(QIcon::fromTheme(QStringLiteral("xyz.mooma0750.wave")));
}

bool wave_window_active()
{
    return QGuiApplication::focusWindow() != nullptr;
}

static QQuickWindow* main_window(QQmlApplicationEngine& engine)
{
    const auto roots = engine.rootObjects();
    for (QObject* root : roots) {
        if (auto window = qobject_cast<QQuickWindow*>(root))
            return window;
    }
    return nullptr;
}

void wave_quit()
{
    QGuiApplication::quit();
}

void wave_snapshot_later(QQmlApplicationEngine& engine, const QString& path, int delay_ms)
{
    QQmlApplicationEngine* e = &engine;
    QTimer::singleShot(delay_ms, [e, path]() {
        if (auto window = main_window(*e)) {
            const QImage image = window->grabWindow();
            if (image.isNull() || !image.save(path))
                qWarning("snapshot failed: %s", qPrintable(path));
        } else {
            qWarning("snapshot failed: no window");
        }
        QGuiApplication::quit();
    });
}
