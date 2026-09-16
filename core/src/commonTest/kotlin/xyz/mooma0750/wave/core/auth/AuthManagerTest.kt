package xyz.mooma0750.wave.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class AuthManagerTest {
    private val credentials = assertNotNull(Credentials.fromLogin("SAPISID=synthetic", null, null, 7))

    @Test
    fun restoreReflectsStore() =
        runTest {
            val found = AuthManager(FakeStore(LoadResult.Found(credentials)))
            found.restore()
            assertEquals(AuthState.LoggedIn(7), found.state.value)
            assertSame(credentials, found.credentials.value)

            val empty = AuthManager(FakeStore(LoadResult.Empty))
            empty.restore()
            assertEquals(AuthState.LoggedOut, empty.state.value)

            val unreadable = FakeStore(LoadResult.Unreadable(UnreadableReason.Tampered))
            val manager = AuthManager(unreadable)
            manager.restore()
            assertEquals(AuthState.Unreadable(UnreadableReason.Tampered), manager.state.value)
            assertNull(manager.credentials.value)
            assertEquals(0, unreadable.clears, "読めない保存物は消さない")
        }

    @Test
    fun failedSaveDoesNotLogIn() =
        runTest {
            val manager = AuthManager(FakeStore(LoadResult.Empty, saveResult = SaveResult.Failed(transient = true)))
            manager.restore()
            assertEquals(SaveResult.Failed(transient = true), manager.completeLogin(credentials))
            assertEquals(AuthState.LoggedOut, manager.state.value)
            assertNull(manager.credentials.value)
        }

    @Test
    fun logoutDropsCredentialsBeforeClearingStore() =
        runTest {
            lateinit var manager: AuthManager
            val store =
                FakeStore(LoadResult.Found(credentials)).apply {
                    onClear = {
                        assertNull(manager.credentials.value)
                        assertEquals(AuthState.LoggedOut, manager.state.value)
                    }
                }
            manager = AuthManager(store)
            manager.restore()
            manager.logout()
            assertEquals(1, store.clears)
        }

    @Test
    fun laterOperationWins() =
        runTest {
            val saveGate = CompletableDeferred<Unit>()
            val store = FakeStore(LoadResult.Empty, beforeSave = { saveGate.await() })
            val manager = AuthManager(store)
            manager.restore()

            val login = async { manager.completeLogin(credentials) }
            runCurrent()
            val logout = launch { manager.logout() }
            runCurrent()
            saveGate.complete(Unit)
            login.await()
            logout.join()

            assertEquals(AuthState.LoggedOut, manager.state.value)
            assertNull(manager.credentials.value)
        }

    @Test
    fun restoreAfterLoginDoesNotOverwrite() =
        runTest {
            val manager = AuthManager(FakeStore(LoadResult.Empty))
            manager.completeLogin(credentials)
            manager.restore()
            assertEquals(AuthState.LoggedIn(7), manager.state.value)
        }

    private class FakeStore(
        private val loadResult: LoadResult,
        private val saveResult: SaveResult = SaveResult.Saved,
        private val beforeSave: suspend () -> Unit = {},
    ) : CredentialStore {
        var clears = 0
        var onClear: () -> Unit = {}

        override suspend fun load(): LoadResult = loadResult

        override suspend fun save(credentials: Credentials): SaveResult {
            beforeSave()
            return saveResult
        }

        override suspend fun clear() {
            onClear()
            clears++
        }
    }
}
