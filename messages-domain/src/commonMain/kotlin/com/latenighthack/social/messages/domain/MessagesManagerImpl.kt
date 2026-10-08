// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.messages.domain

import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
import com.latenighthack.ktstore.Database
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.connector.IncomingNotification
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.TypedLockerClient
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.common.v1.fromByteArray
import com.latenighthack.social.common.v1.toByteArray
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.LocalMessage
import com.latenighthack.social.messages.v1.MessageDeliveryStatus
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.MessagePayload
import com.latenighthack.social.messages.v1.PendingMessage
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray
import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.runtime.*
import com.latenighthack.social.messages.v1.copy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.latenighthack.social.runtime.TaskHealth
import com.latenighthack.social.runtime.recoverTask
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.random.Random

/**
 * Runs the messages feature over one gated MESSAGING locker per room. Sending enqueues the message
 * into a durable outbox and returns; a single global drain loop delivers each via that locker's
 * notification payload (empty body, so the version bump under the room's lock linearizes concurrent
 * sends), retrying with exponential backoff and dead-lettering after [maxAttempts]. Receiving folds
 * the notification stream: each message is verified against current membership and the sender's
 * signature, deduplicated against the persistent [MessageStore], and mirrored into memory for any
 * room that is loaded. A room's history is loaded lazily — a [RoomMessageList] is populated the first
 * time the room is watched (or a message is composed for it) and cached from there — so the manager
 * never loads every room's messages up front. A room's `updated_at` is bumped through
 * [RoomsManager.markUpdated] the moment a message is sent (at enqueue, reflecting the user's intent)
 * and when one is received. Resumable: [start] launches the loops and resumes the outbox, [stop]
 * cancels them and leaves the manager (and its cached rooms) reusable.
 */
