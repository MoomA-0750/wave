package xyz.mooma0750.wave.connect

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.Pairing
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ペアリングした端末の一覧（信頼の根拠、ADR-0008）。ペアの鍵と seq を同じレコードに置き、
 * ファイル全体を Keystore の AES-256-GCM 鍵で包んで noBackupFilesDir に保存する。
 */
class PairStore(
    private val file: File,
    private val keyAlias: String = KEY_ALIAS,
) {
    @Serializable
    data class PairRecord(
        val peerDeviceId: String,
        val peerPublicKey: String,
        val peerName: String,
        val sendKey: String,
        val receiveKey: String,
        val accountKey: String,
        /** 次に送る seq（送る前に進めて保存する）。 */
        val nextSendSeq: Long = 0,
        /** 相手から最後に受けた seq。 */
        val lastReceivedSeq: Long = -1,
    )

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val pairs: List<PairRecord>,
    )

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val atomic = AtomicFile(file)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    suspend fun all(): List<PairRecord> = withContext(Dispatchers.IO) { lock.withLock { load() } }

    suspend fun find(peerDeviceId: String): PairRecord? = all().firstOrNull { it.peerDeviceId == peerDeviceId }

    suspend fun add(pair: Pairing.Established) =
        update { pairs ->
            val record =
                PairRecord(
                    peerDeviceId = EnvelopeSealer.deviceId(pair.peerPublicKey),
                    peerPublicKey = b64.encodeToString(pair.peerPublicKey),
                    peerName = pair.peerDeviceName,
                    sendKey = b64.encodeToString(pair.sendKey),
                    receiveKey = b64.encodeToString(pair.receiveKey),
                    accountKey = b64.encodeToString(pair.accountKey),
                )
            pairs.filterNot { it.peerDeviceId == record.peerDeviceId } + record
        }

    suspend fun remove(peerDeviceId: String) = update { pairs -> pairs.filterNot { it.peerDeviceId == peerDeviceId } }

    /** 送る seq を1つ予約して保存してから返す（送る前に落ちても、同じ seq を2回使わない）。 */
    suspend fun reserveSendSeq(peerDeviceId: String): Long? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val pairs = load()
                val record = pairs.firstOrNull { it.peerDeviceId == peerDeviceId } ?: return@withLock null
                save(pairs.map { if (it === record) it.copy(nextSendSeq = it.nextSendSeq + 1) else it })
                record.nextSendSeq
            }
        }

    /** 受けた seq を記録する。最後に受けた値以下なら false（再送）。 */
    suspend fun acceptReceivedSeq(
        peerDeviceId: String,
        seq: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val pairs = load()
                val record = pairs.firstOrNull { it.peerDeviceId == peerDeviceId } ?: return@withLock false
                if (seq <= record.lastReceivedSeq) return@withLock false
                save(pairs.map { if (it === record) it.copy(lastReceivedSeq = seq) else it })
                true
            }
        }

    private suspend fun update(transform: (List<PairRecord>) -> List<PairRecord>) =
        withContext(Dispatchers.IO) {
            lock.withLock { save(transform(load())) }
        }

    private fun load(): List<PairRecord> {
        val bytes = runCatching { atomic.readFully() }.getOrNull() ?: return emptyList()
        if (bytes.size < 13) return emptyList()
        val plaintext =
            runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                    updateAAD(AAD)
                    doFinal(bytes.copyOfRange(12, bytes.size))
                }
            }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(Stored.serializer(), plaintext.decodeToString()).pairs }.getOrDefault(emptyList())
    }

    private fun save(pairs: List<PairRecord>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(json.encodeToString(Stored.serializer(), Stored(pairs = pairs)).encodeToByteArray())
        val stream = atomic.startWrite()
        try {
            stream.write(cipher.iv + ciphertext)
            atomic.finishWrite(stream)
        } catch (error: java.io.IOException) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "wave-pairs-v1"
        val AAD = "wave-pairs-v1".encodeToByteArray()
    }
}
