package xyz.mooma0750.wave.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.io.File
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * 中継に来てよい端末の集合（決定 K-7・K-11）。信頼の根拠ではない（端末は自分のペア一覧で判断する）。
 * 起動時の設定の鍵は取り消せない（締め出しすぎたときの復旧の足場）。上限は16台。
 */
class Registry(
    private val file: File,
    bootstrapKeys: List<String>,
    private val maxDevices: Int = 16,
) {
    @Serializable
    private data class Stored(
        val keys: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val bootstrap: Map<String, String> = bootstrapKeys.mapNotNull { key -> idOf(key)?.let { it to key } }.toMap()
    private val registered = LinkedHashMap<String, String>()

    init {
        val stored = runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull()
        stored?.keys?.forEach { key -> idOf(key)?.let { registered[it] = key } }
    }

    @Synchronized
    fun publicKey(deviceId: String): ECPublicKey? = (bootstrap[deviceId] ?: registered[deviceId])?.let(::decode)

    @Synchronized
    fun contains(deviceId: String): Boolean = deviceId in bootstrap || deviceId in registered

    /** @return 登録した（または既にある）なら true。上限を超えるなら false */
    @Synchronized
    fun register(publicKey: String): Boolean {
        val id = idOf(publicKey) ?: return false
        if (contains(id)) return true
        if (bootstrap.size + registered.size >= maxDevices) return false
        registered[id] = publicKey
        persist()
        return true
    }

    /** 起動時の設定の鍵は取り消せない。 */
    @Synchronized
    fun revoke(deviceId: String): Boolean {
        if (deviceId in bootstrap) return false
        val removed = registered.remove(deviceId) != null
        if (removed) persist()
        return removed
    }

    @Synchronized
    fun size(): Int = bootstrap.size + registered.size

    private fun persist() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), Stored(registered.values.toList())))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        private val b64 = Base64.getUrlDecoder()

        fun decode(publicKey: String): ECPublicKey? = runCatching { WaveCrypto.decodePublicKey(b64.decode(publicKey)) }.getOrNull()

        fun idOf(publicKey: String): String? = decode(publicKey)?.let { EnvelopeSealer.deviceId(it.encoded) }
    }
}
