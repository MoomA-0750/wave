package xyz.mooma0750.wave.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.playback.Media3QueuePlayer
import xyz.mooma0750.wave.playback.playFromButton

/**
 * 1つを再生するウィジェット（ワンタップ再生）。置くときに選んだプレイリスト・アルバム・曲・ライブの絵と名前を出し、
 * どこを押してもアプリの画面を開かずに再生サービスでそれを始める（プレイリスト・アルバムはシャッフルのボタンも）。
 * それが鳴っている間は一時停止のボタンにし、押すと止める・続きから鳴らす（頭からやり直さない）。
 * クラス名はプレイリストだけを選べた頃のまま（置いてあるウィジェットが受け口の名前で結び付いているため）。
 */
class PlaylistWidget : GlanceAppWidget() {
    // 境目に大きさの倍率を掛けるので、並べ方は置いた実際の大きさから自分で決める（再生中のウィジェットと同じ）
    override val sizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        WidgetScale.load(context)
        if (WidgetPreview.forced) return providePreviewContent(context)
        val client = (context.applicationContext as WaveApplication).container.mediaHttpClient
        provideContent {
            val prefs = currentState<Preferences>()
            val item = WidgetItem.of(prefs)
            val now by WidgetHooks.nowPlaying.collectAsState()
            val artwork = rememberWidgetArtwork(client, item?.thumbnailUrl?.let(Media3QueuePlayer::largeArtwork))
            // 倍率は、絵の上に再生ボタンを重ねる並べ方が収まるところまで（低いときは 100% のまま）
            WidgetScaled(minWidth = 90, minHeight = 90) {
                WaveGlanceTheme(context) {
                    ItemContent(item, artwork, loading = prefs[LOADING] == true, playing = item?.isCurrent(now) == true && now?.playing == true)
                }
            }
        }
    }

    /** ランチャーのウィジェット追加画面の見本（Android 15 以降。[WidgetPreview]）。 */
    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) = providePreviewContent(context)

    private suspend fun providePreviewContent(context: Context): Nothing =
        provideContent {
            WidgetScaled(minWidth = 90, minHeight = 90) {
                WaveGlanceTheme(context) {
                    ItemContent(WidgetPreview.item(context), null, loading = false, playing = false)
                }
            }
        }

    companion object {
        /** 2×2 ほど: 絵と再生ボタン。 */
        val SMALL: DpSize
            @Composable get() = widgetSize(80, 90)

        /** 4×1〜4×2: 絵の横に名前とボタン。 */
        val WIDE: DpSize
            @Composable get() = widgetSize(220, 48)

        /** 4×2: 4×1 と同じ並びで、余白を iPhone と同じにする。 */
        val WIDE_TALL: DpSize
            @Composable get() = widgetSize(220, 110)

        /** 3×3 以上: 大きな絵の下に名前とボタン。 */
        val LARGE: DpSize
            @Composable get() = widgetSize(180, 220)

        /** 置いた大きさに収まる、いちばん広い並べ方。 */
        @Composable
        fun layoutFor(size: DpSize): ItemLayout {
            fun fits(bound: DpSize) = size.width >= bound.width && size.height >= bound.height
            return when {
                fits(LARGE) -> ItemLayout.Large
                fits(WIDE_TALL) -> ItemLayout.WideTall
                // 低い（4×1 など）ときだけ1列にし、狭くても高さがあれば絵の上に再生ボタンを重ねる
                fits(WIDE) && !fits(SMALL) -> ItemLayout.Wide
                else -> ItemLayout.Small
            }
        }

        /** 選んだものの ID（鍵の名前はプレイリストだけを選べた頃のまま）。 */
        val ITEM_ID = stringPreferencesKey("playlistId")
        val KIND = stringPreferencesKey("kind")
        val TITLE = stringPreferencesKey("title")
        val SUBTITLE = stringPreferencesKey("subtitle")
        val THUMBNAIL = stringPreferencesKey("thumbnail")
        val SOURCE_ID = stringPreferencesKey("sourceId")

        /** 再生を押して、曲を読んでいる間。 */
        val LOADING = booleanPreferencesKey("loading")
    }
}

