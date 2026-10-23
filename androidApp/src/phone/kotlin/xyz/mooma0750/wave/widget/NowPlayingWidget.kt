package xyz.mooma0750.wave.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.R
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.library.AddToPlaylistActivity
import xyz.mooma0750.wave.library.LikedNotice
import xyz.mooma0750.wave.playback.NowPlayingSnapshot
import xyz.mooma0750.wave.playback.SongJson
import xyz.mooma0750.wave.playback.playFromButton

/**
 * 再生中の曲のウィジェット（iPhone の「再生中」と同じ並び）。ジャケット（右下に評価のボタン）・曲名・アーティスト
 * （ほかの端末で鳴っていれば端末名）と、前へ・再生/一時停止・次へ。広ければ右に「次に流れる曲」を3曲並べ、押すとその曲へ飛ぶ。
 * 地はジャケットをぼかして暗くしたもの。押すとアプリの再生画面を開く。
 * ほかの端末の再生を映している間はその端末の曲を出し、ボタンはその端末の遠隔操作になる（再生サービスのセッションの再生器が替わるため）。
 * 低いとき（iPhone に無い大きさ）は、ボタンだけか1列にする。
 */
class NowPlayingWidget : GlanceAppWidget() {
    // 境目に大きさの倍率を掛けるので、どの並べ方にするかは置いた実際の大きさから自分で決める（Responsive のままだと、
    // 境目より狭いときにホーム画面の側がボタンだけの並べ方を選んでいた）
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        WidgetScale.load(context)
        if (WidgetPreview.forced) return providePreviewContent(context)
        val container = (context.applicationContext as WaveApplication).container
        // 最初の描画で画像が抜けないよう、今の曲の画像は先に取っておく
        val first = WidgetHooks.nowPlaying.value
        WidgetArtwork.load(container.mediaHttpClient, first?.artworkUrl)
        first?.upNext?.forEach { WidgetArtwork.load(container.mediaHttpClient, it.artworkUrl) }
        provideContent {
            // 描いている間に曲が変わったら、そのまま描き直す
            val snapshot by WidgetHooks.nowPlaying.collectAsState()
            val artwork = rememberWidgetArtwork(container.mediaHttpClient, snapshot?.artworkUrl)
            val queueArtwork = rememberWidgetArtworks(container.mediaHttpClient, snapshot?.upNext.orEmpty().mapNotNull { it.artworkUrl })
            // 倍率は、ボタンだけの並べ方が収まるところまで
            WidgetScaled(minWidth = 150, minHeight = 48) {
                WaveGlanceTheme(context) {
                    NowPlayingContent(snapshot, artwork, queueArtwork)
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
            WidgetScaled(minWidth = 150, minHeight = 48) {
                WaveGlanceTheme(context) {
                    NowPlayingContent(WidgetPreview.nowPlaying(context), null, emptyMap())
                }
            }
        }

    companion object {
        /** 2×1 ほど: ボタンだけ。 */
        val CONTROLS: DpSize
            @Composable get() = widgetSize(150, 48)

        /** 2×2 ほど（iPhone の小）: ジャケットと評価、曲名、ボタン。 */
        val SQUARE: DpSize
            @Composable get() = widgetSize(110, 140)

        /** 4×1: ジャケット・曲名・再生・次へを1列に（ボタンだけの大きさより広いとき）。 */
        val WIDE: DpSize
            @Composable get() = widgetSize(200, 48)

        /** 4×2 以上（iPhone の中）: 左に今の曲とボタン、右に次に流れる曲。 */
        val MEDIUM: DpSize
            @Composable get() = widgetSize(250, 130)

        /** 置いた大きさに収まる、いちばん広い並べ方。 */
        @Composable
        fun layoutFor(size: DpSize): NowPlayingLayout {
            fun fits(bound: DpSize) = size.width >= bound.width && size.height >= bound.height
            return when {
                fits(MEDIUM) -> NowPlayingLayout.Medium
                fits(SQUARE) -> NowPlayingLayout.Square
                fits(WIDE) -> NowPlayingLayout.Wide
                else -> NowPlayingLayout.Controls
            }
        }
    }
}

enum class NowPlayingLayout { Controls, Square, Wide, Medium }

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NowPlayingWidget()

    override fun onReceive(
        context: Context,
        intent: android.content.Intent,
    ) {
        // 並べ方の境目に倍率を掛けるので、描き始める前に読んでおく
        WidgetScale.load(context)
        super.onReceive(context, intent)
    }
}

private val itemIdKey = ActionParameters.Key<String>("itemId")
private val videoIdKey = ActionParameters.Key<String>("videoId")

@Composable
private fun NowPlayingContent(
    snapshot: NowPlayingSnapshot?,
    artwork: Bitmap?,
    queueArtwork: Map<String, Bitmap>,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = actionStartActivity(openAppIntent(context).putExtra(WidgetHooks.EXTRA_OPEN_NOW_PLAYING, true))
    // 中くらいは、今の曲と次に流れる曲を色違いの入れ物に分けて左右につなぐ
    val layout = NowPlayingWidget.layoutFor(size)
    val split = snapshot != null && layout == NowPlayingLayout.Medium
    WidgetSurface(GlanceModifier.clickable(open), split = split) {
        // Responsive では、置いた大きさに収まる宣言した大きさのうち最も大きいものが来る
        when {
            snapshot == null -> EmptyState(open)
            layout == NowPlayingLayout.Controls ->
                Box(GlanceModifier.fillMaxSize().padding(horizontal = 8.wdp), contentAlignment = Alignment.Center) {
                    // 低い枠でも上下が切れないよう、ボタンの高さを枠に収める
                    Controls(snapshot, height = minOf(COMPACT_BUTTON, size.height - 8.wdp), maxWidth = size.width - 16.wdp)
                }
            layout == NowPlayingLayout.Square -> SmallLayout(snapshot, artwork, open)
            layout == NowPlayingLayout.Wide -> WideLayout(snapshot, artwork, open)
            else -> MediumLayout(snapshot, artwork, queueArtwork, open)
        }
    }
}

@Composable
private fun EmptyState(open: Action) {
    val context = LocalContext.current
    val size = LocalSize.current
    val icon = @Composable { dp: androidx.compose.ui.unit.Dp ->
        Image(
            ImageProvider(R.drawable.ic_music_note),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(dp),
        )
    }
    when {
        // 低い: 1行だけ
        size.height < NowPlayingWidget.SQUARE.height ->
            Row(GlanceModifier.fillMaxSize().padding(horizontal = 14.wdp).clickable(open), verticalAlignment = Alignment.CenterVertically) {
                icon(24.wdp)
                Spacer(GlanceModifier.width(10.wdp))
                Text(context.getString(R.string.widget_nothing_playing), style = WidgetText.title(), maxLines = 1)
            }
        // 狭い: 縦に並べる
        size.width < NowPlayingWidget.WIDE.width ->
            Column(GlanceModifier.fillMaxSize().padding(14.wdp).clickable(open), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                icon(32.wdp)
                Spacer(GlanceModifier.height(8.wdp))
                Text(context.getString(R.string.widget_nothing_playing), style = WidgetText.title().copy(textAlign = androidx.glance.text.TextAlign.Center), maxLines = 2)
            }
        else ->
            Row(GlanceModifier.fillMaxSize().padding(16.wdp).clickable(open), verticalAlignment = Alignment.CenterVertically) {
                icon(32.wdp)
                Spacer(GlanceModifier.width(12.wdp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(context.getString(R.string.widget_nothing_playing), style = WidgetText.title(), maxLines = 1)
                    Text(context.getString(R.string.widget_open_app), style = WidgetText.subtitle(), maxLines = 1)
                }
            }
    }
}

@Composable
internal fun Artwork(
    artwork: Bitmap?,
    modifier: GlanceModifier,
    /** null なら押せない（押すのは外の行に任せる）。 */
    open: Action?,
    corner: androidx.compose.ui.unit.Dp = ArtworkCorner,
    iconSize: androidx.compose.ui.unit.Dp = 28.wdp,
) {
    val base = modifier.cornerRadius(corner).let { if (open != null) it.clickable(open) else it }
    if (artwork != null) {
        Image(ImageProvider(artwork), contentDescription = null, contentScale = ContentScale.Crop, modifier = base)
    } else {
        Box(base.background(GlanceTheme.colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(R.drawable.ic_music_note),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(iconSize),
            )
        }
    }
}

/** ジャケットの右下に評価のボタンを重ねたもの（出せないときは絵だけ）。 */
@Composable
private fun ArtworkWithLike(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    side: androidx.compose.ui.unit.Dp,
    open: Action,
) {
    Box(GlanceModifier.size(side), contentAlignment = Alignment.BottomEnd) {
        Artwork(artwork, GlanceModifier.size(side), open)
        snapshot.saved?.let { saved -> LikeButton(snapshot, saved) }
    }
}

/**
 * 評価のボタン（アプリと同じ）。＋は押すと高く評価し、✓（高く評価したかプレイリストに入っている）は
 * プレイリストに追加する画面を出す。絵の上でも見えるよう、暗い丸を敷く。
 */
@Composable
private fun LikeButton(
    snapshot: NowPlayingSnapshot,
    saved: Boolean,
) {
    val context = LocalContext.current
    val song = snapshot.song
    val action =
        if (saved && song != null) {
            actionStartActivity(
                Intent(context, AddToPlaylistActivity::class.java)
                    .putExtra(LikedNotice.EXTRA_SONG, SongJson.encode(song))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        } else {
            actionRunCallback<LikeAction>(actionParametersOf(videoIdKey to snapshot.videoId.orEmpty()))
        }
    Box(
        GlanceModifier
            .padding(3.wdp)
            .size(LIKE_BUTTON)
            .cornerRadius(LIKE_BUTTON / 2)
            .background(ColorProvider(Color.Black.copy(alpha = 0.45f)))
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (saved) R.drawable.ic_check_circle else R.drawable.ic_add_circle),
            contentDescription = context.getString(if (saved) R.string.library_add_to_playlist else R.string.playback_like),
            colorFilter = ColorFilter.tint(if (saved) GlanceTheme.colors.primary else ColorProvider(Color.White)),
            modifier = GlanceModifier.size(LIKE_BUTTON - 6.wdp),
        )
    }
}

/** 曲名と、アーティストかほかの端末の名前（端末名は印付きで色を変える）。 */
@Composable
internal fun TitleAndArtist(
    snapshot: NowPlayingSnapshot,
    modifier: GlanceModifier = GlanceModifier,
    titleStyle: TextStyle = WidgetText.title(),
) {
    Column(modifier) {
        Text(snapshot.title, style = titleStyle, maxLines = 1)
        val device = snapshot.device
        if (device != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(R.drawable.ic_devices),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(14.wdp),
                )
                Spacer(GlanceModifier.width(4.wdp))
                Text(device, style = WidgetText.device(), maxLines = 1)
            }
        } else if (snapshot.artist.isNotEmpty()) {
            Text(snapshot.artist, style = WidgetText.subtitle(), maxLines = 1)
        }
    }
}

