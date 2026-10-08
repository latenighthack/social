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

class SecretConfidentialityRegressionTest {
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
    @Test(timeout=30000) fun accountPublicIdMustNotRevealPrivateProfileKeys() = runTestWithServer(Application::attachTestServices) { server, _ ->
        val owner = party(server.rpcClient)
        val stranger = party(server.rpcClient)
        try {
            val id = owner.profiles.createProfile("owner")
            val room = (owner.account.lifecycle.value as AccountManager.Lifecycle.Ready).privateRoom
            val sources = stranger.lockers.typed(ProfileKeyspaces.PROFILE_SOURCE, ProfileSource::toByteArray, ProfileSource.Companion::fromByteArray)
            val leaked = sources.getLocker(room, id.toSourceLockerId())
            assertTrue(leaked == null || leaked.privateKey.isEmpty(), "a different account read the raw profile private key")
            assertTrue(leaked!!.encryptedPrivateKey.isNotEmpty())
            assertFails { stranger.account.unprotectSecret("profile/${id.rawValue.toList()}", leaked.encryptedPrivateKey) }
            val clear = owner.account.unprotectSecret("profile/${id.rawValue.toList()}", leaked.encryptedPrivateKey)
            assertTrue(clear.isNotEmpty())
        } finally { stranger.close(); owner.close() }
    }
}
