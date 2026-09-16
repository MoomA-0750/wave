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
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

private const val START_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F"
private const val LANDING_HOST = "music.youtube.com"
private const val READ_RETRY_DELAY_MS = 500L
private const val READ_RETRY_LIMIT = 10

/** WebView 内で開いてよいトップレベルの遷移先。それ以外は外部ブラウザへ渡す。 */
internal fun isLoginHostAllowed(uri: Uri): Boolean {
    if (uri.scheme != "https") return false
    val host = uri.host ?: return false
    return host == "accounts.google.com" ||
        host.endsWith(".google.com") ||
        host == "google.com" ||
        host.endsWith(".google.co.jp") ||
        host == LANDING_HOST ||
        host.endsWith(".youtube.com")
}

private val SAPISID_COOKIE = Regex("""(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID|__Secure-1PAPISID)=[^;]+""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text(host.ifEmpty { "ログイン" }) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                LoginUiState.Browsing -> LoginWebView(onHostChanged = { host = it }, onLanded = viewModel::onLanded)
                LoginUiState.Saving, LoginUiState.Done ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LoginUiState.Failed ->
                    Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            when (state.reason) {
                                FailureReason.NoSapisid -> "ログイン情報を取得できませんでした。"
                                FailureReason.SaveFailed -> "ログイン情報を端末に保存できませんでした。"
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
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            // debug ビルドでも、このプロセスの WebView を chrome://inspect に出さない
            WebView.setWebContentsDebuggingEnabled(false)
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
                            if (!request.isForMainFrame) return false
                            if (isLoginHostAllowed(request.url)) return false
                            Log.i(WaveApplication.LOG_TAG, "login opens external host=${request.url.host}")
                            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            return true
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            onHostChanged(url?.let { Uri.parse(it).host }.orEmpty())
                        }

                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: SslError,
                        ) {
                            handler.cancel()
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            val uri = url?.let(Uri::parse) ?: return
                            if (uri.scheme != "https" || uri.host != LANDING_HOST) return
                            val cookie = CookieManager.getInstance().getCookie("https://$LANDING_HOST")
                            if (cookie == null || !SAPISID_COOKIE.containsMatchIn(cookie)) return
                            readConfigValues(view, attempt = 0) { visitorData, dataSyncId ->
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
        }
    }
}

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
                val code = body.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                out.append(code.toChar())
                i += 6
            }
            else -> return null
        }
    }
    return out.toString().takeIf { it.isNotEmpty() }
}