/**
 * 前へ・再生・次へ（m3e-lab の再生のボタングループと同じく、地のあるボタンを並べる。前へ・次へは淡い tonal で幅を狭め、
 * 再生は primary で幅を広くとる）。maxWidth を渡すとその幅いっぱいに広げ（右に余りを残さない）、収まらなければ高さごと縮める。
 */
@Composable
internal fun Controls(
    snapshot: NowPlayingSnapshot,
    modifier: GlanceModifier = GlanceModifier,
    /** ボタンの高さ（収まる限り）。 */
    height: Dp = COMPACT_BUTTON,
    /** 並べられる幅。 */
    maxWidth: Dp? = null,
) {
    val context = LocalContext.current
    // 幅の単位（比 1 に当たる幅）。幅を渡されたらその幅を比で割り振る
    val unit = maxWidth?.let { (it - ButtonGap * 2) / (SIDE_RATIO * 2 + PLAY_RATIO) }
    val h = unit?.let { minOf(height, it) } ?: height
    val w = unit ?: h
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WidgetIconButton(R.drawable.ic_skip_previous, context.getString(R.string.nowplaying_previous), actionRunCallback<PreviousAction>(), GlanceModifier.width(w * SIDE_RATIO).height(h), h)
        Spacer(GlanceModifier.width(ButtonGap))
        PlayPauseButton(snapshot.playing, GlanceModifier.width(w * PLAY_RATIO).height(h), h)
        Spacer(GlanceModifier.width(ButtonGap))
        WidgetIconButton(R.drawable.ic_skip_next, context.getString(R.string.nowplaying_next), actionRunCallback<NextAction>(), GlanceModifier.width(w * SIDE_RATIO).height(h), h)
    }
}

