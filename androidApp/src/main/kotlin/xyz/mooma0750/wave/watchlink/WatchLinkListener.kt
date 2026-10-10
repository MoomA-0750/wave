package xyz.mooma0750.wave.watchlink

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import xyz.mooma0750.wave.WaveApplication

/** スマホと時計の間のメッセージの入口（届くのは同じパッケージ・同じ署名の wave からだけ）。 */
class WatchLinkListener : WearableListenerService() {
    private companion object {
        const val REMOTE_PREFIX = "/wave/remote/"
    }

    override fun onMessageReceived(event: MessageEvent) {
        val container = (application as WaveApplication).container
        when (event.path) {
            WatchLogin.PATH_REQUEST -> container.watchLogin.onRequest(event.sourceNodeId, event.data)
            WatchLogin.PATH_CREDENTIALS -> container.watchLogin.onCredentials(event.data)
            else -> if (event.path.startsWith(REMOTE_PREFIX)) container.watchRemote.onMessage(event.sourceNodeId, event.path, event.data)
        }
    }
}
