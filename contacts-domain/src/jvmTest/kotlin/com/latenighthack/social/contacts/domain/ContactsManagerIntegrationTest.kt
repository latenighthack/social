package com.latenighthack.social.contacts.domain

import com.latenighthack.ktbuf.net.RpcClient
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
import com.latenighthack.social.profiles.v1.ProfileId
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContactsManagerIntegrationTest {

    private class Party(
        val contacts: ContactsManagerImpl,
        val lockers: LockersClient,
    ) {
        fun close() {
            contacts.stop()
            lockers.close()
        }
    }

    private suspend fun newParty(
        rpcClient: RpcClient,
        accountStore: KeyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
    ): Party {
        val account = AccountManagerImpl(accountStore)
        // Contacts write only to the account room, so the account key source is both the auth key
        // source and the lock key source — no rooms/profiles chain is needed.
        val accountKeySource = AccountKeySource(account)
        val contacts = ContactsManagerImpl(account)
        val lockers = LockersClient.create(
            rpcClient = rpcClient,
            storeDelegate = InMemoryStoreDelegate(),
            keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
            keySource = accountKeySource,
            appVersion = Version(0, 0, 1),
            lockKeySource = accountKeySource,
        )
        account.start(lockers)
        contacts.start(lockers)
        account.createAccount()
        account.lifecycle.first { it is AccountManager.Lifecycle.Ready }
        return Party(contacts, lockers)
    }

    private fun profileId() = ProfileId { rawValue = Random.nextBytes(33) }

    private fun List<Contact>.of(profileId: ProfileId): Contact? =
        firstOrNull { it.profileId.rawValue.contentEquals(profileId.rawValue) }

    @Test(timeout = 60_000)
    fun `an added contact surfaces as a friend`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val party = newParty(server.rpcClient)
            val friend = profileId()

            party.contacts.add(friend)

            val contact = party.contacts.watchContacts().first { it.of(friend) != null }.of(friend)
            assertNotNull(contact)
            assertNotNull(contact.friendedAtMillis)
            assertNull(contact.blockedAtMillis)

            party.close()
        }

    @Test(timeout = 60_000)
    fun `friend and block coexist and clear independently`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val party = newParty(server.rpcClient)
            val target = profileId()
            // A second, always-friended contact acts as a hydration sentinel: waiting for it to be
            // present proves the watch has loaded real state past its initial empty emission, so a
            // predicate about `target` being absent can't be satisfied spuriously by that empty seed.
            val keeper = profileId()
            party.contacts.add(keeper)

            // Friending then blocking the same profile leaves both marks set at once.
            party.contacts.add(target)
            party.contacts.block(target)
            val both = party.contacts.watchContacts()
                .first { it.of(keeper) != null && it.of(target)?.let { c -> c.friendedAtMillis != null && c.blockedAtMillis != null } == true }
                .of(target)
            assertNotNull(both)

            // Unfriending clears only the friend mark; the block remains, so the entry survives.
            party.contacts.unfriend(target)
            val blockedOnly = party.contacts.watchContacts()
                .first { it.of(keeper) != null && it.of(target)?.let { c -> c.friendedAtMillis == null } == true }
                .of(target)
            assertNotNull(blockedOnly)
            assertNotNull(blockedOnly.blockedAtMillis)

            // Unblocking clears the last remaining mark, so the contact is dropped entirely.
            party.contacts.unblock(target)
            party.contacts.watchContacts().first { it.of(keeper) != null && it.of(target) == null }

            party.close()
        }

    @Test(timeout = 60_000)
    fun `a restored account recovers its contacts`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val accountStore = KeyValueStore(InMemoryKeyValueStoreDelegate())
            val friend = profileId()
            val blocked = profileId()

            val device1 = newParty(server.rpcClient, accountStore)
            device1.contacts.add(friend)
            device1.contacts.block(blocked)
            device1.contacts.watchContacts().first { it.of(friend) != null && it.of(blocked) != null }
            device1.close()

            // A fresh device restoring the same account (the contacts live in the synced account room)
            // recovers both entries with the same friend/block state.
            val device2 = newParty(server.rpcClient, accountStore)
            val restored = device2.contacts.watchContacts().first { it.of(friend) != null && it.of(blocked) != null }
            assertEquals(1, restored.count { it.profileId.rawValue.contentEquals(friend.rawValue) })
            assertNotNull(restored.of(friend)?.friendedAtMillis)
            assertNull(restored.of(friend)?.blockedAtMillis)
            assertNotNull(restored.of(blocked)?.blockedAtMillis)
            assertNull(restored.of(blocked)?.friendedAtMillis)

            device2.close()
        }
}
