#pragma once
// MV の絵を描く QML の部品（VideoFrame）。GStreamer の appsink が受け取った RGBA の絵を、どの糸からでも
// wave_video_frame で渡すと、主の糸で描き直す。QQuickPaintedItem なので、software の描き方でも GPU でも描ける。

#include <QtCore/QMutex>
#include <QtGui/QImage>
#include <QtQuick/QQuickPaintedItem>
#include "rust/cxx.h"

class WaveVideoItem : public QQuickPaintedItem
{
    Q_OBJECT
    Q_PROPERTY(bool hasFrame READ hasFrame NOTIFY hasFrameChanged)
public:
    explicit WaveVideoItem(QQuickItem* parent = nullptr);
    ~WaveVideoItem() override;
    void paint(QPainter* painter) override;
    bool hasFrame() const;
Q_SIGNALS:
    void hasFrameChanged();
public Q_SLOTS:
    void frameArrived();
};

// QML に VideoFrame（wave.video 1.0）を登録する。
void wave_register_video_item();
// 絵を1枚渡す（RGBA、stride は1行のバイト数）。どの糸から呼んでもよい。
void wave_video_frame(rust::Slice<const uint8_t> data, int32_t width, int32_t height, int32_t stride);
// 絵を消す（映像を止めたとき）。
void wave_video_clear();
