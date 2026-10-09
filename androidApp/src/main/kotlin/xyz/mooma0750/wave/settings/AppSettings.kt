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

/** Claude の中継の接続先（QR の waveai://pair?u=&t=&n= から読む）。 */
data class AiBridgeConfig(
    val url: String,
    val token: String,
    /** 中継の動いている機械の名前（表示用）。 */
    val name: String? = null,
) {
    companion object {
        /** QR の中身を読む。形が違えば null。接続先は https か、この端末の中（127.0.0.1）だけ受ける。 */
        fun parse(text: String): AiBridgeConfig? {
            val uri = runCatching { android.net.Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "waveai" || uri.host != "pair") return null
            val url = uri.getQueryParameter("u")?.trimEnd('/') ?: return null
            val token = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() } ?: return null
            return AiBridgeConfig(url, token, uri.getQueryParameter("n")).takeIf { it.urlAllowed() }
        }
    }

    fun urlAllowed(): Boolean = url.startsWith("https://") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")
}

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

    /** 再生中に、通知センターにキュー（次に流れる曲）を出すか。既定は出さない。 */
    val queueNotice: Flow<Boolean> =
        store.data
            .map { it[QUEUE_NOTICE] ?: false }
            .catch { if (it is IOException) emit(false) else throw it }

    suspend fun setQueueNotice(enabled: Boolean) {
        store.edit { it[QUEUE_NOTICE] = enabled }
    }

    /** Claude の中継（tools/ai-bridge）の接続先とトークン。設定していなければ null。 */
    val aiBridge: Flow<AiBridgeConfig?> =
        store.data
            .map { prefs -> prefs[AI_URL]?.let { url -> prefs[AI_TOKEN]?.let { AiBridgeConfig(url, it, prefs[AI_NAME]) } } }
            .catch { if (it is IOException) emit(null) else throw it }

    suspend fun setAiBridge(config: AiBridgeConfig?) {
        store.edit {
            if (config == null) {
                it.remove(AI_URL)
                it.remove(AI_TOKEN)
                it.remove(AI_NAME)
            } else {
                it[AI_URL] = config.url
                it[AI_TOKEN] = config.token
                config.name?.let { name -> it[AI_NAME] = name } ?: it.remove(AI_NAME)
            }
        }
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
        val QUEUE_NOTICE = booleanPreferencesKey("queue_notice")
        val AI_URL = stringPreferencesKey("ai_url")
        val AI_TOKEN = stringPreferencesKey("ai_token")
        val AI_NAME = stringPreferencesKey("ai_name")
        const val PLAYLIST_ORDER_PREFIX = "playlist_order_"

        fun playlistOrderKey(playlistId: String) = stringPreferencesKey(PLAYLIST_ORDER_PREFIX + playlistId)
    }
}
