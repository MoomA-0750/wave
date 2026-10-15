package xyz.mooma0750.wave.nowplaying

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 映像の PiP（小窓）。小窓には Activity の画面がそのまま縮んで出るので、小窓の間は再生画面が映像だけを出す。
 * 再生画面で映像を出したままホームへ戻ると、自動で小窓にする（Spotify・YouTube と同じ）。
 */
object VideoPip {
    /** 再生画面で「曲｜動画」の動画を選んでいるか（右の再生の列と全面の再生画面で同じものを使い、小窓から戻ったときも続ける）。 */
    var selected by mutableStateOf(false)

    private val inPipFlow = MutableStateFlow(false)

    /** 小窓で映しているか。 */
    val inPip: StateFlow<Boolean> = inPipFlow.asStateFlow()

    private val showingOwners = mutableSetOf<Any>()
    private val showingFlow = MutableStateFlow(false)

    /** 再生画面で映像を出しているか（出していれば、ホームへ戻ったときに小窓にする）。 */
    val showing: StateFlow<Boolean> = showingFlow.asStateFlow()

    /** 再生画面（右の列と全面の2つあることがある）ごとに、映像を出しているかを知らせる。 */
    fun setShowing(owner: Any, on: Boolean) {
        if (on) showingOwners += owner else showingOwners -= owner
        showingFlow.value = showingOwners.isNotEmpty()
    }

    fun onModeChanged(inPip: Boolean) {
        inPipFlow.value = inPip
    }

    fun params(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(showingFlow.value)
                    setSeamlessResizeEnabled(true)
                }
            }.build()

    /** 小窓にする（再生画面の小窓のボタン）。 */
    fun enter(activity: Activity?) {
        activity ?: return
        runCatching { activity.enterPictureInPictureMode(params()) }
            .onFailure { android.util.Log.w(xyz.mooma0750.wave.WaveApplication.LOG_TAG, "video pip failed", it) }
    }
}