/** 前へ・次へと再生の幅（高さに対する比）。 */
private const val SIDE_RATIO = 0.7f
private const val PLAY_RATIO = 1.3f

@Composable
internal fun PlayPauseButton(
    playing: Boolean,
    modifier: GlanceModifier,
    height: Dp,
) {
    val context = LocalContext.current
    WidgetIconButton(
        if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
        context.getString(if (playing) R.string.nowplaying_pause else R.string.widget_play),
        actionRunCallback<PlayPauseAction>(),
        modifier,
        height,
        primary = true,
    )
}

/** iPhone の小: 左上にジャケット（右下に評価）、下に曲名・アーティスト・ボタン。 */
@Composable
private fun SmallLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    open: Action,
) {
    val context = LocalContext.current
    // 2×2 は端末によって 140dp ほどしかないので、余白を詰める。端末の書体で曲名の2行が高いときは、ジャケットを縮めてボタンを切らさない
    val text = (SMALL_TEXT_BLOCK * context.resources.configuration.fontScale * LocalWidgetScale.current).dp
    val art = (LocalSize.current.height - 24.wdp - text - 2.wdp - SMALL_PLAY_BUTTON).coerceIn(24.wdp, 44.wdp)
    Column(GlanceModifier.fillMaxSize().padding(12.wdp)) {
        ArtworkWithLike(snapshot, artwork, art, open)
        // 曲名は残りの高さの中で下に寄せる（はみ出しても切れるのは曲名の側で、ボタンではない）
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.BottomStart) {
            TitleAndArtist(snapshot, GlanceModifier.fillMaxWidth().clickable(open), WidgetText.headline())
        }
        Spacer(GlanceModifier.height(2.wdp))
        // 2×2 の高さに収まるよう、ボタンは小さめにする
        Controls(snapshot, height = SMALL_PLAY_BUTTON, maxWidth = LocalSize.current.width - 24.wdp)
    }
}

