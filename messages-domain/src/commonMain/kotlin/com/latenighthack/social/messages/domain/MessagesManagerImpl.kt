package com.latenighthack.social.messages.domain

import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.common.v1.RoomId
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
import com.latenighthack.social.runtime.DomainLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Runs the messages feature over one gated MESSAGING locker per room. Sending enqueues the message
 * into a durable outbox and returns; a single global drain loop delivers each via that locker's
 * notification payload (empty body, so the version bump under the room's lock linearizes concurrent
 * sends), retrying with exponential backoff and dead-lettering after [maxAttempts]. Receiving folds
 * the notification stream: each message is verified against current membership and the sender's
 * signature, deduplicated by message id, and mirrored into a persistent [MessageStore] that holds
 * received, sent, and not-yet-sent messages alike. A newly stored message bumps its room's
 * `updated_at` through [RoomsManager.markUpdated]. Resumable: [start] launches the loops and resumes
 * the outbox, [stop] cancels them and leaves the manager reusable.
 */
class MessagesManagerImpl(
    private val rooms: RoomsManager,
    private val myProfiles: MyProfilesManager,
    private val delegate: StoreDelegate,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffBaseMillis: Long = DEFAULT_BACKOFF_BASE_MILLIS,
    private val backoffCapMillis: Long = DEFAULT_BACKOFF_CAP_MILLIS,
    private val idleWaitMillis: Long = DEFAULT_IDLE_WAIT_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MessagesManager, DomainLifecycle {

    private val store = MessageStore(delegate)
    private val pending = PendingMessageStore(delegate)
    private val deadLetters = DeadLetterStore(delegate)

    // Each room's messages, kept sorted by sentAtMillis, with their local delivery status.
    private val _messages = MutableStateFlow<Map<RoomId, List<MessageEntry>>>(emptyMap())

    // Guards _messages, seen, members, and unverified, all reachable concurrently by the notification
    // collector, the membership collectors, and send()/the drain loop on a multi-threaded dispatcher.
    private val mutex = Mutex()

    // Guards the one-time store init that both send() and run() can trigger concurrently.
    private val initMutex = Mutex()

    // Message ids already stored (seeded from the cache at start, added at enqueue), so our own echoed
    // sends, replays, and duplicates are dropped on receive.
    private val seen = mutableSetOf<List<Byte>>()

    // Current membership per watched room, kept fresh by the per-room membership collectors.
    private val members = mutableMapOf<RoomId, Set<ProfileId>>()

    // Notifications whose sender is not yet a known member, held until that room's membership advances.
    private val unverified = mutableMapOf<RoomId, MutableList<SignedContent>>()

    private val subscribedRooms = mutableSetOf<RoomId>()

    // Nudges the drain loop to attempt immediately when a new message is enqueued.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var job: Job? = null
    private var lockers: LockersClient? = null
    private var storesInitialized = false

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
        if (job?.isActive == true) return
        job = scope.launch { run(lockers) }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(lockers: LockersClient) {
        // A prior stop() cancelled the collectors and drain loop, so forget the per-room state and
        // re-establish it below (seen is re-seeded from the cache during hydrate).
        mutex.withLock {
            subscribedRooms.clear()
            members.clear()
            unverified.clear()
        }
        ensureStores()
        hydrate()

        supervisorScope {
            launch { drainLoop(lockers) }
            launch { messageClient(lockers).notifications.collect { onNotification(it) } }

            rooms.watchRooms().collect { roomIds ->
                for (roomId in roomIds) {
                    if (mutex.withLock { subscribedRooms.add(roomId) }) {
                        launch { messageClient(lockers).subscribeToRoom(roomId) }
                        launch { watchMembership(roomId) }
                    }
                }
            }
        }
    }

    // Merge the persisted messages into memory under the lock, so a concurrent enqueue (which adds to
    // seen + memory atomically) is never clobbered — a message already in seen is skipped here.
    private suspend fun hydrate() {
        mutex.withLock {
            for (local in store.getAllMessages()) {
                val messageId = local.messageId ?: continue
                val idList = messageId.rawValue.toList()
                if (idList in seen) continue
                val signed = local.message ?: continue
                val payload = runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull() ?: continue
                seen.add(idList)
                addToMemory(RoomId(rawValue = local.roomId), payload, local.status)
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
        val idList = payload.messageId.toList()

        val stored = mutex.withLock {
            if (idList in seen) return
            if (senderId !in members[roomId].orEmpty()) {
                // Sender's membership hasn't synced yet; hold and re-check when it advances.
                unverified.getOrPut(roomId) { mutableListOf() }.add(signed)
                return
            }
            val senderKey = runCatching { Secp256r1PublicKey.decode(payload.senderProfileId) }.getOrNull() ?: return
            if (!MessageSigning.verify(signed, senderKey)) return
            seen.add(idList)
            store.saveMessage(LocalMessage {
                this.roomId = roomId.rawValue
                messageId = MessageId(rawValue = payload.messageId)
                message = signed
                status = MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT
            })
            addToMemory(roomId, payload, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT)
            true
        }
        if (stored) bestEffortBump(roomId)
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
        val senderId = rooms.localProfile(roomId) ?: error("not a member of this room")
        ensureStores()

        // A draft fans out into one message per component: each attachment's photo component in order,
        // then the text as a Text component (skipped when blank, so an attachment-only send is fine).
        val components = draft.attachments.map { it.component ?: Component { } } +
            listOfNotNull(draft.text.takeIf { it.isNotBlank() }?.let { text ->
                Component { contents.text { this.text = text } }
            })

        for (component in components) {
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
            enqueue(roomId, messageId, payload, signed)
        }
        wake.trySend(Unit)
    }

    override suspend fun retry(roomId: RoomId, messageId: MessageId) {
        ensureStores()
        val dead = deadLetters.getAllDeadLettered().firstOrNull {
            it.messageId?.rawValue?.contentEquals(messageId.rawValue) == true
        } ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        pending.savePending(dead.copy(attempts = 0L, nextAttemptMillis = now))
        deadLetters.deleteDeadLettered(messageId)
        markStatus(roomId, messageId, dead.message, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING)
        wake.trySend(Unit)
    }

    // Optimistic local echo: store and show the message as SENDING at once, and durably queue it for
    // the drain loop to deliver. Adding to seen here is what dedups the write's own echoed notification.
    private suspend fun enqueue(roomId: RoomId, messageId: MessageId, payload: MessagePayload, signed: SignedContent) {
        mutex.withLock {
            seen.add(messageId.rawValue.toList())
            store.saveMessage(LocalMessage {
                this.roomId = roomId.rawValue
                this.messageId = messageId
                message = signed
                status = MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING
            })
            addToMemory(roomId, payload, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING)
        }
        val now = Clock.System.now().toEpochMilliseconds()
        pending.savePending(PendingMessage {
            this.roomId = roomId.rawValue
            this.messageId = messageId
            message = signed
            attempts = 0
            nextAttemptMillis = now
            createdAtMillis = now
        })
    }

    private suspend fun drainLoop(lockers: LockersClient) {
        while (true) {
            val now = Clock.System.now().toEpochMilliseconds()
            for (entry in pending.getAllPending().sortedBy { it.createdAtMillis }) {
                if (entry.nextAttemptMillis <= now) attemptSend(lockers, entry)
            }
            val soonest = pending.getAllPending().minOfOrNull { it.nextAttemptMillis }
            val wait = if (soonest == null) idleWaitMillis
            else (soonest - Clock.System.now().toEpochMilliseconds()).coerceIn(1L, idleWaitMillis)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private suspend fun attemptSend(lockers: LockersClient, entry: PendingMessage) {
        val messageId = entry.messageId ?: return
        val roomId = RoomId(rawValue = entry.roomId)
        val signed = entry.message ?: run { pending.deletePending(messageId); return }
        try {
            // Empty body ({ it } keeps it unchanged); the message rides as the notification payload.
            messageClient(lockers).updateLocker(
                roomId,
                MessagesKeyspaces.MESSAGING_LOCKER,
                notificationBuilder = { payload { rawValue = signed.toByteArray() } },
            ) { it }
            pending.deletePending(messageId)
            markStatus(roomId, messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT)
            bestEffortBump(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val attempts = entry.attempts + 1
            if (attempts >= maxAttempts) {
                deadLetters.saveDeadLettered(entry.copy(attempts = attempts))
                pending.deletePending(messageId)
                markStatus(roomId, messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_FAILED)
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

    // Re-persist the message with a new status and flip it in memory. Idempotent for a message that
    // may have been dropped from memory (e.g. after a stop()): it re-adds it from its parsed payload.
    private suspend fun markStatus(roomId: RoomId, messageId: MessageId, signed: SignedContent?, status: MessageDeliveryStatus) {
        val envelope = signed ?: return
        val payload = runCatching { MessagePayload.fromByteArray(envelope.content) }.getOrNull() ?: return
        mutex.withLock {
            store.saveMessage(LocalMessage {
                this.roomId = roomId.rawValue
                this.messageId = messageId
                message = envelope
                this.status = status
            })
            val list = _messages.value[roomId].orEmpty()
            val idList = messageId.rawValue.toList()
            _messages.value = if (list.any { it.payload.messageId.toList() == idList }) {
                _messages.value + (roomId to list.map { e ->
                    if (e.payload.messageId.toList() == idList) e.copy(status = status) else e
                })
            } else {
                seen.add(idList)
                _messages.value + (roomId to (list + MessageEntry(payload, status)).sortedBy { it.payload.sentAtMillis })
            }
        }
    }

    // Adds a message to _messages sorted by sentAtMillis. Callers hold [mutex] and have already guarded
    // against duplicates via [seen].
    private fun addToMemory(roomId: RoomId, payload: MessagePayload, status: MessageDeliveryStatus) {
        val list = _messages.value[roomId].orEmpty()
        val updated = (list + MessageEntry(payload, status)).sortedBy { it.payload.sentAtMillis }
        _messages.value = _messages.value + (roomId to updated)
    }

    override fun watchMessages(roomId: RoomId): Flow<List<MessageEntry>> =
        _messages.map { it[roomId].orEmpty() }.distinctUntilChanged()

    override fun watchMessageIds(roomId: RoomId): Flow<List<MessageId>> =
        _messages.map { room -> room[roomId].orEmpty().map { MessageId(rawValue = it.payload.messageId) } }
            .distinctUntilChanged()

    private suspend fun ensureStores() {
        if (storesInitialized) return
        initMutex.withLock {
            if (storesInitialized) return
            store.prepare()
            pending.prepare()
            deadLetters.prepare()
            delegate.createStores()
            storesInitialized = true
        }
    }

    private fun messageClient(lockers: LockersClient): TypedLockerClient<SignedContent> =
        lockers.typed(MessagesKeyspaces.MESSAGING, SignedContent::toByteArray, SignedContent.Companion::fromByteArray)

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
        const val DEFAULT_BACKOFF_BASE_MILLIS = 1_000L
        const val DEFAULT_BACKOFF_CAP_MILLIS = 60_000L
        const val DEFAULT_IDLE_WAIT_MILLIS = 30_000L
    }
}
