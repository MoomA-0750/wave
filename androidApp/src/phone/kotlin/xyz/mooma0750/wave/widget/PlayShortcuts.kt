package xyz.mooma0750.wave.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.PlayLink
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.playback.playFromButton
import xyz.mooma0750.wave.ui.WaveTheme

/**
 * ホーム画面に置く「鳴らすもの」のショートカット（固定ショートカット）。ワンタップ再生のウィジェットと同じく、
 * プレイリスト・アルバム・曲・ライブの1つを、名前と表紙の絵で置き、押すと画面を出さずに鳴らす（[ItemShortcutActivity]）。
 * ランチャーのウィジェットの一覧の「ショートカット」からと（[CreatePlayShortcutActivity]）、曲・プレイリスト・アルバムのメニューの
 * 「ホーム画面に追加」から置ける。
 */
object PlayShortcuts {
    private const val EXTRA_KIND = "xyz.mooma0750.wave.shortcut.KIND"
    private const val EXTRA_ID = "xyz.mooma0750.wave.shortcut.ID"
    private const val EXTRA_TITLE = "xyz.mooma0750.wave.shortcut.TITLE"
    private const val EXTRA_SUBTITLE = "xyz.mooma0750.wave.shortcut.SUBTITLE"
    private const val EXTRA_THUMBNAIL = "xyz.mooma0750.wave.shortcut.THUMBNAIL"

    /** ランチャーが「ホーム画面に追加」を受け付けるか（受け付けなければメニューに出さない）。 */
    fun canPin(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** メニューの「ホーム画面に追加」: 表紙を読んでから、ランチャーに置いてよいかを尋ねてもらう。 */
    fun pin(
        context: Context,
        item: WidgetItem,
    ) {
        val app = context.applicationContext as WaveApplication
        app.container.appScope.launch {
            val shortcut = shortcutOf(app, item)
            runCatching { ShortcutManagerCompat.requestPinShortcut(app, shortcut, null) }
                .onSuccess { Log.i(WaveApplication.LOG_TAG, "pin shortcut kind=${item.kind} requested=$it") }
                .onFailure { Log.w(WaveApplication.LOG_TAG, "pin shortcut failed type=${it::class.simpleName}") }
        }
    }

    /** 置くショートカット（同じものは同じ ID にして、置き直しで増えないようにする）。 */
    suspend fun shortcutOf(
        context: Context,
        item: WidgetItem,
    ): ShortcutInfoCompat {
        val container = (context.applicationContext as WaveApplication).container
        val cover = WidgetArtwork.load(container.mediaHttpClient, item.thumbnailUrl)
        val intent =
            Intent(context, ItemShortcutActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_KIND, item.kind.name)
                .putExtra(EXTRA_ID, item.id)
                .putExtra(EXTRA_TITLE, item.title)
        item.subtitle?.let { intent.putExtra(EXTRA_SUBTITLE, it) }
        item.thumbnailUrl?.let { intent.putExtra(EXTRA_THUMBNAIL, it) }
        return ShortcutInfoCompat
            .Builder(context, idOf(item))
            .setShortLabel(item.title.ifEmpty { context.getString(R.string.widget_play) })
            .setLongLabel(context.getString(R.string.widget_play_playlist, item.title))
            .setIcon(IconCompat.createWithAdaptiveBitmap(cover?.let(PlaylistShortcuts::adaptive) ?: PlaylistShortcuts.placeholder(context, placeholderIcon(item))))
            .setIntent(intent)
            .build()
    }

    fun idOf(item: WidgetItem): String = "item:${item.kind.name}:${item.id}"

    fun itemOf(intent: Intent): WidgetItem? {
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { name -> WidgetItemKind.entries.firstOrNull { it.name == name } } ?: return null
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotEmpty() } ?: return null
        return WidgetItem(kind, id, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_SUBTITLE), intent.getStringExtra(EXTRA_THUMBNAIL))
    }
}

/** ログインを読み終えるのを待つ（プロセスを起こしたばかりのとき）。していなければ案内を出して false。 */
internal suspend fun signedInForPlay(context: Context): Boolean {
    val container = (context.applicationContext as WaveApplication).container
    val auth = withTimeoutOrNull(AUTH_WAIT_MS) { container.authManager.state.first { it !is AuthState.Restoring } }
    if (auth is AuthState.LoggedIn) return true
    toast(context, R.string.widget_sign_in)
    return false
}

private const val AUTH_WAIT_MS = 5_000L

/** ホーム画面のショートカットを押した: 画面を出さずに、置いたものを鳴らして閉じる（ワンタップ再生のウィジェットを押したのと同じ）。 */
class ItemShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = PlayShortcuts.itemOf(intent)
        if (item != null) {
            ShortcutManagerCompat.reportShortcutUsed(this, PlayShortcuts.idOf(item))
            val app = applicationContext
            (app as WaveApplication).container.appScope.launch {
                if (signedInForPlay(app)) playWidgetItem(app, item, shuffle = false)
            }
        }
        finish()
    }
}

/**
 * ランチャーのウィジェットの一覧の「ショートカット」の「wave: 再生」を置いたとき: ワンタップ再生と同じ画面で鳴らすものを選び、
 * それを押すと鳴るショートカットにして返す。
 */
class CreatePlayShortcutActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(xyz.mooma0750.wave.settings.AppLanguage.wrap(newBase))

    private val saving = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (intent?.action != Intent.ACTION_CREATE_SHORTCUT) {
            finish()
            return
        }
        enableEdgeToEdge()
        val container = (application as WaveApplication).container
        val likedTitle = getString(R.string.main_liked_songs)
        setContent {
            WaveTheme {
                val auth by container.authManager.state.collectAsStateWithLifecycle()
                ItemPicker(
                    title = stringResource(R.string.shortcut_choose),
                    loggedIn = auth is AuthState.LoggedIn,
                    restoring = auth == AuthState.Restoring,
                    saving = saving.value,
                    model = viewModel { WidgetItemPickerModel(container.music, likedTitle) },
                    onPick = ::pick,
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun pick(item: WidgetItem) {
        if (saving.value) return
        saving.value = true
        lifecycleScope.launch {
            runCatching { PlayShortcuts.shortcutOf(this@CreatePlayShortcutActivity, item) }
                .onSuccess {
                    setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this@CreatePlayShortcutActivity, it))
                    Log.i(WaveApplication.LOG_TAG, "create shortcut kind=${item.kind}")
                }.onFailure { Log.w(WaveApplication.LOG_TAG, "create shortcut failed type=${it::class.simpleName}") }
            finish()
        }
    }
}

/**
 * ほかのアプリ（Tasker・自動化のアプリの「Intent を送る」など）から呼ぶ再生の口。画面を出さずに受けて閉じる。
 * 受けるのは再生の操作だけ（ログイン情報などは何も返さない）。
 *
 * - [ACTION_PLAY]: [EXTRA_LINK]（または Intent の data）の YouTube / YouTube Music の URL か ID を鳴らす（[PlayLink] の見分け方）。
 *   [EXTRA_SHUFFLE] でプレイリスト・アルバムをシャッフル。リンクが無ければ、今のキューの再生を続ける
 * - [ACTION_PAUSE]・[ACTION_PLAY_PAUSE]・[ACTION_NEXT]・[ACTION_PREVIOUS]: 今のキューの操作
 *
 * ほかの端末を映している間は、アプリの画面から選んだときと同じく、その端末で鳴らしてもらう。
 */
class PlayIntentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val link = intent?.getStringExtra(EXTRA_LINK) ?: intent?.dataString
        val shuffle = intent?.getBooleanExtra(EXTRA_SHUFFLE, false) == true
        val app = applicationContext
        val scope = (app as WaveApplication).container.appScope
        Log.i(WaveApplication.LOG_TAG, "play intent action=${action?.substringAfterLast('.')} link=${link != null} shuffle=$shuffle")
        when (action) {
            ACTION_PLAY ->
                scope.launch {
                    if (link == null) {
                        withController(app) { it.playFromButton() }
                        return@launch
                    }
                    val parsed = PlayLink.parse(link)
                    if (parsed == null) {
                        toast(app, R.string.shortcut_link_unknown)
                        return@launch
                    }
                    if (signedInForPlay(app)) playLink(app, parsed, shuffle)
                }
            ACTION_PAUSE -> scope.launch { withController(app) { it.pause() } }
            ACTION_PLAY_PAUSE -> scope.launch { withController(app) { if (it.playWhenReady) it.pause() else it.playFromButton() } }
            ACTION_NEXT -> scope.launch { withController(app) { it.seekToNext() } }
            ACTION_PREVIOUS -> scope.launch { withController(app) { it.seekToPrevious() } }
        }
        finish()
    }

    companion object {
        const val ACTION_PLAY = "xyz.mooma0750.wave.action.PLAY"
        const val ACTION_PAUSE = "xyz.mooma0750.wave.action.PAUSE"
        const val ACTION_PLAY_PAUSE = "xyz.mooma0750.wave.action.PLAY_PAUSE"
        const val ACTION_NEXT = "xyz.mooma0750.wave.action.NEXT"
        const val ACTION_PREVIOUS = "xyz.mooma0750.wave.action.PREVIOUS"
        const val EXTRA_LINK = "xyz.mooma0750.wave.extra.LINK"
        const val EXTRA_SHUFFLE = "xyz.mooma0750.wave.extra.SHUFFLE"
    }
}

/**
 * リンクで渡されたものを鳴らす。動画は、曲かライブか・題・アーティストを次に流れる曲（upNext）の先頭で引いてから、
 * ワンタップ再生の曲と同じく鳴らす（ライブの印が取れなくても、流すときにライブと分かれば流し直す）。
 */
private suspend fun playLink(
    context: Context,
    link: PlayLink,
    shuffle: Boolean,
) {
    val container = (context.applicationContext as WaveApplication).container
    when (link) {
        is PlayLink.Playlist -> playWidgetItem(context, WidgetItem(WidgetItemKind.Playlist, link.playlistId, "", null, null), shuffle)
        is PlayLink.Album -> playWidgetItem(context, WidgetItem(WidgetItemKind.Album, link.browseId, "", null, null), shuffle)
        is PlayLink.Video ->
            when (val page = container.music.upNext(link.videoId, null)) {
                is CallResult.Ok -> {
                    val song = page.value.songs.firstOrNull { it.videoId == link.videoId }
                    if (song == null) {
                        toast(context, R.string.widget_load_failed)
                    } else {
                        container.player.playSong(song)
                    }
                }
                is CallResult.Failed -> {
                    Log.w(WaveApplication.LOG_TAG, "play link failed kind=${page.kind}")
                    toast(context, R.string.widget_load_failed)
                }
            }
    }
}
