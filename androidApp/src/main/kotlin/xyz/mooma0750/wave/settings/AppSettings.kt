package xyz.mooma0750.wave.settings

import xyz.mooma0750.wave.library.PlaylistOrderStore
import xyz.mooma0750.wave.core.music.PlaylistOrder
import androidx.datastore.preferences.core.stringPreferencesKey
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 認証情報とは別の、アプリの設定。 */
class AppSettings(
    context: Context,
) {
    private val store = context.applicationContext.settingsDataStore

    /** 再生履歴を YouTube Music に送るか（ADR-0006 の 7、既定は送る）。 */
    val historyEnabled: Flow<Boolean> =
        store.data
            .map { it[HISTORY_ENABLED] ?: true }
            // 読めなければ送らない側に倒す（再生サービス・画面を落とさない）
            .catch { if (it is IOException) emit(false) else throw it }

    suspend fun isHistoryEnabled(): Boolean = historyEnabled.first()

    suspend fun setHistoryEnabled(enabled: Boolean) {
        store.edit { it[HISTORY_ENABLED] = enabled }
    }

    /** 登録したアーティストの新作を通知で知らせるか（既定は知らせる）。 */
    val releaseNotices: Flow<Boolean> =
        store.data
            .map { it[RELEASE_NOTICES] ?: true }
            .catch { if (it is IOException) emit(false) else throw it }

    suspend fun setReleaseNotices(enabled: Boolean) {
        store.edit { it[RELEASE_NOTICES] = enabled }
    }

    /** プレイリストごとに選んだ並べ方。選んでいなければカスタム。 */
    val playlistOrders: PlaylistOrderStore =
        object : PlaylistOrderStore {
            override suspend fun get(playlistId: String): PlaylistOrder =
                try {
                    store.data.first()[playlistOrderKey(playlistId)]?.let(PlaylistOrder::decode)
                } catch (_: IOException) {
                    null
                } ?: PlaylistOrder()

            override suspend fun set(
                playlistId: String,
                order: PlaylistOrder,
            ) {
                store.edit { if (order == PlaylistOrder()) it.remove(playlistOrderKey(playlistId)) else it[playlistOrderKey(playlistId)] = order.encode() }
            }
        }

    /** ログアウトで、前のアカウントのプレイリストの並べ方を消す。 */
    suspend fun clearPlaylistOrders() {
        store.edit { prefs -> prefs.asMap().keys.filter { it.name.startsWith(PLAYLIST_ORDER_PREFIX) }.forEach { prefs.remove(it) } }
    }

    private companion object {
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val RELEASE_NOTICES = booleanPreferencesKey("release_notices")
        const val PLAYLIST_ORDER_PREFIX = "playlist_order_"

        fun playlistOrderKey(playlistId: String) = stringPreferencesKey(PLAYLIST_ORDER_PREFIX + playlistId)
    }
}
