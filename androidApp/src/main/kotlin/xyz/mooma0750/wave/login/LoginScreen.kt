package xyz.mooma0750.wave.login

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.mooma0750.wave.WaveApplication
import java.io.ByteArrayInputStream

private const val START_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F"
private const val LANDING_HOST = "music.youtube.com"
private const val READ_RETRY_DELAY_MS = 500L
private const val READ_RETRY_LIMIT = 10

/**
 * WebView 内で開いてよいメインフレームのホスト。利用者がページを置ける Google のホスト（sites・docs など）を含めないよう、
 * ワイルドカードにせず列挙する。足りずにログインが止まった場合は、ログの "login blocked host=" で分かる。
 */
private val LOGIN_HOSTS =
    setOf(
        "accounts.google.com",
        "accounts.google.co.jp",
        "accounts.youtube.com",
        "myaccount.google.com",
        "gds.google.com",
        "consent.google.com",
        "consent.youtube.com",
        "google.com",
        "www.google.com",
        "www.google.co.jp",
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        LANDING_HOST,
    )

internal fun isLoginHostAllowed(uri: Uri): Boolean = uri.scheme == "https" && uri.host in LOGIN_HOSTS

private val SAPISID_COOKIE = Regex("""(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID|__Secure-1PAPISID)=[^;]+""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text(host.ifEmpty { "ログイン" }) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (val state = uiState) {
                LoginUiState.Browsing ->
                    LoginWebView(
                        onHostChanged = { host = it },
                        onLanded = viewModel::onLanded,
                        onRendererGone = viewModel::onRendererGone,
                        onAbandoned = viewModel::onAbandoned,
                    )
                LoginUiState.Saving, LoginUiState.Done ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LoginUiState.Failed ->
                    Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            when (state.reason) {
                                FailureReason.NoSapisid -> "ログイン情報を取得できませんでした。"
                                FailureReason.SaveFailed -> "ログイン情報を端末に保存できませんでした。"
                                FailureReason.RendererGone -> "ログイン画面が異常終了しました。"
                            },
                        )
                        Button(onClick = viewModel::retry) { Text("もう一度ログインする") }
                    }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(
    onHostChanged: (String) -> Unit,
    onLanded: (cookie: String?, visitorData: String?, dataSyncId: String?) -> Unit,
    onRendererGone: () -> Unit,
    onAbandoned: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            // debug ビルドでも、このプロセスの WebView を chrome://inspect に出さない
            WebView.setWebContentsDebuggingEnabled(false)
            WaveApplication.markLoginWebViewUsed()
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptCookie(true)

                webChromeClient =
                    object : WebChromeClient() {
                        // ページの console を logcat に流さない
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                    }
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (!request.isForMainFrame || isLoginHostAllowed(request.url)) return false
                            Log.i(WaveApplication.LOG_TAG, "login blocked host=${request.url.host} external=${request.hasGesture()}")
                            // 利用者が自分でタップしたリンクだけ、ブラウザで開く（ページ側の自動遷移では外部アプリを起動しない）
                            if (request.hasGesture() && request.url.scheme == "https") {
                                val intent =
                                    Intent(Intent.ACTION_VIEW, request.url).apply {
                                        addCategory(Intent.CATEGORY_BROWSABLE)
                                        component = null
                                        selector = null
                                    }
                                runCatching { view.context.startActivity(intent) }
                            }
                            return true
                        }

                        // POST の遷移は shouldOverrideUrlLoading を通らないので、ここでもメインフレームを止める
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (!request.isForMainFrame || isLoginHostAllowed(request.url)) return null
                            Log.i(WaveApplication.LOG_TAG, "login blocked host=${request.url.host} method=${request.method}")
                            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            onHostChanged(url?.let { Uri.parse(it).host }.orEmpty())
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            canGoBack = view.canGoBack()
                        }

                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: SslError,
                        ) {
                            handler.cancel()
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            // 処理しないとアプリのプロセスごと終わる。この WebView はもう使えないので、画面を失敗にして作り直させる
                            Log.w(WaveApplication.LOG_TAG, "login renderer gone crashed=${detail.didCrash()}")
                            onRendererGone()
                            return true
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            if (!isOnLandingPage(view)) return
                            if (!hasSapisid(CookieManager.getInstance().getCookie("https://$LANDING_HOST"))) return
                            readConfigValues(view, attempt = 0) { visitorData, dataSyncId ->
                                // 読んでいる間に別のページへ移っていたら使わない。cookie は値を読み終えた時点のものを渡す
                                if (!isOnLandingPage(view)) return@readConfigValues
                                val cookie = CookieManager.getInstance().getCookie("https://$LANDING_HOST")
                                if (!hasSapisid(cookie)) return@readConfigValues
                                onLanded(cookie, visitorData, dataSyncId)
                            }
                        }
                    }
                loadUrl(START_URL)
                webView = this
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                clearCache(true)
                destroy()
            }
            webView = null
            onAbandoned()
        }
    }
}

private fun isOnLandingPage(view: WebView): Boolean {
    val uri = view.url?.let(Uri::parse) ?: return false
    return uri.scheme == "https" && uri.host == LANDING_HOST
}

private fun hasSapisid(cookie: String?): Boolean = cookie != null && SAPISID_COOKIE.containsMatchIn(cookie)

/** ページの yt.config_ から値を読む。JS インターフェースは公開せず、評価結果（JSON の文字列リテラル）を受け取るだけ。 */
private fun readConfigValues(
    view: WebView,
    attempt: Int,
    onRead: (visitorData: String?, dataSyncId: String?) -> Unit,
) {
    view.evaluateJavascript("String(window.yt?.config_?.VISITOR_DATA ?? '')") { rawVisitor ->
        view.evaluateJavascript("String(window.yt?.config_?.DATASYNC_ID ?? '')") { rawSync ->
            val visitorData = decodeJsString(rawVisitor)
            val dataSyncId = decodeJsString(rawSync)
            if ((visitorData == null || dataSyncId == null) && attempt < READ_RETRY_LIMIT) {
                Handler(Looper.getMainLooper()).postDelayed({ readConfigValues(view, attempt + 1, onRead) }, READ_RETRY_DELAY_MS)
            } else {
                onRead(visitorData, dataSyncId)
            }
        }
    }
}

/** evaluateJavascript の結果（"..." の形）を文字列に戻す。空文字・非文字列は null。JSON パーサは使わない（例外文に入力が入るため）。 */
internal fun decodeJsString(raw: String?): String? {
    if (raw == null || raw.length < 2 || raw.first() != '"' || raw.last() != '"') return null
    val body = raw.substring(1, raw.length - 1)
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '\\') {
            out.append(c)
            i++
            continue
        }
        if (i + 1 >= body.length) return null
        when (val next = body[i + 1]) {
            '"', '\\', '/' -> {
                out.append(next)
                i += 2
            }
            'n', 'r', 't', 'b', 'f' -> return null
            'u' -> {
                if (i + 6 > body.length) return null
                val code = body.substring(i + 2, i + 6).toIntOrNull(16)?.takeIf { it >= 0 } ?: return null
                out.append(code.toChar())
                i += 6
            }
            else -> return null
        }
    }
    return out.toString().takeIf { it.isNotEmpty() }
}
