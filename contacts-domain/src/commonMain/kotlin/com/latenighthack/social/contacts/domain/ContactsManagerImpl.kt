// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.contacts.domain

import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.TypedLockerClient
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.contacts.v1.ContactRecord
import com.latenighthack.social.contacts.v1.fromByteArray
import com.latenighthack.social.contacts.v1.toByteArray
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.withAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.latenighthack.social.runtime.TaskHealth
import com.latenighthack.social.runtime.recoverTask
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock

/**
 * Keeps the user's friend/block list as one [ContactRecord] locker per profile in the account room,
 * keyed by profile id. Writes are authorized by the account key already in the client's key-source
 * chain (the account room is locked to that key), so contacts needs no key source of its own and the
 * records are stored unsigned. Friend and block are independent fields: a mutation reads the current
 * record and rewrites only its own field, so setting one never clears the other. Resumable: [start]
 * subscribes the account room, [stop] leaves the manager reusable.
 */
class ContactsManagerImpl(
    private val account: AccountManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ContactsManager, DomainLifecycle {

    override val taskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
    private val runner = com.latenighthack.social.runtime.ManagerRunner(scope)
    private val lockers: LockersClient? get() = runner.token as? LockersClient

    override fun start(lockers: LockersClient) {
        runner.start(lockers) {
             recoverTask(taskHealth) { run(lockers) } }
    }

    override fun stop() {
        runner.stop()
    }

    override suspend fun stopAndJoin() {
        runner.stopAndJoin()
    }

    // Warm the account room once the account is ready so the watch and mutations see current
    // versions; the watch subscribes on its own too, so this is a best-effort head start.
    private suspend fun run(lockers: LockersClient) {
        contactsClient(lockers).subscribeToRoom(accountRoom(), waitForSubscription = false)
    }

    override suspend fun add(profileId: ProfileId): Unit = account.withAccount {
        runner.command { addOwned(profileId) }
    }

    private suspend fun addOwned(profileId: ProfileId) {
        val now = Clock.System.now().toEpochMilliseconds()
        contactsClient(requireLockers()).updateLocker(accountRoom(), lockerId(profileId)) { current ->
            ContactRecord(friend = ContactRecord.Friend(addedAtMillis = now), block = current.block)
        }
    }

    override suspend fun block(profileId: ProfileId): Unit = account.withAccount {
        runner.command { blockOwned(profileId) }
    }

    private suspend fun blockOwned(profileId: ProfileId) {
        val now = Clock.System.now().toEpochMilliseconds()
        contactsClient(requireLockers()).updateLocker(accountRoom(), lockerId(profileId)) { current ->
            ContactRecord(friend = current.friend, block = ContactRecord.Block(blockedAtMillis = now))
        }
    }

    override suspend fun unfriend(profileId: ProfileId): Unit = account.withAccount { runner.command { clearField(
        profileId,
        keep = { current -> ContactRecord(friend = null, block = current.block) },
    ) } }

    override suspend fun unblock(profileId: ProfileId): Unit = account.withAccount { runner.command { clearField(
        profileId,
        keep = { current -> ContactRecord(friend = current.friend, block = null) },
    ) } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun watchContacts(): Flow<List<Contact>> =
        account.lifecycle.flatMapLatest { state ->
            if (state !is AccountManager.Lifecycle.Ready) flowOf(emptyList())
            else contactsClient(requireLockers()).watchAll(state.privateRoom, ContactsKeyspaces.CONTACTS).map { records ->
                records.filterValues { it.friend != null || it.block != null }.map { (lockerId, record) ->
                    Contact(ProfileId { rawValue = lockerId.rawValue }, record.friend?.addedAtMillis, record.block?.blockedAtMillis)
                }
            }
        }.distinctUntilChanged()

    // Clears one field: keeps the record (rewriting via [keep]) only if the other field is still set
    // (per [otherPresent]), otherwise deletes the locker so an empty record is never stored.
    private suspend fun clearField(
        profileId: ProfileId,
        keep: (ContactRecord) -> ContactRecord,
    ) {
        val client = contactsClient(requireLockers())
        val room = accountRoom()
        val id = lockerId(profileId)
        client.updateLocker(room, id) { keep(it) }
    }

    private suspend fun accountRoom(): RoomId =
        (account.lifecycle.first { it is AccountManager.Lifecycle.Ready } as AccountManager.Lifecycle.Ready).privateRoom

    private fun requireLockers(): LockersClient = lockers ?: error("contacts requires start(lockers) first")

    private fun lockerId(profileId: ProfileId): LockerId = LockerId(profileId.rawValue, ContactsKeyspaces.CONTACTS)

    private fun contactsClient(lockers: LockersClient): TypedLockerClient<ContactRecord> =
        lockers.typed(ContactsKeyspaces.CONTACTS, ContactRecord::toByteArray, ContactRecord.Companion::fromByteArray)
}
