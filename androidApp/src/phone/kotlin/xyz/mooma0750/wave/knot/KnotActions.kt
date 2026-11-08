package xyz.mooma0750.wave.knot

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.BuildConfig
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.PlayLink
import xyz.mooma0750.wave.playback.playFromButton
import xyz.mooma0750.wave.widget.WidgetHooks
import xyz.mooma0750.wave.widget.WidgetItem
import xyz.mooma0750.wave.widget.WidgetItemCatalog
import xyz.mooma0750.wave.widget.WidgetItemKind
import xyz.mooma0750.wave.widget.playLink
import xyz.mooma0750.wave.widget.playWidgetItem
import xyz.mooma0750.wave.widget.withController

/**
 * Knot のショートカットに wave のアクションを出す口（Knot の docs/external-actions.md の 1 版）。
 * Knot からの call（actions・choices・run）だけに答え、画面は出さない。答えの JSON は [KnotActionsJson] が作る。
 * 鳴らす頼みはワンタップ再生のウィジェット・ホーム画面のショートカット・ほかのアプリからの Intent と同じもの。
 */
class KnotActions : ContentProvider() {
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val out = Bundle()
        if (!KnotActionsJson.allowed(callingPackage, BuildConfig.DEBUG)) {
            Log.w(WaveApplication.LOG_TAG, "knot call refused method=$method")
            out.putString("error", "wave は Knot からの呼び出しだけを受けます")
            return out
        }
        val context = context ?: return out
        val reply =
            runBlocking {
                withTimeoutOrNull(RUN_TIMEOUT_MS) { answer(context, method, arg, extras) }
                    ?: KnotReply.Error(context.getString(R.string.knot_timeout))
            }
        Log.i(WaveApplication.LOG_TAG, "knot call method=$method type=${arg?.substringAfterLast('.')} ok=${reply !is KnotReply.Error}")
        when (reply) {
            is KnotReply.Json -> out.putString("json", reply.json)
            is KnotReply.Output -> out.putString("output", reply.json)
            is KnotReply.Error -> out.putString("error", reply.message)
        }
        return out
    }

    private suspend fun answer(
        context: Context,
        method: String,
        arg: String?,
        extras: Bundle?,
    ): KnotReply =
        when (method) {
            "actions" -> KnotReply.Json(KnotActionsJson.definitions())
            "choices" -> choices(context, extras?.getString("query")?.trim()?.takeIf { it.isNotEmpty() })
            "run" -> run(context, arg.orEmpty(), KnotActionsJson.params(extras?.getString("params")))
            else -> KnotReply.Error("知らない呼び出し $method")
        }

    /** 「選んだものを再生」の候補（ワンタップ再生と同じもの）。種類ごとの見出しで並べる。 */
    private suspend fun choices(
        context: Context,
        query: String?,
    ): KnotReply {
        val app = context.applicationContext as WaveApplication
        if (!signedIn(app)) return KnotReply.Error(context.getString(R.string.widget_sign_in))
        val catalog = WidgetItemCatalog(app.container.music, context.getString(R.string.main_liked_songs))
        val titles =
            mapOf(
                WidgetItemKind.Playlist to context.getString(R.string.library_filter_playlists),
                WidgetItemKind.Album to context.getString(R.string.search_tab_albums),
                WidgetItemKind.Song to context.getString(R.string.search_tab_songs),
                WidgetItemKind.Live to context.getString(R.string.search_tab_live),
            )
        val states = coroutineScope { WidgetItemKind.entries.map { kind -> async { kind to catalog.load(kind, query.orEmpty()) } }.awaitAll() }
        if (states.all { it.second.items.isEmpty() && it.second.failure != null }) return KnotReply.Error(context.getString(R.string.widget_load_failed))
        val sections = states.map { (kind, state) -> titles.getValue(kind) to state.items.take(MAX_CHOICES) }
        return KnotReply.Json(KnotActionsJson.choices(sections, context.getString(R.string.live_badge)))
    }

    private suspend fun run(
        context: Context,
        type: String,
        params: KnotParams,
    ): KnotReply {
        val app = context.applicationContext as WaveApplication
        val container = app.container
        // 鳴らせなかったときの知らせを、toast の代わりに誤りの文として返す
        var failure: String? = null
        val report: suspend (Int) -> Unit = { failure = context.getString(it) }
        when (type) {
            KnotActionsJson.PLAY -> {
                val item = params.item?.let(KnotActionsJson::itemOf) ?: return KnotReply.Error(context.getString(R.string.knot_no_item))
                if (!signedIn(app)) return KnotReply.Error(context.getString(R.string.widget_sign_in))
                playWidgetItem(app, item, params.shuffle, report)
                return failure?.let(KnotReply::Error) ?: KnotReply.Output(KnotActionsJson.text(context.getString(R.string.widget_play_playlist, item.title)))
            }
            KnotActionsJson.PLAY_LINK -> {
                val link = params.link?.let(PlayLink::parse) ?: return KnotReply.Error(context.getString(R.string.shortcut_link_unknown))
                if (!signedIn(app)) return KnotReply.Error(context.getString(R.string.widget_sign_in))
                playLink(app, link, params.shuffle, report)
                return failure?.let(KnotReply::Error) ?: KnotReply.Output(KnotActionsJson.NULL)
            }
            KnotActionsJson.PLAY_PAUSE -> withController(app) { if (it.playWhenReady) it.pause() else it.playFromButton() }
            KnotActionsJson.NEXT -> withController(app) { it.seekToNext() }
            KnotActionsJson.PREVIOUS -> withController(app) { it.seekToPrevious() }
            KnotActionsJson.LIKE -> {
                val videoId = WidgetHooks.nowPlaying.value?.videoId ?: return KnotReply.Error(context.getString(R.string.widget_nothing_playing))
                container.player.setLiked(videoId, true)
            }
            KnotActionsJson.NOW_PLAYING -> {
                val now = WidgetHooks.nowPlaying.value ?: return KnotReply.Error(context.getString(R.string.widget_nothing_playing))
                return KnotReply.Output(KnotActionsJson.text(KnotActionsJson.nowPlayingText(now.title, now.artist)))
            }
            else -> return KnotReply.Error("知らないアクション $type")
        }
        return KnotReply.Output(KnotActionsJson.NULL)
    }

    /** プロセスを起こしたばかりなら、ログインを読み終えるのを待つ。 */
    private suspend fun signedIn(app: WaveApplication): Boolean =
        withTimeoutOrNull(AUTH_WAIT_MS) { app.container.authManager.state.first { it !is AuthState.Restoring } } is AuthState.LoggedIn

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        /** Knot は 60 秒ほどで見切るので、その手前で返す。 */
        const val RUN_TIMEOUT_MS = 50_000L
        const val AUTH_WAIT_MS = 5_000L

        /** 見出しごとに出す候補の数。 */
        const val MAX_CHOICES = 50
    }
}

/** call の答え。 */
sealed interface KnotReply {
    data class Json(
        val json: String,
    ) : KnotReply

    /** 出力（JSON の値）。 */
    data class Output(
        val json: String,
    ) : KnotReply

    data class Error(
        val message: String,
    ) : KnotReply
}
