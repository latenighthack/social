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
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileSource
import com.latenighthack.social.profiles.v1.fromByteArray
import com.latenighthack.social.profiles.v1.toByteArray
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MyProfilesManagerIntegrationTest {

    @Test(timeout = 30_000)
    fun `create profile stores a source, writes a signed disclosure, and reloads`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val accountStore = KeyValueStore(InMemoryKeyValueStoreDelegate())
            val account = AccountManagerImpl(accountStore)
            val accountKeySource = AccountKeySource(account)
            val myProfiles = MyProfilesManagerImpl(account)
            val lockKeySource = ProfileKeySource(myProfiles, accountKeySource)
            val lockers = LockersClient.create(
                rpcClient = server.rpcClient,
                storeDelegate = InMemoryStoreDelegate(),
                keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
                keySource = accountKeySource,
                appVersion = Version(0, 0, 1),
                lockKeySource = lockKeySource,
            )
            account.start(lockers)
            myProfiles.start(lockers)

            account.createAccount()
            val ready = account.lifecycle.first { it is AccountManager.Lifecycle.Ready } as AccountManager.Lifecycle.Ready
            val accountRoom = ready.privateRoom

            val profileId = myProfiles.createProfile("Alice")

            // The secret half is stored as a source locker in the account room.
            val sourceClient = lockers.typed(
                ProfileKeyspaces.PROFILE_SOURCE, ProfileSource::toByteArray, ProfileSource.Companion::fromByteArray,
            )
            val source = sourceClient.getLocker(accountRoom, profileId.toSourceLockerId())
            assertNotNull(source)
            assertEquals(profileId, source.profileId)
            assertTrue(source.privateKey.isNotEmpty())

            // The profile's own room holds a Profile with one signed display-name disclosure.
            val profileClient = lockers.typed(
                ProfileKeyspaces.PROFILE, Profile::toByteArray, Profile.Companion::fromByteArray,
            )
            val profile = profileClient.getLocker(profileId.toRoomId(), profileId.toProfileLockerId())
            assertNotNull(profile)
            assertEquals(1, profile.disclosures.size)
            assertEquals("Alice", profile.displayName())
            assertTrue(profile.disclosures.first().signature.isNotEmpty())

            // Updating via the builder rewrites (and re-signs) the disclosures.
            myProfiles.updateProfile(profileId) {
                disclosures = emptyList()
                disclosures {
                    addDisclosure {
                        payload { content.displayName { value = "Bob" } }
                    }
                }
            }
            val updated = profileClient.getLocker(profileId.toRoomId(), profileId.toProfileLockerId())
            assertEquals("Bob", updated?.displayName())
            assertTrue(updated!!.disclosures.first().signature.isNotEmpty())

            // A fresh manager over the same account loads the profile proactively from sources.
            val reloaded = MyProfilesManagerImpl(account)
            reloaded.start(lockers)
            val loaded = reloaded.watchProfile(profileId).first { it != null }
            assertEquals("Bob", loaded?.displayName())
            assertEquals(listOf(profileId), reloaded.getProfileList().first { it.isNotEmpty() })

            reloaded.stop()
            myProfiles.stop()
            account.stop()
            lockers.close()
        }
}

internal fun Profile.displayName(): String? =
    disclosures.firstNotNullOfOrNull {
        (it.payload?.content as? Profile.Disclosure.Payload.OneOfContent.displayName)?.value?.value
    }
