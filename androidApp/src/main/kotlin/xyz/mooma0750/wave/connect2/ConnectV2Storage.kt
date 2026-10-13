package xyz.mooma0750.wave.connect2

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import xyz.mooma0750.wave.handoff.v2.ClientPersisted
import xyz.mooma0750.wave.handoff.v2.ConnectJson
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Connect v2 の端末の保存（Phase 6b、D-2 の頭・D-4 の storeId と版の床・D-9 の退いた印）。`noBackupFilesDir` に置く。
 * ファイルがあるのに読めないときは空として扱わず、書き込みもしない。
 *
 * 本文はデータの鍵（AES-256-GCM、ソフトウェア）で暗号化し、データの鍵を Keystore の AES 鍵で包んで隣のファイルに置く。
 * Keystore を使うのはプロセスごとにデータの鍵を解く1回だけ（書くたびに Keystore の操作をすると、Pixel 3（Android 12）では
 * Keystore の操作の枠が埋まって連携が止まった。2026-10-06）。前の形（Keystore の鍵で直に包んだ本文）も読める。
 */
class ConnectV2Storage(
    private val file: File,
    private val keyAlias: String = KEY_ALIAS,
) {
    sealed interface Loaded {
        data class Ok(
            val state: ClientPersisted,
        ) : Loaded

        data object Empty : Loaded

        data object Unreadable : Loaded
    }

    private val atomic = AtomicFile(file)
    private val keyFile = AtomicFile(File(file.parentFile, file.name + ".key"))

    /** 解いたデータの鍵（メモリだけ）。 */
    private var dataKey: SecretKey? = null

    @Synchronized
    fun load(): Loaded {
        val bytes =
            try {
                atomic.readFully()
            } catch (_: java.io.FileNotFoundException) {
                return Loaded.Empty
            } catch (_: IOException) {
                return Loaded.Unreadable
            }
        if (bytes.size < 13) return Loaded.Unreadable
        val plaintext =
            (if (bytes[0] == FORMAT_DATA_KEY) runCatching { open(dataKey(create = false) ?: error("no data key"), bytes.copyOfRange(1, bytes.size)) }.getOrNull() else null)
                // 前の形（Keystore の鍵で直に包んだ本文）
                ?: runCatching { open(key(), bytes) }.getOrNull()
                ?: return Loaded.Unreadable
        return runCatching { Loaded.Ok(ConnectJson.json.decodeFromString(ClientPersisted.serializer(), plaintext.decodeToString())) }.getOrDefault(Loaded.Unreadable)
    }

    @Synchronized
    fun save(state: ClientPersisted) {
        val key = dataKey(create = true) ?: throw IOException("data key unavailable")
        // ソフトウェアの AES（Keystore の操作をしない）。nonce は毎回乱数
        val nonce = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(ConnectJson.json.encodeToString(ClientPersisted.serializer(), state).encodeToByteArray())
        val stream = atomic.startWrite()
        try {
            stream.write(byteArrayOf(FORMAT_DATA_KEY) + nonce + ciphertext)
            atomic.finishWrite(stream)
        } catch (error: IOException) {
            atomic.failWrite(stream)
            throw error
        }
    }

    /** 読めなくなった保存とその鍵を捨てる（家に加わり直す）。 */
    @Synchronized
    fun reset() {
        atomic.delete()
        keyFile.delete()
        dataKey = null
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    /** nonce(12) ‖ 暗号文 を開く。 */
    private fun open(
        key: SecretKey,
        bytes: ByteArray,
    ): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(AAD)
            doFinal(bytes.copyOfRange(12, bytes.size))
        }

    /** データの鍵（隣のファイルを Keystore の鍵で開く。無ければ create のときだけ作って包んで置く）。 */
    private fun dataKey(create: Boolean): SecretKey? {
        dataKey?.let { return it }
        val wrapped = runCatching { keyFile.readFully() }.getOrNull()
        val raw =
            if (wrapped != null) {
                open(key(), wrapped)
            } else {
                if (!create) return null
                val fresh = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                cipher.updateAAD(AAD)
                val sealed = cipher.iv + cipher.doFinal(fresh)
                val stream = keyFile.startWrite()
                try {
                    stream.write(sealed)
                    keyFile.finishWrite(stream)
                } catch (error: IOException) {
                    keyFile.failWrite(stream)
                    throw error
                }
                fresh
            }
        if (raw.size != 32) throw IOException("bad data key")
        return javax.crypto.spec.SecretKeySpec(raw, "AES").also { dataKey = it }
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
        const val KEY_ALIAS = "wave-connect-v2"

        /** 新しい形（データの鍵で暗号化）の先頭の1バイト。前の形は nonce から始まる。 */
        const val FORMAT_DATA_KEY: Byte = 0x57
        val AAD = "wave-connect-v2".encodeToByteArray()
    }
}
