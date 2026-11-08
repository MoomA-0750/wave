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
        QQuickWindow* main = main_window(*e);
        if (!main) {
            qWarning("snapshot failed: no window");
        } else {
            const QImage image = main->grabWindow();
            if (image.isNull() || !image.save(path))
                qWarning("snapshot failed: %s", qPrintable(path));
        }
        // ほかに開いている窓（映像の小窓など）は「名前-2.png」…に書き出す
        int n = 2;
        const auto windows = QGuiApplication::topLevelWindows();
        for (QWindow* w : windows) {
            auto quick = qobject_cast<QQuickWindow*>(w);
            if (!quick || quick == main || !quick->isVisible())
                continue;
            QString other = path;
            other.replace(QStringLiteral(".png"), QStringLiteral("-%1.png").arg(n++));
            const QImage image = quick->grabWindow();
            if (image.isNull() || !image.save(other))
                qWarning("snapshot failed: %s", qPrintable(other));
        }
        QGuiApplication::quit();
    });
}
