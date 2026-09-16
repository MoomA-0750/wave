package xyz.mooma0750.wave.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 合成値だけを使う。エイリアスと DataStore ファイルはテストごとに使い捨てる。 */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val alias = "wave.credentials.test.$id"
    private val file = File(context.filesDir, "datastore/test-credentials-$id.preferences_pb")
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    private val credentials = assertNotNull(Credentials.fromLogin("SID=s; SAPISID=synthetic-sapisid", "synthetic-visitor", "synthetic-sync||x", 99))

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
        androidKeyStore().deleteEntry(alias)
    }

    @Test
    fun emptyAtFirst(): Unit =
        runBlocking {
            assertEquals(LoadResult.Empty, store().load())
        }

    @Test
    fun roundTripsAcrossStoreInstances(): Unit =
        runBlocking {
            assertEquals(SaveResult.Saved, store().save(credentials))

            val loaded = assertIs<LoadResult.Found>(store().load()).credentials
            assertEquals("SID=s; SAPISID=synthetic-sapisid", loaded.cookie.reveal())
            assertEquals("synthetic-sync", loaded.dataSyncId?.reveal())
            assertEquals("synthetic-visitor", loaded.visitorData)
            assertEquals(99, loaded.obtainedAtEpochMs)
        }

    @Test
    fun storesOnlyCiphertext(): Unit =
        runBlocking {
            store().save(credentials)
            val prefs = dataStore.data.first()
            val ciphertext = Base64.getDecoder().decode(prefs[stringPreferencesKey("ct")])
            val plaintext = CredentialCodec.encode(credentials)
            assertEquals(false, ciphertext.contentEquals(plaintext))
            assertEquals(false, ciphertext.decodeToString(throwOnInvalidSequence = false).contains("SAPISID"))
            assertEquals(false, prefs.asMap().values.joinToString(" ").contains("synthetic"))
            // GCM: 暗号文 = 平文と同じ長さ + 16 バイトのタグ
            assertEquals(plaintext.size + 16, ciphertext.size)
        }

    @Test
    fun schemaVersionIsBoundAsAssociatedData(): Unit =
        runBlocking {
            store().save(credentials)
            dataStore.edit { it[intPreferencesKey("v")] = 2 }

            assertEquals(LoadResult.Unreadable(UnreadableReason.Tampered), store().load())
        }

    @Test
    fun tamperedIvIsDetected(): Unit =
        runBlocking {
            store().save(credentials)
            val ivKey = stringPreferencesKey("iv")
            dataStore.edit { prefs ->
                val bytes = Base64.getDecoder().decode(prefs[ivKey])
                bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
                prefs[ivKey] = Base64.getEncoder().encodeToString(bytes)
            }

            assertEquals(LoadResult.Unreadable(UnreadableReason.Tampered), store().load())
        }

    @Test
    fun missingKeyIsUnavailableAndRecoversOnSave(): Unit =
        runBlocking {
            store().save(credentials)
            androidKeyStore().deleteEntry(alias)

            assertEquals(LoadResult.Unreadable(UnreadableReason.KeyUnavailable), store().load())

            assertEquals(SaveResult.Saved, store().save(credentials))
            assertIs<LoadResult.Found>(store().load())
        }

    @Test
    fun tamperedCiphertextIsDetectedAndRecoversOnSave(): Unit =
        runBlocking {
            store().save(credentials)
            val ctKey = stringPreferencesKey("ct")
            dataStore.edit { prefs ->
                val bytes = Base64.getDecoder().decode(prefs[ctKey])
                bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
                prefs[ctKey] = Base64.getEncoder().encodeToString(bytes)
            }

            assertEquals(LoadResult.Unreadable(UnreadableReason.Tampered), store().load())

            assertEquals(SaveResult.Saved, store().save(credentials))
            assertIs<LoadResult.Found>(store().load())
        }

    @Test
    fun clearRemovesCiphertextAndKey(): Unit =
        runBlocking {
            store().save(credentials)
            store().clear()

            assertEquals(LoadResult.Empty, store().load())
            assertNull(androidKeyStore().getKey(alias, null))
            assertEquals(emptyMap(), dataStore.data.first().asMap())
        }

    private fun store() = KeystoreCredentialStore(dataStore, alias)

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
