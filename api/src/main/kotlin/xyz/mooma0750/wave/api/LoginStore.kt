package xyz.mooma0750.wave.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.core.auth.CredentialStore
import xyz.mooma0750.wave.core.auth.Credentials
import xyz.mooma0750.wave.core.auth.LoadResult
import xyz.mooma0750.wave.core.auth.SaveResult
import xyz.mooma0750.wave.core.auth.UnreadableReason

/**
 * 家の端末から渡されたログイン（ADR-0015）をデータのディレクトリに置く（権限 600。サーバーには Keystore が無い）。
 * 値はログ・応答に出さない。
 */
class LoginStore(
    private val data: DataDir,
) : CredentialStore {
    @Serializable
    private data class Stored(
        val cookie: String,
        val visitorData: String? = null,
        val dataSyncId: String? = null,
        val obtainedAtEpochMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): LoadResult {
        val text = data.read(DataDir.LOGIN) ?: return LoadResult.Empty
        val stored = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull() ?: return LoadResult.Unreadable(UnreadableReason.Malformed)
        val credentials = Credentials.fromLogin(stored.cookie, stored.visitorData, stored.dataSyncId, stored.obtainedAtEpochMs) ?: return LoadResult.Unreadable(UnreadableReason.Malformed)
        return LoadResult.Found(credentials)
    }

    override suspend fun save(credentials: Credentials): SaveResult =
        runCatching {
            data.write(DataDir.LOGIN, json.encodeToString(Stored.serializer(), Stored(credentials.cookie.reveal(), credentials.visitorData, credentials.dataSyncId?.reveal(), credentials.obtainedAtEpochMs)))
            SaveResult.Saved
        }.getOrElse { SaveResult.Failed(transient = true) }

    override suspend fun clear(): Boolean {
        data.delete(DataDir.LOGIN)
        return !data.file(DataDir.LOGIN).exists()
    }

    fun exists(): Boolean = data.file(DataDir.LOGIN).isFile
}
