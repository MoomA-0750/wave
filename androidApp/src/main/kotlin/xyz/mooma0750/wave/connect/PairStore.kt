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
        /**
         * 手元で解除したが、相手から UnpairAck を受けていない。一覧・引き取りには出さず、鍵は残して、
         * 中継につながっている間と、相手から封筒が届いたときに Unpair を送り直す。UnpairAck を受けたら消す。
         */
        val unpaired: Boolean = false,
        /**
         * 相手が解除した（Unpair を受けて UnpairAck を返した）時刻。相手の再送に UnpairAck を返せるよう、しばらく鍵を残す。
         * 一覧・引き取りには出さない。REMOVED_BY_PEER_KEEP_MS を過ぎたら消す。
         */
        val removedByPeerAtMs: Long? = null,
    ) {
        /** 使えるペアか（解除済み・相手が解除したものを除く）。 */
        val active: Boolean
            get() = !unpaired && removedByPeerAtMs == null
    }

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val pairs: List<PairRecord>,
    )

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val atomic = AtomicFile(file)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 使えるペア（解除済み・相手が解除したものを除く）。 */
    suspend fun all(): List<PairRecord> = records().filter { it.active }

    /** 解除したが、相手から UnpairAck を受けていないペア。 */
    suspend fun unpairedPending(): List<PairRecord> = records().filter { it.unpaired && it.removedByPeerAtMs == null }

    /** @param includeInactive 解除済み・相手が解除したものも含める（相手からの封筒を開いて、解除を知らせ直す・Ack を返すため） */
    suspend fun find(
        peerDeviceId: String,
        includeInactive: Boolean = false,
    ): PairRecord? = records().firstOrNull { it.peerDeviceId == peerDeviceId && (includeInactive || it.active) }

    /** ペアの一覧のファイルがあるのに読めない（Keystore の鍵が無効になった等）。この間は追加も解除もできない。 */
    suspend fun isUnreadable(): Boolean = withContext(Dispatchers.IO) { lock.withLock { load() == null } }

    /** 読めないときは空として扱う（書き込みはしない。update が失敗する）。 */
    private suspend fun records(): List<PairRecord> = withContext(Dispatchers.IO) { lock.withLock { load().orEmpty() } }

    suspend fun markUnpaired(peerDeviceId: String) = update { pairs -> pairs.map { if (it.peerDeviceId == peerDeviceId) it.copy(unpaired = true) else it } }

    /** 相手が解除した。鍵は相手の再送に Ack を返すためにしばらく残す。期限を過ぎた記録もここで消す。 */
    suspend fun markRemovedByPeer(
        peerDeviceId: String,
        nowEpochMs: Long,
    ) = update { pairs ->
        pairs
            .map { if (it.peerDeviceId == peerDeviceId && it.removedByPeerAtMs == null) it.copy(removedByPeerAtMs = nowEpochMs) else it }
            .filterNot { it.removedByPeerAtMs != null && nowEpochMs - it.removedByPeerAtMs > REMOVED_BY_PEER_KEEP_MS }
    }

    /**
     * 解除済みの記録を消す。同じ端末とペアリングし直していたら（鍵が違えば）消さない。
     * @return 消したか
     */
    suspend fun removeUnpaired(
        peerDeviceId: String,
        sendKey: String,
    ): Boolean {
        var removed = false
        update { pairs ->
            pairs.filterNot { (it.peerDeviceId == peerDeviceId && it.sendKey == sendKey && !it.active).also { match -> if (match) removed = true } }
        }
        return removed
    }

    /** 読めなくなったペアの一覧と、それを包む鍵を捨てる（ログインは消さない）。ペアリングはし直す。 */
    suspend fun resetUnreadable() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                atomic.delete()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
            }
        }

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


    /** 送る seq を1つ予約して保存してから返す（送る前に落ちても、同じ seq を2回使わない）。 */
    suspend fun reserveSendSeq(peerDeviceId: String): Long? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val pairs = loadForWrite()
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
                val pairs = loadForWrite()
                val record = pairs.firstOrNull { it.peerDeviceId == peerDeviceId } ?: return@withLock false
                if (seq <= record.lastReceivedSeq) return@withLock false
                save(pairs.map { if (it === record) it.copy(lastReceivedSeq = seq) else it })
                true
            }
        }

    private suspend fun update(transform: (List<PairRecord>) -> List<PairRecord>) =
        withContext(Dispatchers.IO) {
            lock.withLock { save(transform(loadForWrite())) }
        }

    /** 読めなかったファイルを空の一覧で上書きしない（Keystore の鍵が無効になった等で、全部のペアを黙って失わない）。 */
    private fun loadForWrite(): List<PairRecord> = load() ?: throw java.io.IOException("pairs unreadable")

    /** ファイルが無ければ空、あるのに読めなければ null。 */
    private fun load(): List<PairRecord>? {
        // AtomicFile は書きかけのときに .bak から戻すので、ファイルの有無ではなく読み込みの例外で見分ける
        val bytes =
            try {
                atomic.readFully()
            } catch (_: java.io.FileNotFoundException) {
                return emptyList()
            } catch (_: java.io.IOException) {
                return null
            }
        if (bytes.size < 13) return null
        val plaintext =
            runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                    updateAAD(AAD)
                    doFinal(bytes.copyOfRange(12, bytes.size))
                }
            }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(Stored.serializer(), plaintext.decodeToString()).pairs }.getOrNull()
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

        /** 相手が解除したペアの鍵を、相手の再送に Ack を返すために残す期間。 */
        const val REMOVED_BY_PEER_KEEP_MS = 7L * 24 * 60 * 60 * 1000
        val AAD = "wave-pairs-v1".encodeToByteArray()
    }
}