enum class ItemLayout { Small, Wide, WideTall, Large }

class PlaylistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PlaylistWidget()

    override fun onReceive(
        context: Context,
        intent: android.content.Intent,
    ) {
        // 並べ方の境目に倍率を掛けるので、描き始める前に読んでおく
        WidgetScale.load(context)
        super.onReceive(context, intent)
    }
}

private val shuffleKey = ActionParameters.Key<Boolean>("shuffle")

@Composable
private fun ItemContent(
    item: WidgetItem?,
    artwork: Bitmap?,
    loading: Boolean,
    playing: Boolean,
) {
    val context = LocalContext.current
    if (item == null) {
        // 選ぶ前（置いている途中）
        WidgetSurface {
            Box(GlanceModifier.fillMaxSize().padding(12.wdp), contentAlignment = Alignment.Center) {
                Text(context.getString(R.string.widget_playlist_not_chosen), style = WidgetText.subtitle(), maxLines = 3)
            }
        }
        return
    }
    // どこを押しても鳴らす（読んでいる間は押しても何もしない）
    val play = actionRunCallback<PlayItemAction>(actionParametersOf(shuffleKey to false))
    val surface = if (loading) GlanceModifier else GlanceModifier.clickable(play)
    val size = LocalSize.current
    val shuffle = item.kind == WidgetItemKind.Playlist || item.kind == WidgetItemKind.Album
    WidgetSurface(surface) {
        val layout = PlaylistWidget.layoutFor(size)
        when (layout) {
            ItemLayout.Small ->
                Box(GlanceModifier.fillMaxSize().padding(WidgetPadding), contentAlignment = Alignment.BottomEnd) {
                    Cover(item, artwork, GlanceModifier.fillMaxSize(), play, Alignment.TopStart)
                    // 再生ボタンは絵の短い辺の 4 割まで（大きくしても絵を隠しすぎない）
                    val cover = minOf(size.width, size.height) - WidgetPadding * 2
                    Box(GlanceModifier.padding(6.wdp)) { PlayButton(item, loading, playing, minOf(COMPACT_BUTTON, cover * 0.4f)) }
                }
            ItemLayout.Wide, ItemLayout.WideTall -> {
                // 低い（4×1）ときは余白を詰める（iPhone に無い大きさ）
                val tall = layout == ItemLayout.WideTall
                val padding = if (tall) GlanceModifier.padding(WidgetPadding) else GlanceModifier.padding(horizontal = 8.wdp, vertical = 6.wdp)
                Row(GlanceModifier.fillMaxSize().then(padding), verticalAlignment = Alignment.CenterVertically) {
                    val side = (size.height - if (tall) WidgetPadding * 2 else 12.wdp).coerceIn(44.wdp, 140.wdp)
                    Cover(item, artwork, GlanceModifier.size(side), play)
                    Spacer(GlanceModifier.width(14.wdp))
                    Names(item, GlanceModifier.defaultWeight(), play)
                    if (shuffle) ShuffleButton(item, loading)
                    PlayButton(item, loading, playing, COMPACT_BUTTON)
                }
            }
            else ->
                Column(GlanceModifier.fillMaxSize().padding(WidgetPadding)) {
                    Cover(item, artwork, GlanceModifier.fillMaxWidth().defaultWeight(), play)
                    Spacer(GlanceModifier.height(12.wdp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Names(item, GlanceModifier.defaultWeight(), play)
                        if (shuffle) ShuffleButton(item, loading, 60.wdp)
                        PlayButton(item, loading, playing)
                    }
                }
        }
    }
}

/** 名前と、種類（ライブは印）・アーティスト。 */
@Composable
private fun Names(
    item: WidgetItem,
    modifier: GlanceModifier,
    play: Action,
) {
    val context = LocalContext.current
    val kind =
        when (item.kind) {
            // アルバムの副題は「アルバム • アーティスト • 年」で、種類が入っている
            WidgetItemKind.Playlist, WidgetItemKind.Album, WidgetItemKind.Song -> null
            WidgetItemKind.Live -> context.getString(R.string.live_badge)
        }
    val subtitle = listOfNotNull(kind, item.subtitle?.takeIf { it.isNotEmpty() && item.kind != WidgetItemKind.Playlist }).joinToString(" • ")
    Column(modifier.clickable(play)) {
        Text(item.title, style = WidgetText.title(), maxLines = if (subtitle.isEmpty()) 2 else 1)
        if (subtitle.isNotEmpty()) Text(subtitle, style = WidgetText.subtitle(), maxLines = 1)
    }
}

@Composable
private fun Cover(
    item: WidgetItem,
    artwork: Bitmap?,
    modifier: GlanceModifier,
    play: Action,
    /** 再生ボタンを重ねるとき、仮の絵を左上に寄せる（ボタンで隠れないように）。 */
    iconAlignment: Alignment = Alignment.Center,
) {
    val base = modifier.cornerRadius(ArtworkCorner).clickable(play)
    if (artwork != null) {
        Image(ImageProvider(artwork), contentDescription = null, contentScale = ContentScale.Crop, modifier = base)
    } else {
        Box(base.background(GlanceTheme.colors.secondaryContainer).padding(12.wdp), contentAlignment = iconAlignment) {
            Image(
                ImageProvider(placeholderIcon(item)),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(36.wdp),
            )
        }
    }
}

internal fun placeholderIcon(item: WidgetItem): Int =
    when (item.kind) {
        WidgetItemKind.Playlist -> if (item.id == MusicRepository.LIKED_SONGS) R.drawable.ic_favorite else R.drawable.ic_queue_music
        WidgetItemKind.Album -> R.drawable.ic_album
        WidgetItemKind.Song -> R.drawable.ic_music_note
        WidgetItemKind.Live -> R.drawable.ic_live
    }

/** 主役の操作（再生）は角の丸い四角で大きく塗る。読んでいる間は待ちの表示、鳴っている間は一時停止にする。 */
@Composable
private fun PlayButton(
    item: WidgetItem,
    loading: Boolean,
    playing: Boolean,
    height: Dp = 60.wdp,
) {
    val context = LocalContext.current
    // 主役は primary の幅の広い角丸の四角（再生中のウィジェットの再生ボタンと同じ）
    val modifier = GlanceModifier.width(height * 1.3f).height(height)
    if (loading) {
        Box(modifier.cornerRadius(height * 0.3f).background(GlanceTheme.colors.primary), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(GlanceModifier.size(height * 0.45f), color = GlanceTheme.colors.onPrimary)
        }
        return
    }
    WidgetIconButton(
        if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
        if (playing) context.getString(R.string.widget_pause) else context.getString(R.string.widget_play_playlist, item.title),
        actionRunCallback<PlayItemAction>(actionParametersOf(shuffleKey to false)),
        modifier,
        height,
        primary = true,
    )
}

@Composable
private fun ShuffleButton(
    item: WidgetItem,
    loading: Boolean,
    height: Dp = COMPACT_BUTTON,
) {
    val context = LocalContext.current
    WidgetIconButton(
        R.drawable.ic_shuffle,
        context.getString(R.string.widget_shuffle_playlist, item.title),
        actionRunCallback<PlayItemAction>(actionParametersOf(shuffleKey to true)),
        GlanceModifier.size(height),
        height,
        enabled = !loading,
    )
    Spacer(GlanceModifier.width(ButtonGap))
}

/**
 * 選んだものを再生サービスで鳴らす。プレイリストは最初のページ（続きはキューが読み足す）、アルバムは曲の一覧、
 * 曲・ライブはその曲から（アプリで曲を選んだときと同じく、後ろにラジオが続く）。
 * それが今鳴っているなら、頭からやり直さずに一時停止・再開する。ほかの端末を映している間は、
 * アプリの画面から選んだときと同じく、その端末で鳴らしてもらう。
 */
class PlayItemAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        val item = WidgetItem.of(prefs) ?: return
        if (prefs[PlaylistWidget.LOADING] == true) return
        val shuffle = parameters[shuffleKey] == true
        if (!shuffle && item.isCurrent(WidgetHooks.nowPlaying.value)) {
            withController(context) { if (it.playWhenReady) it.pause() else it.playFromButton() }
            return
        }
        val container = (context.applicationContext as WaveApplication).container
        val widget = PlaylistWidget()
        suspend fun setLoading(value: Boolean) {
            updateAppWidgetState(context, glanceId) { it[PlaylistWidget.LOADING] = value }
            widget.update(context, glanceId)
        }
        setLoading(true)
        try {
            // プロセスを起こしたばかりなら、ログインを読み終えるのを待つ
            val auth = withTimeoutOrNull(AUTH_WAIT_MS) { container.authManager.state.first { it !is AuthState.Restoring } }
            if (auth !is AuthState.LoggedIn) {
                toast(context, R.string.widget_sign_in)
                return
            }
            // 出どころを覚えておく（鳴っている間を見分ける）
            playWidgetItem(context, item, shuffle)?.let { id -> updateAppWidgetState(context, glanceId) { it[PlaylistWidget.SOURCE_ID] = id } }
        } finally {
            setLoading(false)
        }
    }

    private companion object {
        const val AUTH_WAIT_MS = 5_000L
    }
}

