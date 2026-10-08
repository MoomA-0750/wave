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
import xyz.mooma0750.wave.core.music.MusicRepository
import xyz.mooma0750.wave.core.music.NewRelease
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
    suspend fun check(onFound: (List<NewRelease>) -> Unit = {}): Boolean {
        if (!settings.releaseNotices.first()) return true
        // 裏で起きたばかりなら、保存したログインを読み終えるのを待つ
        val state = withTimeoutOrNull(30_000) { auth.state.first { it !is AuthState.Restoring } }
        if (state !is AuthState.LoggedIn) return true
        if (lock.isLocked) return true
        return lock.withLock {
            val artists =
                when (val result = music.subscribedArtists()) {
                    is CallResult.Ok -> result.value.take(MAX_ARTISTS)
                    is CallResult.Failed -> return@withLock false
                }
            val now = System.currentTimeMillis()
            val year = java.time.Year.now().value
            var watch = read().retain(artists.map { it.browseId }.toSet()).expire(now)
            val found = mutableListOf<NewRelease>()
            for (artist in artists) {
                val page = (music.artist(artist.browseId) as? CallResult.Ok)?.value ?: continue
                val (fresh, next) = watch.observe(artist.browseId, page, now, year)
                watch = next
                found += fresh
                // まとめて叩かない
                delay(300)
            }
            write(watch)
            mutableRecent.value = watch.recent
            loaded = true
            Log.i(WaveApplication.LOG_TAG, "release watch artists=${artists.size} new=${found.size} recent=${watch.recent.size}")
            onFound(found)
            true
        }
    }

    /** ログアウト。 */
    fun clear() {
        file.delete()
        mutableRecent.value = emptyList()
    }

    private companion object {
        const val MAX_ARTISTS = 300
    }
}
