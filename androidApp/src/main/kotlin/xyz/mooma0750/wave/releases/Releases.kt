package xyz.mooma0750.wave.releases

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mooma0750.wave.WaveApplication
import xyz.mooma0750.wave.core.auth.AuthManager
import xyz.mooma0750.wave.core.auth.AuthState
import xyz.mooma0750.wave.core.music.ChannelPostsClient
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.NewRelease
import xyz.mooma0750.wave.core.music.ReleaseSuspicion
import xyz.mooma0750.wave.core.music.ReleaseWatchState
import xyz.mooma0750.wave.core.session.CallResult
import xyz.mooma0750.wave.settings.AppSettings
import java.io.File

/**
 * 登録したアーティストの新作の見張り（裏の確かめ ReleaseWatcher と、ホーム・アーティストのページの表示が共有する）。
 * 状態（前に見た作品と、最近の新作）は noBackupFilesDir のファイルに持つ。確かめは同時に1本だけ走らせる。
 */
class Releases(
    context: Context,
    private val music: MusicRepository,
    private val auth: AuthManager,
    private val settings: AppSettings,
    private val youtube: ChannelPostsClient,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "release-watch.json"))
    private val lock = Mutex()
    private val mutableRecent = MutableStateFlow<List<NewRelease>>(emptyList())

    /** 最近の新作（新しい順）。 */
    val recent: StateFlow<List<NewRelease>> = mutableRecent.asStateFlow()

    @Volatile
    private var loaded = false

    private fun read(): ReleaseWatchState = runCatching { file.readFully().decodeToString() }.getOrNull()?.let(ReleaseWatchState::decode) ?: ReleaseWatchState()

    private fun write(state: ReleaseWatchState) {
        val stream = file.startWrite()
        try {
            stream.write(ReleaseWatchState.encode(state).encodeToByteArray())
            file.finishWrite(stream)
        } catch (_: java.io.IOException) {
            file.failWrite(stream)
        }
    }

    /** 保存してある最近の新作を読む（画面を開いたとき）。 */
    suspend fun load() {
        if (loaded) return
        lock.withLock {
            if (loaded) return
            mutableRecent.value = read().expire(System.currentTimeMillis()).recent
            loaded = true
        }
    }

    /**
     * 登録したアーティストのページを順に見て、新作を探す。見つけた新作は onFound に渡す（通知する）。
     * @return 最後まで確かめられたか
     */
    suspend fun check(onFound: suspend (List<NewRelease>) -> Unit = {}): Boolean {
        if (!settings.releaseNotices.first()) return true
        // 裏で起きたばかりなら、保存したログインを読み終えるのを待つ
        val state = withTimeoutOrNull(30_000) { auth.state.first { it !is AuthState.Restoring } }
        if (state !is AuthState.LoggedIn) return true
        if (lock.isLocked) return true
        return lock.withLock {
            var complete = true
            val subscribed =
                when (val result = music.subscribedArtists(onIncomplete = { complete = false })) {
                    is CallResult.Ok -> result.value
                    is CallResult.Failed -> return@withLock false
                }
            val artists = subscribed.take(MAX_ARTISTS)
            val now = System.currentTimeMillis()
            val year = java.time.Year.now().value
            // 登録をやめたアーティストの記録は、登録の一覧を最後まで読めたときだけ捨てる（途中のページで失敗すると欠けて返り、
            // 欠けた人の記録まで捨てると次に初めて見る扱いになって新作を知らせ損ねる）。空で返ったときも捨てない
            var watch = read().expire(now).let { if (complete && subscribed.isNotEmpty()) it.retain(subscribed.map { a -> a.browseId }.toSet()) else it }
            val found = mutableListOf<NewRelease>()
            for (artist in artists) {
                val page = (music.artist(artist.browseId) as? CallResult.Ok)?.value ?: continue
                val (fresh, next) = watch.observe(artist.browseId, page, now, year)
                watch = next
                found += fresh
                // まとめて叩かない
                delay(300)
            }
            watch = checkSuspicion(watch, now)
            write(watch)
            mutableRecent.value = watch.recent
            loaded = true
            // 出元が怪しい新作は棚に印を付けて残すが、通知は鳴らさない
            val notable = found.filter { release -> watch.recent.firstOrNull { it.albumBrowseId == release.albumBrowseId }?.suspect.isNullOrEmpty() }
            Log.i(WaveApplication.LOG_TAG, "release watch artists=${artists.size} new=${found.size} suspect=${found.size - notable.size} recent=${watch.recent.size}")
            onFound(notable)
            true
        }
    }

    /**
     * まだ確かめていない新作の出元を確かめる（1曲目の YouTube の説明文と再生回数。ReleaseSuspicion）。
     * 読めなかったものは次の回にまた確かめる。
     */
    private suspend fun checkSuspicion(
        state: ReleaseWatchState,
        now: Long,
    ): ReleaseWatchState {
        var watch = state
        for (release in state.unchecked().take(MAX_SUSPICION_CHECKS)) {
            val albumId = release.albumBrowseId ?: continue
            val videoId = (music.album(albumId) as? CallResult.Ok)?.value?.songs?.firstOrNull()?.videoId ?: continue
            val facts = youtube.videoFacts(videoId) ?: continue
            val reasons = ReleaseSuspicion.reasons(release.artistName, facts, now)
            if (reasons.isNotEmpty()) Log.i(WaveApplication.LOG_TAG, "release suspect album=$albumId reasons=$reasons")
            watch = watch.withSuspicion(albumId, reasons)
            delay(300)
        }
        return watch
    }

    /** ログアウト。 */
    fun clear() {
        file.delete()
        mutableRecent.value = emptyList()
    }

    private companion object {
        const val MAX_ARTISTS = 300

        /** 1回の確かめで出元を確かめる新作の数（前に覚えた分も少しずつ確かめる）。 */
        const val MAX_SUSPICION_CHECKS = 20
    }
}