/**
 * ワンタップ再生で選んだ1つを鳴らす（ウィジェット・ホーム画面のショートカット・ほかのアプリからの頼みで共通）。
 * プレイリストは最初のページ（続きはキューが読み足す）、アルバムは曲の一覧、曲・ライブはその曲から（アプリで曲を選んだときと同じく、
 * 後ろにラジオが続く）。ほかの端末を映している間は、その端末で鳴らしてもらう。ログインは呼ぶ側で確かめておく。
 * @return アルバムを鳴らしたときのキューの出どころ（`OLAK5uy_…`）
 */
internal suspend fun playWidgetItem(
    context: Context,
    item: WidgetItem,
    shuffle: Boolean,
): String? {
    val container = (context.applicationContext as WaveApplication).container
    when (item.kind) {
        WidgetItemKind.Playlist -> playPlaylist(context, item.id, shuffle)
        WidgetItemKind.Album ->
            when (val page = container.music.album(item.id)) {
                is CallResult.Ok ->
                    if (page.value.songs.isEmpty()) {
                        toast(context, R.string.widget_load_failed)
                    } else {
                        container.player.playFrom(page.value.songs, 0, page.value.playlistId, null, shuffle)
                        return page.value.playlistId
                    }
                is CallResult.Failed -> {
                    Log.w(WaveApplication.LOG_TAG, "widget album failed kind=${page.kind}")
                    toast(context, R.string.widget_load_failed)
                }
            }
        WidgetItemKind.Song, WidgetItemKind.Live -> container.player.playSong(item.toSong())
    }
    return null
}

