package com.latenighthack.social.account.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.account.domain.AccountManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The use cases are thin wrappers, so they're tested against a fake of the concept. */
private class FakeAccountManager : AccountManager {
    var hasAccount = false
        private set

    override val lifecycle: StateFlow<AccountManager.Lifecycle> =
        MutableStateFlow(AccountManager.Lifecycle.NoAccount)

    override fun start(lockers: LockersClient) {}
    override fun stop() {}
    override suspend fun createAccount(): ByteArray { hasAccount = true; return ByteArray(33) }
    override suspend fun localAccountRoom(): RoomId? = null
    override suspend fun restoreAccount(privateKey: ByteArray): ByteArray { hasAccount = true; return ByteArray(33) }
    override suspend fun signOut() { hasAccount = false }
}

class AccountUseCaseTest {

    @Test
    fun `create account then sign out`() = runTest {
        val manager = FakeAccountManager()

        val created = CreateAccountUseCase(manager).create()
        assertIs<AccountResult.Ready>(created)
        assertEquals(33, created.accountId.size)
        assertTrue(manager.hasAccount)

        val signedOut = SignOutUseCase(manager).signOut()
        assertEquals(AccountResult.SignedOut, signedOut)
        assertFalse(manager.hasAccount)
    }
}
