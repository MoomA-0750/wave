#include "videoitem.h"

#include <QtCore/QMetaObject>
#include <QtCore/QSet>
#include <QtGui/QPainter>
#include <QtQml/qqml.h>

namespace {
QMutex frameLock;
QImage latest;
QSet<WaveVideoItem*> items;

QImage currentFrame()
{
    QMutexLocker locker(&frameLock);
    return latest;
}

void notifyItems()
{
    QMutexLocker locker(&frameLock);
    for (WaveVideoItem* item : std::as_const(items))
        QMetaObject::invokeMethod(item, "frameArrived", Qt::QueuedConnection);
}
}

WaveVideoItem::WaveVideoItem(QQuickItem* parent)
    : QQuickPaintedItem(parent)
{
    QMutexLocker locker(&frameLock);
    items.insert(this);
}

WaveVideoItem::~WaveVideoItem()
{
    QMutexLocker locker(&frameLock);
    items.remove(this);
}

bool WaveVideoItem::hasFrame() const
{
    return !currentFrame().isNull();
}

void WaveVideoItem::frameArrived()
{
    update();
    Q_EMIT hasFrameChanged();
}

void WaveVideoItem::paint(QPainter* painter)
{
    const QImage frame = currentFrame();
    painter->fillRect(boundingRect(), Qt::black);
    if (frame.isNull())
        return;
    // 縦横の比を保って、枠の中に収める
    const QSizeF size = QSizeF(frame.size()).scaled(boundingRect().size(), Qt::KeepAspectRatio);
    const QRectF target((width() - size.width()) / 2, (height() - size.height()) / 2, size.width(), size.height());
    painter->setRenderHint(QPainter::SmoothPixmapTransform);
    painter->drawImage(target, frame);
}

void wave_register_video_item()
{
    qmlRegisterType<WaveVideoItem>("wave.video", 1, 0, "VideoFrame");
}

void wave_video_frame(rust::Slice<const uint8_t> data, int32_t width, int32_t height, int32_t stride)
{
    if (width <= 0 || height <= 0 || stride < width * 4 || data.size() < size_t(stride) * size_t(height))
        return;
    QImage image(data.data(), width, height, stride, QImage::Format_RGBA8888);
    {
        QMutexLocker locker(&frameLock);
        latest = image.copy();
    }
    notifyItems();
}

void wave_video_clear()
{
    {
        QMutexLocker locker(&frameLock);
        latest = QImage();
    }
    notifyItems();
}
