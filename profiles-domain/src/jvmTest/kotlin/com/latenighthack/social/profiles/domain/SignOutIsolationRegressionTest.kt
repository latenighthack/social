package com.latenighthack.social.profiles.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.*
import com.latenighthack.lockers.connector.*
import com.latenighthack.lockers.server.*
import com.latenighthack.social.account.domain.*
import com.latenighthack.social.profiles.v1.*
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.test.*

class SignOutIsolationRegressionTest {
    private class Party(val account: AccountManagerImpl, val profiles: MyProfilesManagerImpl, val lockers: LockersClient) {
        fun close() { profiles.stop(); account.stop(); lockers.close() }
    }
    private suspend fun party(rpc: RpcClient): Party {
        val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
        val keys = AccountKeySource(account)
        val profiles = MyProfilesManagerImpl(account)
        val lockers = LockersClient.create(rpcClient=rpc,
            database=Database(ConnectorStorage.configuration("review-profiles-${kotlin.random.Random.nextLong()}"), InMemoryStoreDelegate()),
            keyValueStore=KeyValueStore(InMemoryKeyValueStoreDelegate()), keySource=keys, appVersion=Version(0,0,1),
            lockKeySource=ProfileKeySource(profiles,keys))
        account.start(lockers); profiles.start(lockers); account.createAccount()
        account.lifecycle.first { it is AccountManager.Lifecycle.Ready }
        return Party(account, profiles, lockers)
    }
    @Test(timeout=30000) fun signingKeyMustBeUnavailableAfterSignOut() = runTestWithServer(Application::attachTestServices) { server, _ ->
        val owner = party(server.rpcClient)
        try {
            val id = owner.profiles.createProfile("owner")
            owner.account.signOut()
            owner.account.lifecycle.first { it is AccountManager.Lifecycle.SignedOut }
            assertNull(owner.profiles.getProfile(id))
            assertNull(owner.profiles.sign(id, 123L, byteArrayOf(1)), "signed-out profile still signs")
        } finally { owner.close() }
    }
}
