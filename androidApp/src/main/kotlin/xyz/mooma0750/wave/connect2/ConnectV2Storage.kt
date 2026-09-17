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
 * Connect v2 の端末の保存（Phase 6b、D-2 の頭・D-4 の storeId と版の床・D-9 の退いた印）。
 * Keystore の AES 鍵で包み、`noBackupFilesDir` に置く。ファイルがあるのに読めないときは空として扱わず、書き込みもしない（v1 の PairStore と同じ）。
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
            runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                    updateAAD(AAD)
                    doFinal(bytes.copyOfRange(12, bytes.size))
                }
            }.getOrNull() ?: return Loaded.Unreadable
        return runCatching { Loaded.Ok(ConnectJson.json.decodeFromString(ClientPersisted.serializer(), plaintext.decodeToString())) }.getOrDefault(Loaded.Unreadable)
    }

    @Synchronized
    fun save(state: ClientPersisted) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(ConnectJson.json.encodeToString(ClientPersisted.serializer(), state).encodeToByteArray())
        val stream = atomic.startWrite()
        try {
            stream.write(cipher.iv + ciphertext)
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
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
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
        val AAD = "wave-connect-v2".encodeToByteArray()
    }
}
