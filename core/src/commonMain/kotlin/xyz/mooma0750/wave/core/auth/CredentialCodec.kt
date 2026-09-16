package xyz.mooma0750.wave.core.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 暗号化する前の平文。フィールドを足すときは既定値を付け、schemaVersion は上げない。 */
@Serializable
internal class StoredCredentials(
    val schemaVersion: Int,
    val cookie: String,
    val dataSyncId: String? = null,
    val visitorData: String? = null,
    val obtainedAtEpochMs: Long,
)

internal sealed interface DecodeResult {
    class Ok(val credentials: Credentials) : DecodeResult

    data object Malformed : DecodeResult

    data class UnsupportedSchema(val version: Int) : DecodeResult
}

internal object CredentialCodec {
    const val CURRENT_SCHEMA = 1

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(credentials: Credentials): ByteArray =
        json
            .encodeToString(
                StoredCredentials.serializer(),
                StoredCredentials(
                    schemaVersion = CURRENT_SCHEMA,
                    cookie = credentials.cookie.reveal(),
                    dataSyncId = credentials.dataSyncId?.reveal(),
                    visitorData = credentials.visitorData,
                    obtainedAtEpochMs = credentials.obtainedAtEpochMs,
                ),
            ).encodeToByteArray()

    /** 例外を投げない。kotlinx.serialization の例外文には入力の一部が入りうるので、種類だけを返す。 */
    fun decode(bytes: ByteArray): DecodeResult {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return DecodeResult.Malformed
        val version =
            runCatching { json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int }.getOrNull()
                ?: return DecodeResult.Malformed
        if (version > CURRENT_SCHEMA) return DecodeResult.UnsupportedSchema(version)
        if (version < 1) return DecodeResult.Malformed
        val stored = runCatching { json.decodeFromString(StoredCredentials.serializer(), text) }.getOrNull() ?: return DecodeResult.Malformed
        val credentials =
            Credentials.restore(stored.cookie, stored.dataSyncId, stored.visitorData, stored.obtainedAtEpochMs)
                ?: return DecodeResult.Malformed
        return DecodeResult.Ok(credentials)
    }
}
