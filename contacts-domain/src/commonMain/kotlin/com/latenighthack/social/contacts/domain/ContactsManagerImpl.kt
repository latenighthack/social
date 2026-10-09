// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.contacts.domain

import com.latenighthack.social.observability.*

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
) : ContactsManager, DomainLifecycle, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry


    private var job: Job? = null
    private var lockers: LockersClient? = null

    override fun start(lockers: LockersClient) {
        socialTelemetry.event("contacts", "start")
        this.lockers = lockers
        if (job?.isActive == true) return
        job = scope.launch { socialTelemetry.measure("contacts", "start") { run(lockers) } }
    }

    override fun stop() {
        socialTelemetry.event("contacts", "stop")
        job?.cancel()
        job = null
    }

    // Warm the account room once the account is ready so the watch and mutations see current
    // versions; the watch subscribes on its own too, so this is a best-effort head start.
    private suspend fun run(lockers: LockersClient) {
        contactsClient(lockers).subscribeToRoom(accountRoom(), waitForSubscription = false)
    }

    override suspend fun add(profileId: ProfileId): Unit = socialTelemetry.measure("contacts", "add") {
        val now = Clock.System.now().toEpochMilliseconds()
        contactsClient(requireLockers()).updateLocker(accountRoom(), lockerId(profileId)) { current ->
            ContactRecord(friend = ContactRecord.Friend(addedAtMillis = now), block = current.block)
        }
    }

    override suspend fun block(profileId: ProfileId): Unit = socialTelemetry.measure("contacts", "block") {
        val now = Clock.System.now().toEpochMilliseconds()
        contactsClient(requireLockers()).updateLocker(accountRoom(), lockerId(profileId)) { current ->
            ContactRecord(friend = current.friend, block = ContactRecord.Block(blockedAtMillis = now))
        }
    }

    override suspend fun unfriend(profileId: ProfileId) = clearField(
        profileId, operation = "unfriend",
        otherPresent = { it.block != null },
        keep = { current -> ContactRecord(friend = null, block = current.block) },
    )

    override suspend fun unblock(profileId: ProfileId) = clearField(
        profileId, operation = "unblock",
        otherPresent = { it.friend != null },
        keep = { current -> ContactRecord(friend = current.friend, block = null) },
    )

    override fun watchContacts(): Flow<List<Contact>> = (flow {
        val client = contactsClient(requireLockers())
        val room = accountRoom()
        emitAll(
            client.watchAll(room, ContactsKeyspaces.CONTACTS).map { records ->
                records.map { (lockerId, record) ->
                    Contact(
                        profileId = ProfileId { rawValue = lockerId.rawValue },
                        friendedAtMillis = record.friend?.addedAtMillis,
                        blockedAtMillis = record.block?.blockedAtMillis,
                    )
                }
            },
        )
    }.distinctUntilChanged()

    // Clears one field: keeps the record (rewriting via [keep]) only if the other field is still set
    // (per [otherPresent]), otherwise deletes the locker so an empty record is never stored.
    ).socialObserved(socialTelemetry, "contacts")

    private suspend fun clearField(
        profileId: ProfileId,
        operation: String,
        otherPresent: (ContactRecord) -> Boolean,
        keep: (ContactRecord) -> ContactRecord,
    ): Unit = socialTelemetry.measure("contacts", operation) {
        val client = contactsClient(requireLockers())
        val room = accountRoom()
        val id = lockerId(profileId)
        val current = client.getLocker(room, id) ?: run { result("noop"); return@measure }
        if (otherPresent(current)) {
            client.updateLocker(room, id) { keep(it) }
        } else {
            client.deleteLocker(room, id)
        }
    }

    private suspend fun accountRoom(): RoomId =
        (account.lifecycle.first { it is AccountManager.Lifecycle.Ready } as AccountManager.Lifecycle.Ready).privateRoom

    private fun requireLockers(): LockersClient = lockers ?: error("contacts requires start(lockers) first")

    private fun lockerId(profileId: ProfileId): LockerId = LockerId(profileId.rawValue, ContactsKeyspaces.CONTACTS)

    private fun contactsClient(lockers: LockersClient): TypedLockerClient<ContactRecord> =
        lockers.typed(ContactsKeyspaces.CONTACTS, ContactRecord::toByteArray, ContactRecord.Companion::fromByteArray)
}