internal suspend fun toast(
    context: Context,
    text: Int,
) = withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

/**
 * プレイリストを最初のページから鳴らす（続きはキューが読み足す。ほかの端末を映している間はその端末で）。
 * ワンタップ再生とプレイリストのウィジェットで共通。ログインは呼ぶ側で確かめておく。
 */
internal suspend fun playPlaylist(
    context: Context,
    playlistId: String,
    shuffle: Boolean,
) {
    val container = (context.applicationContext as WaveApplication).container
    val order = container.settings.playlistOrders.get(playlistId)
    // 手元で並べる並べ方（全部読んでから並べる）は、ウィジェットでは最初のページをサーバーの並びで始める
    when (val page = container.music.playlist(playlistId, order = order.takeUnless { it.arrangedLocally })) {
        is CallResult.Ok ->
            if (page.value.songs.isEmpty()) {
                toast(context, R.string.widget_playlist_empty)
            } else {
                container.player.playFrom(page.value.songs, 0, playlistId, page.value.continuation, shuffle)
            }
        is CallResult.Failed -> {
            Log.w(WaveApplication.LOG_TAG, "widget playlist failed kind=${page.kind}")
            toast(context, R.string.widget_playlist_failed)
        }
    }
}

/** プレイリストだけを選べた頃の版で描いたボタンの宛先（描き直すまで残っている）。 */
class PlayPlaylistAction : ActionCallback by PlayItemAction()
