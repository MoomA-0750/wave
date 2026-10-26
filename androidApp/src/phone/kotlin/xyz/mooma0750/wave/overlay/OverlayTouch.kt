package xyz.mooma0750.wave.overlay

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * 重ね表示の指の動きを、タップの回数・長押し・ドラッグに分ける。窓ごと動かすので、位置は画面の座標（raw）で見る
 * （窓の中の座標は、窓が指に付いて動くと変わらなくなる）。
 * タップは続けて押された回数を数え、[maxTaps] に届くか、間が空いたところで [onTaps] に渡す。
 */
internal class OverlayTouch(
    private val slop: Int,
    private val maxTaps: Int,
    private val onTaps: (Int) -> Unit,
    private val onLongPress: (() -> Unit)?,
    private val onDragStart: () -> Unit,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDragEnd: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var taps = 0
    private val longPress =
        Runnable {
            longPressed = true
            taps = 0
            onLongPress?.invoke()
        }
    private val flushTaps =
        Runnable {
            val count = taps
            taps = 0
            if (count > 0) onTaps(count)
        }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                longPressed = false
                handler.removeCallbacks(flushTaps)
                if (onLongPress != null) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && !longPressed && dx * dx + dy * dy > slop.toFloat() * slop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                    // ドラッグに入ったら、その前のタップは数えない
                    taps = 0
                    onDragStart()
                }
                if (dragging) onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                when {
                    dragging -> onDragEnd()
                    longPressed -> Unit
                    else -> {
                        taps += 1
                        if (taps >= maxTaps) {
                            flushTaps.run()
                        } else {
                            handler.postDelayed(flushTaps, ViewConfiguration.getDoubleTapTimeout().toLong())
                        }
                    }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging) onDragEnd()
                dragging = false
            }
        }
        return true
    }

    fun cancel() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(flushTaps)
        taps = 0
    }
}
