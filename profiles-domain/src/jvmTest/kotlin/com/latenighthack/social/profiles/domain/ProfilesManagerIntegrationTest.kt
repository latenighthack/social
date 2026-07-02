package com.latenighthack.social.profiles.domain

import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.account.domain.AccountManagerImpl
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfilesManagerIntegrationTest {

    @Test(timeout = 30_000)
    fun `observing a profile ingests it into memory and the cache`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            // Party A publishes a profile from their own account.
            val accountA = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
            val keySourceA = AccountKeySource(accountA)
            val myProfilesA = MyProfilesManagerImpl(accountA)
            val lockersA = LockersClient.create(
                rpcClient = server.rpcClient,
                storeDelegate = InMemoryStoreDelegate(),
                keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
                keySource = keySourceA,
                appVersion = Version(0, 0, 1),
                lockKeySource = ProfileKeySource(myProfilesA, keySourceA),
            )
            accountA.start(lockersA)
            myProfilesA.start(lockersA)
            accountA.createAccount()
            accountA.lifecycle.first { it is AccountManager.Lifecycle.Ready }
            val profileId = myProfilesA.createProfile("Alice")

            // Party B connects with their own identity and observes A's profile.
            val accountB = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
            val keySourceB = AccountKeySource(accountB)
            val lockersB = LockersClient.create(
                rpcClient = server.rpcClient,
                storeDelegate = InMemoryStoreDelegate(),
                keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
                keySource = keySourceB,
                appVersion = Version(0, 0, 1),
                lockKeySource = keySourceB,
            )
            accountB.start(lockersB)
            accountB.createAccount()
            accountB.lifecycle.first { it is AccountManager.Lifecycle.Ready }

            val cacheDelegate = InMemoryStoreDelegate()
            val profilesManager = ProfilesManagerImpl(cacheDelegate)
            profilesManager.start(lockersB)
            profilesManager.observe(profileId)

            // It shows up in memory...
            val observed = profilesManager.watchProfile(profileId).first { it != null }
            assertEquals("Alice", observed?.displayName())
            assertEquals("Alice", profilesManager.getProfile(profileId)?.displayName())
            assertEquals(listOf("Alice"), profilesManager.getProfiles(listOf(profileId)).map { it?.displayName() })

            // ...and is persisted to the dedicated cache.
            val stored = ProfileStore(cacheDelegate).getAllProfiles()
            assertEquals(1, stored.size)
            assertEquals(profileId, stored.first().profileId)
            assertEquals("Alice", stored.first().profile?.displayName())

            profilesManager.stop()
            myProfilesA.stop()
            accountA.stop()
            accountB.stop()
            lockersA.close()
            lockersB.close()
        }
}