@Composable
private fun WideLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    open: Action,
) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 8.wdp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, GlanceModifier.size(44.wdp), open)
        Spacer(GlanceModifier.width(10.wdp))
        TitleAndArtist(snapshot, GlanceModifier.defaultWeight().clickable(open))
        PlayPauseButton(snapshot.playing, GlanceModifier.width(COMPACT_BUTTON * PLAY_RATIO).height(COMPACT_BUTTON), COMPACT_BUTTON)
        Spacer(GlanceModifier.width(ButtonGap))
        WidgetIconButton(R.drawable.ic_skip_next, context.getString(R.string.nowplaying_next), actionRunCallback<NextAction>(), GlanceModifier.width(COMPACT_BUTTON * SIDE_RATIO).height(COMPACT_BUTTON), COMPACT_BUTTON)
    }
}

/**
 * iPhone の中: 左にジャケット（右下に評価）・曲名・アーティスト・ボタン、右に「次に流れる曲」を3曲（上下いっぱいに等しく割り振る）。
 * ボタンを縮めてでもジャケットの下に曲名が入る高さなら下に置き（間を空けない）、入らないときだけジャケットの横に置く。
 */
@Composable
private fun MediumLayout(
    snapshot: NowPlayingSnapshot,
    artwork: Bitmap?,
    queueArtwork: Map<String, Bitmap>,
    open: Action,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    // 曲名とアーティストの2行の高さ（文字の大きさの設定も掛かる）
    val text = (TEXT_BLOCK * context.resources.configuration.fontScale * LocalWidgetScale.current).dp
    // ジャケットの下に曲名を置くとき、ボタン・ジャケットを最も縮めた高さ。余りはボタン、ジャケットの順に戻す
    var spare = size.height - ContainerPadding * 2 - (STACK_ARTWORK_MIN.wdp + STACK_GAP.wdp + text + STACK_GAP.wdp + STACK_BUTTON_MIN.wdp)
    val stacked = spare >= 0.dp
    val button = STACK_BUTTON_MIN.wdp + spare.coerceIn(0.dp, COMPACT_BUTTON - STACK_BUTTON_MIN.wdp)
    spare -= button - STACK_BUTTON_MIN.wdp
    val art = STACK_ARTWORK_MIN.wdp + spare.coerceIn(0.dp, (STACK_ARTWORK_MAX - STACK_ARTWORK_MIN).wdp)
    Row(GlanceModifier.fillMaxSize()) {
        WidgetContainer(GlanceModifier.defaultWeight().fillMaxHeight(), secondary = false) {
        Column(GlanceModifier.fillMaxSize().padding(ContainerPadding)) {
            if (stacked) {
                ArtworkWithLike(snapshot, artwork, art, open)
                // 曲名は残りの高さの中で下に寄せる（文字の高さの見積もりが外れても、切れるのは曲名の上の余白でボタンではない）
                Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.BottomStart) {
                    TitleAndArtist(snapshot, GlanceModifier.fillMaxWidth().clickable(open))
                }
                Spacer(GlanceModifier.height(STACK_GAP.wdp))
            } else {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ArtworkWithLike(snapshot, artwork, 48.wdp, open)
                    Spacer(GlanceModifier.width(10.wdp))
                    TitleAndArtist(snapshot, GlanceModifier.defaultWeight().clickable(open), WidgetText.headline())
                }
                Spacer(GlanceModifier.defaultWeight())
            }
            Controls(snapshot, height = if (stacked) button else COMPACT_BUTTON, maxWidth = size.width / 2 - ContainerPadding * 2)
        }
        }
        // 右側: 次に流れる曲（押すとその曲へ飛ぶ。ほかの端末で鳴っていればその端末で）
        WidgetContainer(GlanceModifier.defaultWeight().fillMaxHeight(), secondary = true) {
        Column(GlanceModifier.fillMaxSize().padding(ContainerPadding)) {
            Text(context.getString(R.string.widget_up_next), style = WidgetText.caption().copy(fontWeight = FontWeight.Medium), maxLines = 1)
            for (item in snapshot.upNext) {
                Row(
                    GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionRunCallback<JumpAction>(actionParametersOf(itemIdKey to item.itemId))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val thumb = item.artworkUrl?.let(queueArtwork::get)
                    Artwork(thumb, GlanceModifier.size(28.wdp), null, corner = 5.wdp, iconSize = 16.wdp)
                    Spacer(GlanceModifier.width(8.wdp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(item.title, style = WidgetText.subtitle().copy(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium), maxLines = 1)
                        if (item.artist.isNotEmpty()) Text(item.artist, style = WidgetText.caption(), maxLines = 1)
                    }
                }
            }
            // 次の曲が少なくても、行の高さをそろえる
            repeat(NowPlayingSnapshot.UP_NEXT_COUNT - snapshot.upNext.size) { Spacer(GlanceModifier.defaultWeight()) }
        }
        }
    }
}