class MessagesManagerImpl(
    private val rooms: RoomsManager,
    private val myProfiles: MyProfilesManager,
    private val database: Database,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffBaseMillis: Long = DEFAULT_BACKOFF_BASE_MILLIS,
    private val backoffCapMillis: Long = DEFAULT_BACKOFF_CAP_MILLIS,
    private val idleWaitMillis: Long = DEFAULT_IDLE_WAIT_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val session: AccountSession? = null,
) : MessagesManager, DomainLifecycle {

    private val store = MessageStore(database)
    private val pending = PendingMessageStore(database)
    private val deadLetters = DeadLetterStore(database)

    // One lazily-loaded, cached list of messages per room the app has touched. Guarded by [roomsMutex].
    private val roomLists = mutableMapOf<RoomId, RoomMessageList>()
    private val roomsMutex = Mutex()

    // Cross-room receive state, guarded by [mutex]: current membership per watched room, notifications
    // held until their sender's membership syncs, and the set of rooms already subscribed for events.
    private val mutex = Mutex()
    private val members = mutableMapOf<RoomId, Set<ProfileId>>()
    private val unverified = mutableMapOf<RoomId, MutableList<SignedContent>>()
    private val subscribedRooms = mutableSetOf<RoomId>()

    // Completes on first start — by then the app has created the prepared stores — gating store access.
    private val ready = CompletableDeferred<Unit>()

    // Nudges the drain loop to attempt immediately when a new message is enqueued.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override val taskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
    private var job: Job? = null
    private var lockers: LockersClient? = null

    override suspend fun prepare() {
        store.prepare()
        pending.prepare()
        deadLetters.prepare()
    }

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
        if (job?.isActive == true) return
        job = scope.launch { recoverTask(taskHealth) { run(lockers) } }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(lockers: LockersClient) {
        session.ownerChanges().collectLatest { owner ->
            roomsMutex.withLock { roomLists.clear() }
            if (owner != null) {
                migrateOwnership(owner)
                runForOwner(lockers)
            }
        }
    }

    private suspend fun migrateOwnership(owner: String) {
        if (session == null) return
        database.transaction("social.messages") {
            for (row in store.getAllMessages()) if (row.ownerAccountId.isEmpty()) {
                val id = row.messageId ?: continue
                if (session.owns("")) store.saveMessage(row.copy(ownerAccountId = owner))
                else store.deleteMessage(RoomId(rawValue = row.roomId), id)
            }
            for (row in pending.getAllPending()) if (row.ownerAccountId.isEmpty()) {
                val id = row.messageId ?: continue
                if (session.owns("")) pending.savePending(row.copy(ownerAccountId = owner))
                else pending.deletePending(RoomId(rawValue = row.roomId), id)
            }
            for (row in deadLetters.getAllDeadLetters()) if (row.ownerAccountId.isEmpty()) {
                val id = row.messageId ?: continue
                if (session.owns("")) deadLetters.saveDeadLettered(row.copy(ownerAccountId = owner))
                else deadLetters.deleteDeadLettered(RoomId(rawValue = row.roomId), id)
            }
        }
    }

    private suspend fun runForOwner(lockers: LockersClient) {
        // A prior stop() cancelled the collectors and drain loop, so forget the per-room subscription
        // state and re-establish it below. Cached room lists are kept (the store is unchanged).
        mutex.withLock {
            subscribedRooms.clear()
            members.clear()
            unverified.clear()
        }
        if (!ready.isCompleted) ready.complete(Unit)

        coroutineScope {
            launch { drainLoop(lockers) }
            launch { messageClient(lockers).notifications.collect { onNotification(it) } }

            rooms.watchRooms().collect { roomIds ->
                for (roomId in roomIds) {
                    if (mutex.withLock { subscribedRooms.add(roomId) }) {
                        launch { messageClient(lockers).subscribeToRoom(roomId) }
                        launch { watchMembership(roomId) }
                        launch {
                            messageClient(lockers).watchAll(roomId).collect { snapshot ->
                                for ((id, signed) in snapshot) {
                                    val payload = runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull() ?: continue
                                    if (!payload.messageId.contentEquals(id.rawValue)) continue
                                    tryIngest(roomId, signed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun watchMembership(roomId: RoomId) {
        rooms.watchMembers(roomId).collect { memberList ->
            val set = memberList.toSet()
            val buffered = mutex.withLock {
                members[roomId] = set
                unverified.remove(roomId).orEmpty()
            }
            // Membership advanced: re-evaluate anything that was held because its sender was unknown.
            for (signed in buffered) tryIngest(roomId, signed)
        }
    }

    private suspend fun onNotification(notification: IncomingNotification) {
        val signed = runCatching { SignedContent.fromByteArray(notification.payload) }.getOrNull() ?: return
        // A malformed or transiently-failing message must not tear down the shared notification collector.
        try {
            tryIngest(notification.roomId, signed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun tryIngest(roomId: RoomId, signed: SignedContent) {
        val payload = runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull() ?: return
        if (!payload.roomId.contentEquals(roomId.rawValue)) return
        val senderId = ProfileId { rawValue = payload.senderProfileId }

        val isMember = mutex.withLock {
            if (senderId in members[roomId].orEmpty()) {
                true
            } else {
                // Sender's membership hasn't synced yet; hold and re-check when it advances.
                unverified.getOrPut(roomId) { mutableListOf() }.add(signed)
                false
            }
        }
        if (!isMember) return

        // The signature proves the author controls senderProfileId; the membership check above is what
        // stops a member posting under a profile that isn't in the room.
        val senderKey = runCatching { Secp256r1PublicKey.decode(payload.senderProfileId) }.getOrNull() ?: return
        if (!MessageSigning.verify(signed, senderKey)) return

        if (roomList(roomId).ingest(payload, signed)) bestEffortBump(roomId)
    }

    // Bump the room's updated_at to the front. Best-effort: a lost bump (network error, shutdown) must
    // never fail a send or tear down a collector — the send/receive itself is what matters.
    private suspend fun bestEffortBump(roomId: RoomId) {
        try {
            rooms.markUpdated(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    override suspend fun send(roomId: RoomId, draft: Draft) {
        session.currentOwner()
        val senderId = rooms.localProfile(roomId) ?: error("not a member of this room")
        val list = roomList(roomId)

        // A draft fans out into one message per component: each attachment's photo component in order,
        // then the text as a Text component (skipped when blank, so an attachment-only send is fine).
        val components = draft.attachments.map { it.component ?: Component { } } +
            listOfNotNull(draft.text.takeIf { it.isNotBlank() }?.let { text ->
                Component { contents.text { this.text = text } }
            })

        val prepared = components.map { component ->
            val messageId = MessageId(rawValue = Random.nextBytes(32))
            val payload = MessagePayload(
                roomId = roomId.rawValue,
                senderProfileId = senderId.rawValue,
                sentAtMillis = Clock.System.now().toEpochMilliseconds(),
                component = component,
                messageId = messageId.rawValue,
            )
            val signed = myProfiles.sign(senderId, MessageSigning.LABEL, payload.toByteArray())
                ?: error("no signing key for the room's profile")
            Triple(messageId, payload, signed)
        }
        list.addOwn(prepared)
        wake.trySend(Unit)
        // Bump the room to the front the moment the user sends, reflecting their intent — not when the
        // message eventually lands. Launched (not awaited) and best-effort: markUpdated reorders the
        // room list locally first, so the send neither blocks on nor fails from the synced write.
        scope.launch { bestEffortBump(roomId) }
    }

    override suspend fun retry(roomId: RoomId, messageId: MessageId) {
        ready.await()
        val dead = deadLetters.getDeadLettered(roomId, messageId) ?: return
        if (!session.owns(dead.ownerAccountId)) return
        val signed = dead.message ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING) {
            pending.savePending(dead.copy(attempts = 0L, nextAttemptMillis = now))
            deadLetters.deleteDeadLettered(roomId, messageId)
        }
        wake.trySend(Unit)
    }

    private suspend fun drainLoop(lockers: LockersClient) {
        while (true) {
            val now = Clock.System.now().toEpochMilliseconds()
            for (entry in pending.getAllPending().filter { session.owns(it.ownerAccountId) }.sortedBy { it.createdAtMillis }) {
                if (entry.nextAttemptMillis <= now) attemptSend(lockers, entry)
            }
            val soonest = pending.getAllPending().filter { session.owns(it.ownerAccountId) }.minOfOrNull { it.nextAttemptMillis }
            val wait = if (soonest == null) idleWaitMillis
            else (soonest - Clock.System.now().toEpochMilliseconds()).coerceIn(1L, idleWaitMillis)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private suspend fun attemptSend(lockers: LockersClient, entry: PendingMessage) {
        val messageId = entry.messageId ?: return
        val roomId = RoomId(rawValue = entry.roomId)
        val signed = entry.message ?: run { pending.deletePending(roomId, messageId); return }
        try {
            // Empty body ({ it } keeps it unchanged); the message rides as the notification payload.
            messageClient(lockers).updateLocker(
                roomId,
                LockerId(messageId.rawValue, MessagesKeyspaces.MESSAGING),
                notificationBuilder = { payload { rawValue = signed.toByteArray() } },
            ) { current ->
                check(current.content.isEmpty() || current == signed) { "message id already contains different content" }
                signed
            }
            roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT) {
                pending.deletePending(roomId, messageId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val attempts = entry.attempts + 1
            if (attempts >= maxAttempts) {
                roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_FAILED) {
                    deadLetters.saveDeadLettered(entry.copy(attempts = attempts))
                    pending.deletePending(roomId, messageId)
                }
            } else {
                pending.savePending(entry.copy(
                    attempts = attempts,
                    nextAttemptMillis = Clock.System.now().toEpochMilliseconds() + backoffMillis(attempts),
                ))
            }
        }
    }

    private fun backoffMillis(attempts: Long): Long {
        val shift = (attempts - 1).coerceIn(0L, 20L).toInt()
        return (backoffBaseMillis shl shift).coerceAtMost(backoffCapMillis)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun watchMessages(roomId: RoomId): Flow<List<MessageEntry>> = session.ownerChanges().flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else watchOwnedMessages(roomId)
    }

    private fun watchOwnedMessages(roomId: RoomId): Flow<List<MessageEntry>> = flow {
        val list = roomList(roomId)
        list.ensureLoaded()
        emitAll(list.entries.map { if (session.owns(list.owner)) it else emptyList() })
    }.distinctUntilChanged()

    override fun watchMessageIds(roomId: RoomId): Flow<List<MessageId>> =
        watchMessages(roomId).map { entries -> entries.map { MessageId(rawValue = it.payload.messageId) } }.distinctUntilChanged()

    private suspend fun roomList(roomId: RoomId): RoomMessageList {
        ready.await()
        return roomsMutex.withLock {
            val owner = session.currentOwner()
            roomLists[roomId]?.takeIf { it.owner == owner } ?: RoomMessageList(roomId).also { roomLists[roomId] = it }
        }
    }

    private fun messageClient(lockers: LockersClient): TypedLockerClient<SignedContent> =
        lockers.typed(MessagesKeyspaces.MESSAGING, SignedContent::toByteArray, SignedContent.Companion::fromByteArray)

    /**
     * One room's messages, loaded from the store on first use and cached in memory thereafter. Keeps
     * the manager's footprint proportional to the rooms actually touched, not every room on the device.
     * Its [mutex] serialises load, receive, and status changes for the room; the store (keyed by room +
     * message id) is the dedup source of truth, so an unloaded room still drops duplicates on receive.
     */
    private inner class RoomMessageList(private val roomId: RoomId) {
        val owner = session.currentOwner()
        val entries = MutableStateFlow<List<MessageEntry>>(emptyList())

        // The message ids this room holds, maintained in memory so receive-dedup checks a set rather
        // than round-tripping the store. Populated by loadLocked and kept in sync by putLocked.
        private val seen = mutableSetOf<List<Byte>>()
        private val mutex = Mutex()
        private var loaded = false

        suspend fun ensureLoaded() = mutex.withLock { loadLocked() }

        private suspend fun loadLocked() {
            if (loaded) return
            entries.value = store.getMessagesForRoom(roomId).filter { session.owns(it.ownerAccountId) }.mapNotNull { local ->
                val messageId = local.messageId ?: return@mapNotNull null
                val signed = local.message ?: return@mapNotNull null
                val payload = runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull()
                    ?: return@mapNotNull null
                seen.add(messageId.rawValue.toList())
                MessageEntry(payload, local.status)
            }.sortedBy { it.payload.sentAtMillis }
            loaded = true
        }

        /** Our own send: persist it as SENDING and show it immediately (loads history first). */
        suspend fun addOwn(prepared: List<Triple<MessageId, MessagePayload, SignedContent>>) = mutex.withLock {
            loadLocked()
            database.transaction("social.messages") {
                for ((id, payload, signed) in prepared) {
                    store.saveMessage(local(id, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING))
                    pending.savePending(PendingMessage {
                        roomId = this@RoomMessageList.roomId.rawValue
                        messageId = id
                        ownerAccountId = owner
                        message = signed
                        createdAtMillis = payload.sentAtMillis
                        nextAttemptMillis = payload.sentAtMillis
                    })
                }
            }
            for ((_, payload, _) in prepared) putLocked(payload, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING)
        }

        /**
         * A verified incoming message: drop it if this room already has it (our own echo, a replay, a
         * duplicate). When the room is loaded that check is the fast in-memory id set; when it isn't,
         * it's a keyed store lookup and the message is persisted without pulling the room into memory.
         * Returns true when it was newly stored, so the caller bumps the room.
         */
        suspend fun ingest(payload: MessagePayload, signed: SignedContent): Boolean = mutex.withLock {
            val messageId = MessageId(rawValue = payload.messageId)
            val duplicate = if (loaded) payload.messageId.toList() in seen else store.getMessage(roomId, messageId) != null
            if (duplicate) return@withLock false
            store.saveMessage(local(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT))
            if (loaded) putLocked(payload, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT)
            true
        }

        /** Persist a delivery-status change and reflect it in memory when the room is loaded. */
        suspend fun setStatus(
            messageId: MessageId, signed: SignedContent, status: MessageDeliveryStatus,
            transition: suspend () -> Unit = {},
        ) = mutex.withLock {
            database.transaction("social.messages") {
                transition()
                store.saveMessage(local(messageId, signed, status))
            }
            if (loaded) {
                runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull()?.let { putLocked(it, status) }
            }
        }

        // Insert or replace the entry for a message id, keeping the list sorted by sentAtMillis and the
        // id set current. Callers hold [mutex].
        private fun putLocked(payload: MessagePayload, status: MessageDeliveryStatus) {
            val idList = payload.messageId.toList()
            seen.add(idList)
            val without = entries.value.filterNot { it.payload.messageId.toList() == idList }
            entries.value = (without + MessageEntry(payload, status)).sortedBy { it.payload.sentAtMillis }
        }

        private fun local(messageId: MessageId, signed: SignedContent, status: MessageDeliveryStatus): LocalMessage {
            if (!session.owns(owner)) throw CancellationException("account changed")
            return LocalMessage {
            roomId = this@RoomMessageList.roomId.rawValue
            this.messageId = messageId
            message = signed
            this.status = status
            ownerAccountId = owner
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
        const val DEFAULT_BACKOFF_BASE_MILLIS = 1_000L
        const val DEFAULT_BACKOFF_CAP_MILLIS = 60_000L
        const val DEFAULT_IDLE_WAIT_MILLIS = 30_000L
    }
}
