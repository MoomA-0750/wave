package xyz.mooma0750.wave.core.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore の AES-256-GCM 鍵で暗号化し、暗号文を DataStore に置く。
 * 例外オブジェクトは保持も記録もせず、分類だけを返す。
 *
 * @param dataStore プロセスで1つだけ作ったものを渡す（同じファイルに2つ作ると DataStore が例外を投げる）
 */
class KeystoreCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialStore {
    override suspend fun load(): LoadResult =
        withContext(Dispatchers.IO) {
            val prefs =
                try {
                    dataStore.data.first()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@withContext LoadResult.Unreadable(UnreadableReason.Transient)
                }
            val encodedIv = prefs[IV]
            val encodedCiphertext = prefs[CIPHERTEXT]
            val schemaVersion = prefs[SCHEMA_VERSION]
            if (encodedIv == null && encodedCiphertext == null) return@withContext LoadResult.Empty
            if (encodedIv == null || encodedCiphertext == null || schemaVersion == null) {
                return@withContext LoadResult.Unreadable(UnreadableReason.Malformed)
            }
            val iv = decodeBase64(encodedIv)
            val ciphertext = decodeBase64(encodedCiphertext)
            if (iv == null || iv.size != GCM_IV_BYTES || ciphertext == null) {
                return@withContext LoadResult.Unreadable(UnreadableReason.Malformed)
            }

            val key =
                when (val lookup = lookupKey()) {
                    is KeyLookup.Found -> lookup.key
                    KeyLookup.Missing -> return@withContext LoadResult.Unreadable(UnreadableReason.KeyUnavailable)
                    is KeyLookup.Failed -> return@withContext LoadResult.Unreadable(lookup.reason)
                }

            val plaintext =
                try {
                    Cipher.getInstance(TRANSFORMATION).run {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                        updateAAD(associatedData(schemaVersion))
                        doFinal(ciphertext)
                    }
                } catch (_: AEADBadTagException) {
                    return@withContext LoadResult.Unreadable(UnreadableReason.Tampered)
                } catch (_: KeyPermanentlyInvalidatedException) {
                    return@withContext LoadResult.Unreadable(UnreadableReason.KeyUnavailable)
                } catch (_: GeneralSecurityException) {
                    return@withContext LoadResult.Unreadable(UnreadableReason.Transient)
                } catch (_: ProviderException) {
                    return@withContext LoadResult.Unreadable(UnreadableReason.Transient)
                } catch (_: RuntimeException) {
                    return@withContext LoadResult.Unreadable(UnreadableReason.Transient)
                }

            try {
                when (val decoded = CredentialCodec.decode(plaintext)) {
                    is DecodeResult.Ok -> LoadResult.Found(decoded.credentials)
                    DecodeResult.Malformed -> LoadResult.Unreadable(UnreadableReason.Malformed)
                    is DecodeResult.UnsupportedSchema -> LoadResult.Unreadable(UnreadableReason.UnsupportedSchema)
                }
            } finally {
                plaintext.fill(0)
            }
        }

    override suspend fun save(credentials: Credentials): SaveResult =
        withContext(Dispatchers.IO) {
            val plaintext = CredentialCodec.encode(credentials)
            try {
                val encrypted = encryptWithUsableKey(plaintext) ?: return@withContext SaveResult.Failed(transient = true)
                dataStore.edit { prefs ->
                    prefs[IV] = Base64.getEncoder().encodeToString(encrypted.iv)
                    prefs[CIPHERTEXT] = Base64.getEncoder().encodeToString(encrypted.ciphertext)
                    prefs[SCHEMA_VERSION] = CredentialCodec.CURRENT_SCHEMA
                }
                SaveResult.Saved
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SaveResult.Failed(transient = true)
            } finally {
                plaintext.fill(0)
            }
        }

    /**
     * 暗号文 → 鍵 の順に消す。暗号文の削除に失敗しても鍵は消す（鍵が無ければ暗号文は読めない。
     * 次の起動では Unreadable(KeyUnavailable) になり、再ログインで上書きされる）。
     * 途中でキャンセルさせない。
     */
    override suspend fun clear() {
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                dataStore.edit { prefs ->
                    prefs.remove(IV)
                    prefs.remove(CIPHERTEXT)
                    prefs.remove(SCHEMA_VERSION)
                }
            } catch (_: Exception) {
            }
            try {
                androidKeyStore().deleteEntry(keyAlias)
            } catch (_: Exception) {
            }
        }
    }

    private class Encrypted(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    /**
     * 鍵が無ければ作る。作り直すのは、鍵が永久に無効化された（KeyPermanentlyInvalidatedException）場合だけ。
     * それ以外の失敗（UnrecoverableKeyException を含む。一時的な失敗が包まれていることがある）では鍵を消さず、失敗を返す。
     */
    private fun encryptWithUsableKey(plaintext: ByteArray): Encrypted? {
        var regenerate = false
        repeat(2) {
            try {
                val key =
                    if (regenerate) {
                        regenerateKey()
                    } else {
                        when (val lookup = lookupKey()) {
                            is KeyLookup.Found -> lookup.key
                            KeyLookup.Missing -> generateKey()
                            is KeyLookup.Failed -> return null
                        }
                    }
                return Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.ENCRYPT_MODE, key)
                    updateAAD(associatedData(CredentialCodec.CURRENT_SCHEMA))
                    Encrypted(iv = iv, ciphertext = doFinal(plaintext))
                }
            } catch (_: KeyPermanentlyInvalidatedException) {
                if (regenerate) return null
                regenerate = true
            } catch (_: GeneralSecurityException) {
                return null
            } catch (_: ProviderException) {
                return null
            }
        }
        return null
    }

    private sealed interface KeyLookup {
        class Found(val key: SecretKey) : KeyLookup

        data object Missing : KeyLookup

        data class Failed(val reason: UnreadableReason) : KeyLookup
    }

    private fun lookupKey(): KeyLookup =
        try {
            when (val key = androidKeyStore().getKey(keyAlias, null)) {
                null -> KeyLookup.Missing
                is SecretKey -> KeyLookup.Found(key)
                else -> KeyLookup.Failed(UnreadableReason.KeyUnavailable)
            }
        } catch (_: UnrecoverableKeyException) {
            KeyLookup.Failed(UnreadableReason.KeyUnavailable)
        } catch (_: GeneralSecurityException) {
            KeyLookup.Failed(UnreadableReason.Transient)
        } catch (_: IOException) {
            KeyLookup.Failed(UnreadableReason.Transient)
        } catch (_: ProviderException) {
            KeyLookup.Failed(UnreadableReason.Transient)
        }

    private fun regenerateKey(): SecretKey {
        androidKeyStore().deleteEntry(keyAlias)
        return generateKey()
    }

    private fun generateKey(): SecretKey =
        KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        // 画面ロック中のバックグラウンド再生でも読めるように、利用者認証とアンロックを要求しない
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKey()

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun associatedData(schemaVersion: Int): ByteArray = "wave.credentials$schemaVersion".encodeToByteArray()

    companion object {
        const val DEFAULT_KEY_ALIAS = "wave.credentials.v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12

        private val IV = stringPreferencesKey("iv")
        private val CIPHERTEXT = stringPreferencesKey("ct")
        private val SCHEMA_VERSION = intPreferencesKey("v")
    }
}
