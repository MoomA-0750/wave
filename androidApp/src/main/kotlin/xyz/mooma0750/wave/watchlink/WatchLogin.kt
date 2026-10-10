package xyz.mooma0750.wave.watchlink

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.security.KeyPair
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import xyz.mooma0750.wave.MainActivity
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.auth.SaveResult

/**
 * スマホから時計へログインを渡す（ADR-0011）。スマホ側（頼みを受けて、本人が送ると決める）と時計側（頼んで、受け取る）の両方を持つ。
 */
class WatchLogin(
    context: Context,
    private val auth: AuthManager,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    /** スマホに届いた、時計からの頼み。 */
    class Request(
        val nodeId: String,
        val publicKey: ByteArray,
        val code: String,
        val receivedAtMs: Long,
    )

    private val mutablePending = MutableStateFlow<Request?>(null)

    /** スマホ側: まだ答えていない頼み（切れたものは null）。 */
    val pending: StateFlow<Request?> = mutablePending.asStateFlow()

    /** スマホ側: 時計から頼みが届いた。ログインしていれば、確認を求める通知を出す。 */
    fun onRequest(
        nodeId: String,
        publicKey: ByteArray,
    ) {
        if (WatchLoginCrypto.publicKeyOf(publicKey) == null) return
        scope.launch { handleRequest(nodeId, publicKey) }
    }

    private suspend fun handleRequest(
        nodeId: String,
        publicKey: ByteArray,
    ) {
        // 頼みでプロセスが起きた直後は、保存したログインをまだ読んでいる
        if (!awaitSettledLogin(auth)) {
            Log.i(WaveApplication.LOG_TAG, "watch login request ignored (logged out)")
            return
        }
        val request = Request(nodeId, publicKey, WatchLoginCrypto.codeOf(publicKey), SystemClock.elapsedRealtime())
        mutablePending.value = request
        Log.i(WaveApplication.LOG_TAG, "watch login request received")
        notifyRequest()
    }

    /** スマホ側: 答えを待っている頼み（5分で切れる）。 */
    fun current(): Request? = mutablePending.value?.takeIf { SystemClock.elapsedRealtime() - it.receivedAtMs < REQUEST_TTL_MS }

    /** スマホ側: 本人が番号を見比べて「送る」を押した。送れたら true。 */
    suspend fun approve(request: Request): Boolean {
        if (mutablePending.value !== request) return false
        mutablePending.value = null
        cancelNotification()
        val plain = auth.exportForWatch() ?: return false
        val sealed = WatchLoginCrypto.seal(request.publicKey, plain) ?: return false
        plain.fill(0)
        val ok =
            try {
                Wearable.getMessageClient(appContext).sendMessage(request.nodeId, PATH_CREDENTIALS, sealed).await()
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                false
            }
        Log.i(WaveApplication.LOG_TAG, "watch login sent ok=$ok")
        return ok
    }

    /** スマホ側: 送る（画面を閉じても送り終える。結果は知らせで出す）。 */
    fun approveInBackground(
        request: Request,
        sentText: String,
        failedText: String,
    ) {
        scope.launch { xyz.mooma0750.wave.ui.Notices.show(if (approve(request)) sentText else failedText) }
    }

    fun decline() {
        mutablePending.value = null
        cancelNotification()
        Log.i(WaveApplication.LOG_TAG, "watch login declined")
    }

    /** 時計側の様子。 */
    sealed interface Status {
        data object Idle : Status

        data object Sending : Status

        /** スマホに頼んだ。スマホの画面に同じ番号が出る。 */
        data class Waiting(
            val code: String,
        ) : Status

        /** wave の入ったスマホが見つからない。 */
        data object NoPhone : Status

        data object Failed : Status

        data object Done : Status
    }

    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private var keys: KeyPair? = null
    private var requestedAtMs = 0L

    /** 時計側: スマホに頼む。 */
    fun request() {
        scope.launch {
            mutableStatus.value = Status.Sending
            val nodes =
                runCatching {
                    Wearable.getCapabilityClient(appContext).getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
                }.getOrNull().orEmpty()
            Log.i(WaveApplication.LOG_TAG, "watch login phones=${nodes.size}")
            if (nodes.isEmpty()) {
                mutableStatus.value = Status.NoPhone
                return@launch
            }
            val pair = WatchLoginCrypto.newKeyPair()
            keys = pair
            requestedAtMs = SystemClock.elapsedRealtime()
            val sent = nodes.count { node -> runCatching { Wearable.getMessageClient(appContext).sendMessage(node.id, PATH_REQUEST, pair.public.encoded).await() }.isSuccess }
            val waiting = Status.Waiting(WatchLoginCrypto.codeOf(pair.public.encoded))
            mutableStatus.value = if (sent > 0) waiting else Status.Failed
            // スマホが答えないまま（ログインしていない・やめた）なら、頼みが切れたときに失敗にする
            kotlinx.coroutines.delay(REQUEST_TTL_MS)
            if (mutableStatus.value == waiting) {
                keys = null
                mutableStatus.value = Status.Failed
            }
        }
    }

    /** 時計側: スマホから届いた。頼んだ鍵で開けたら保存する。 */
    fun onCredentials(message: ByteArray) {
        val pair = keys ?: return
        if (SystemClock.elapsedRealtime() - requestedAtMs > REQUEST_TTL_MS) return
        scope.launch {
            val plain = WatchLoginCrypto.open(pair, message)
            if (plain == null) {
                Log.w(WaveApplication.LOG_TAG, "watch login could not open")
                mutableStatus.value = Status.Failed
                return@launch
            }
            keys = null
            val result = auth.importFromPhone(plain)
            plain.fill(0)
            Log.i(WaveApplication.LOG_TAG, "watch login saved=${result == SaveResult.Saved}")
            mutableStatus.value = if (result == SaveResult.Saved) Status.Done else Status.Failed
        }
    }

    fun reset() {
        keys = null
        mutableStatus.value = Status.Idle
    }

    private fun notifyRequest() {
        val manager = androidx.core.app.NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            androidx.core.app.NotificationChannelCompat
                .Builder(CHANNEL_ID, androidx.core.app.NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(appContext.getString(R.string.watch_notification_channel))
                .build(),
        )
        val open =
            android.app.PendingIntent.getActivity(
                appContext,
                NOTIFICATION_ID,
                Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            androidx.core.app.NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_check_circle)
                .setContentTitle(appContext.getString(R.string.watch_login_request_title))
                .setContentText(appContext.getString(R.string.watch_login_request_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(REQUEST_TTL_MS)
                .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() = androidx.core.app.NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)

    companion object {
        /** 時計 → スマホ: 頼み（時計の公開鍵）。 */
        const val PATH_REQUEST = "/wave/login/request"

        /** スマホ → 時計: 包んだログイン。 */
        const val PATH_CREDENTIALS = "/wave/login/credentials"

        /** wave の入ったスマホ（res の android_wear_capabilities）。 */
        const val CAPABILITY_PHONE = "wave_phone"

        const val REQUEST_TTL_MS = 5 * 60 * 1000L
        private const val CHANNEL_ID = "watch"
        private const val NOTIFICATION_ID = 4401
    }
}

/** Play 開発者サービスの Task を待つ。 */
suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val error = task.exception
            when {
                error != null -> continuation.resumeWithException(error)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resume(task.result)
            }
        }
    }

/** 保存したログインを読み終えるまで待つ（メッセージでプロセスが起きた直後）。ログインしていれば true。 */
suspend fun awaitSettledLogin(auth: AuthManager): Boolean =
    kotlinx.coroutines.withTimeoutOrNull(10_000) { auth.state.first { it != AuthState.Restoring && it != AuthState.LoggingOut } } is AuthState.LoggedIn
