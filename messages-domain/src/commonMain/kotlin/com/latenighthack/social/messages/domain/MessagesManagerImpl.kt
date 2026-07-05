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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
 * signature, deduplicated against the persistent [MessageStore], and mirrored into memory for any
 * room that is loaded. A room's history is loaded lazily — a [RoomMessageList] is populated the first
 * time the room is watched (or a message is composed for it) and cached from there — so the manager
 * never loads every room's messages up front. A newly stored message bumps its room's `updated_at`
 * through [RoomsManager.markUpdated]. Resumable: [start] launches the loops and resumes the outbox,
 * [stop] cancels them and leaves the manager (and its cached rooms) reusable.
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

    // One lazily-loaded, cached list of messages per room the app has touched. Guarded by [roomsMutex].
    private val roomLists = mutableMapOf<RoomId, RoomMessageList>()
    private val roomsMutex = Mutex()

    // Cross-room receive state, guarded by [mutex]: current membership per watched room, notifications
    // held until their sender's membership syncs, and the set of rooms already subscribed for events.
    private val mutex = Mutex()
    private val members = mutableMapOf<RoomId, Set<ProfileId>>()
    private val unverified = mutableMapOf<RoomId, MutableList<SignedContent>>()
    private val subscribedRooms = mutableSetOf<RoomId>()

    // Guards the one-time store init that both send() and run() can trigger concurrently.
    private val initMutex = Mutex()

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
        // A prior stop() cancelled the collectors and drain loop, so forget the per-room subscription
        // state and re-establish it below. Cached room lists are kept (the store is unchanged).
        mutex.withLock {
            subscribedRooms.clear()
            members.clear()
            unverified.clear()
        }
        ensureStores()

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
        val senderId = rooms.localProfile(roomId) ?: error("not a member of this room")
        ensureStores()
        val list = roomList(roomId)

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
            // Optimistic local echo: store and show the message as SENDING at once, then durably queue
            // it. The stored row also dedups the write's own echoed notification.
            list.addOwn(messageId, payload, signed)
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
        wake.trySend(Unit)
    }

    override suspend fun retry(roomId: RoomId, messageId: MessageId) {
        ensureStores()
        val dead = deadLetters.getDeadLettered(roomId, messageId) ?: return
        val signed = dead.message ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        pending.savePending(dead.copy(attempts = 0L, nextAttemptMillis = now))
        deadLetters.deleteDeadLettered(roomId, messageId)
        roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING)
        wake.trySend(Unit)
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
        val signed = entry.message ?: run { pending.deletePending(roomId, messageId); return }
        try {
            // Empty body ({ it } keeps it unchanged); the message rides as the notification payload.
            messageClient(lockers).updateLocker(
                roomId,
                MessagesKeyspaces.MESSAGING_LOCKER,
                notificationBuilder = { payload { rawValue = signed.toByteArray() } },
            ) { it }
            pending.deletePending(roomId, messageId)
            roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT)
            bestEffortBump(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val attempts = entry.attempts + 1
            if (attempts >= maxAttempts) {
                deadLetters.saveDeadLettered(entry.copy(attempts = attempts))
                pending.deletePending(roomId, messageId)
                roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_FAILED)
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

    override fun watchMessages(roomId: RoomId): Flow<List<MessageEntry>> = flow {
        val list = roomList(roomId)
        list.ensureLoaded()
        emitAll(list.entries)
    }.distinctUntilChanged()

    override fun watchMessageIds(roomId: RoomId): Flow<List<MessageId>> = flow {
        val list = roomList(roomId)
        list.ensureLoaded()
        emitAll(list.entries.map { room -> room.map { MessageId(rawValue = it.payload.messageId) } })
    }.distinctUntilChanged()

    private suspend fun roomList(roomId: RoomId): RoomMessageList =
        roomsMutex.withLock { roomLists.getOrPut(roomId) { RoomMessageList(roomId) } }

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

    /**
     * One room's messages, loaded from the store on first use and cached in memory thereafter. Keeps
     * the manager's footprint proportional to the rooms actually touched, not every room on the device.
     * Its [mutex] serialises load, receive, and status changes for the room; the store (keyed by room +
     * message id) is the dedup source of truth, so an unloaded room still drops duplicates on receive.
     */
    private inner class RoomMessageList(private val roomId: RoomId) {
        val entries = MutableStateFlow<List<MessageEntry>>(emptyList())
        private val mutex = Mutex()
        private var loaded = false

        suspend fun ensureLoaded() = mutex.withLock { loadLocked() }

        private suspend fun loadLocked() {
            if (loaded) return
            entries.value = store.getMessagesForRoom(roomId).mapNotNull { local ->
                val signed = local.message ?: return@mapNotNull null
                val payload = runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull()
                    ?: return@mapNotNull null
                MessageEntry(payload, local.status)
            }.sortedBy { it.payload.sentAtMillis }
            loaded = true
        }

        /** Our own send: persist it as SENDING and show it immediately (loads history first). */
        suspend fun addOwn(messageId: MessageId, payload: MessagePayload, signed: SignedContent) = mutex.withLock {
            loadLocked()
            store.saveMessage(local(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING))
            putLocked(payload, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING)
        }

        /**
         * A verified incoming message: drop it if the store already has it (our own echo, a replay, a
         * duplicate); otherwise persist it and, if this room is loaded, reflect it in memory. Returns
         * true when it was newly stored, so the caller bumps the room.
         */
        suspend fun ingest(payload: MessagePayload, signed: SignedContent): Boolean = mutex.withLock {
            val messageId = MessageId(rawValue = payload.messageId)
            if (store.getMessage(roomId, messageId) != null) return@withLock false
            store.saveMessage(local(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT))
            if (loaded) putLocked(payload, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT)
            true
        }

        /** Persist a delivery-status change and reflect it in memory when the room is loaded. */
        suspend fun setStatus(messageId: MessageId, signed: SignedContent, status: MessageDeliveryStatus) = mutex.withLock {
            store.saveMessage(local(messageId, signed, status))
            if (loaded) {
                runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull()?.let { putLocked(it, status) }
            }
        }

        // Insert or replace the entry for a message id, keeping the list sorted by sentAtMillis.
        private fun putLocked(payload: MessagePayload, status: MessageDeliveryStatus) {
            val idList = payload.messageId.toList()
            val without = entries.value.filterNot { it.payload.messageId.toList() == idList }
            entries.value = (without + MessageEntry(payload, status)).sortedBy { it.payload.sentAtMillis }
        }

        private fun local(messageId: MessageId, signed: SignedContent, status: MessageDeliveryStatus) = LocalMessage {
            roomId = this@RoomMessageList.roomId.rawValue
            this.messageId = messageId
            message = signed
            this.status = status
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
        const val DEFAULT_BACKOFF_BASE_MILLIS = 1_000L
        const val DEFAULT_BACKOFF_CAP_MILLIS = 60_000L
        const val DEFAULT_IDLE_WAIT_MILLIS = 30_000L
    }
}