/** ボタンの操作は再生サービスのセッションへ送る（ほかの端末を映している間は、その端末の遠隔操作になる）。 */
internal suspend fun withController(
    context: Context,
    block: (androidx.media3.session.MediaController) -> Unit,
) {
    val container = (context.applicationContext as WaveApplication).container
    runCatching {
        val controller = container.player.controller()
        withContext(Dispatchers.Main.immediate) {
            // サービスが居なかった（プロセスが終わっていた）: つなぐと保存したキューが戻るので、少し待ってから操作する
            if (controller.mediaItemCount == 0) {
                withTimeoutOrNull(RESTORE_WAIT_MS) {
                    while (controller.mediaItemCount == 0) delay(100)
                }
            }
            if (controller.mediaItemCount == 0) {
                // 戻すキューも無い: 「再生していない」に描き直す
                NowPlayingWidget().updateAll(context)
                return@withContext
            }
            block(controller)
        }
    }.onFailure { Log.w(WaveApplication.LOG_TAG, "widget control failed type=${it::class.simpleName}") }
}

private const val RESTORE_WAIT_MS = 5_000L

/**
 * 中でジャケットの下に曲名を置くときの大きさ（dp。倍率を掛ける前）。曲名とアーティストの2行は、文字の余白込みで Pixel 3 では 33 ほどだが、
 * 端末の書体によっては 37 ほどになって下のボタンが切れたので、余裕を見る。
 */
private const val TEXT_BLOCK = 42f

/** 2×2 の曲名（headline）とアーティストの2行の高さの見積もり（dp。倍率を掛ける前）。 */
private const val SMALL_TEXT_BLOCK = 40f
private const val STACK_GAP = 6
private const val STACK_ARTWORK_MIN = 44
private const val STACK_ARTWORK_MAX = 56
private const val STACK_BUTTON_MIN = 36

/** 狭い・低いウィジェットの再生ボタンの大きさ（既定は 60dp）。 */
internal val COMPACT_BUTTON: Dp
    @Composable get() = 48.wdp

/** 広さのあるウィジェットの再生ボタンの大きさ。 */
private val LIKE_BUTTON: Dp
    @Composable get() = 22.wdp
private val SMALL_PLAY_BUTTON: Dp
    @Composable get() = 36.wdp


class JumpAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val itemId = parameters[itemIdKey] ?: return
        val container = (context.applicationContext as WaveApplication).container
        runCatching { container.player.jumpTo(itemId) }
            .onFailure { Log.w(WaveApplication.LOG_TAG, "widget jump failed type=${it::class.simpleName}") }
    }
}

/** ＋: 今の曲を高く評価する（曲が替わっていたら再生サービスが受け付けない）。 */
class LikeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val videoId = parameters[videoIdKey]?.takeIf { it.isNotEmpty() } ?: return
        val container = (context.applicationContext as WaveApplication).container
        runCatching { container.player.setLiked(videoId, true) }
            .onFailure { Log.w(WaveApplication.LOG_TAG, "widget like failed type=${it::class.simpleName}") }
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = withController(context) { if (it.playWhenReady) it.pause() else it.playFromButton() }
}

class NextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = withController(context) { it.seekToNext() }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = withController(context) { it.seekToPrevious() }
}
