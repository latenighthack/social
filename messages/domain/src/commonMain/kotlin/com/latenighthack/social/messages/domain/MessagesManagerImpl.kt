// Manager recovery / untrusted input boundaries catch transport-specific failures; cancellation escapes.
@file:Suppress("TooGenericExceptionCaught")

// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.messages.domain

import com.latenighthack.social.runtime.OperationsObserver
import com.latenighthack.social.runtime.record
import com.latenighthack.social.runtime.measure

import com.latenighthack.social.observability.*

import kotlinx.coroutines.flow.asStateFlow

import com.latenighthack.social.messages.v1.BoundedMessagePayload

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
import kotlinx.coroutines.ensureActive
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
    private val observer: OperationsObserver = OperationsObserver.NONE,
) : MessagesManager, DomainLifecycle, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry

    override suspend fun prepare() = socialTelemetry.measure("messages", "prepare") { (run observedOperation@ {
        store.prepare()
        pending.prepare()
        deadLetters.prepare()

        }) }
    override fun start(lockers: LockersClient) = run { socialTelemetry.event("messages", "start"); (run observedOperation@ {
        runner.start(lockers) {
             recoverTask(mutableTaskHealth) { run(lockers) } }

        }) }
    override fun stop() = run { socialTelemetry.event("messages", "stop"); (run observedOperation@ {
        runner.stop()

        }) }
    override suspend fun send(roomId: RoomId, draft: Draft): Unit = socialTelemetry.measure("messages", "send") { (withSession { sendOwned(roomId, draft) }) }
    override suspend fun retry(roomId: RoomId, messageId: MessageId): Unit = socialTelemetry.measure("messages", "retry") { (withSession { retryOwned(roomId, messageId) }) }
    override fun watchMessages(roomId: RoomId): Flow<List<MessageEntry>>  = (session.ownerChanges().flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else watchOwnedMessages(roomId)
    }).socialObserved(socialTelemetry, "messages")
    override fun watchMessageIds(roomId: RoomId): Flow<List<MessageId>>  = (watchMessages(roomId).map { entries -> entries.map { MessageId(rawValue = it.payload.messageId) } }.distinctUntilChanged()).socialObserved(socialTelemetry, "messages")


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
    private val unverified = mutableMapOf<RoomId, com.latenighthack.social.runtime.BoundedCache<List<Byte>, SignedContent>>()
    private val elapsedClock = com.latenighthack.social.runtime.monotonicMillisClock()
    private val subscribedRooms = mutableSetOf<RoomId>()

    // Completes on first start — by then the app has created the prepared stores — gating store access.
    private val ready = CompletableDeferred<Unit>()

    // Nudges the drain loop to attempt immediately when a new message is enqueued.
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private data class RoomBump(val room: RoomId, val owner: String, val generation: Long)
    private val bumps = Channel<RoomBump>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private val mutableTaskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
    override val taskHealth = mutableTaskHealth.asStateFlow()
    private val runner = com.latenighthack.social.runtime.ManagerRunner(scope)
    private val lockers: LockersClient? get() = runner.token as? LockersClient


    override suspend fun stopAndJoin() {
        runner.stopAndJoin()
    }

    private suspend fun run(lockers: LockersClient) {
        session.ownerChanges().collectLatest { owner ->
            roomsMutex.withLock { roomLists.entries.removeAll { it.value.owner != owner || it.value.generation != (session?.generation?.value ?: 0L) } }
            if (owner != null) {
                migrateOwnership(owner)
                withSession {
                    if (session.currentOwner() != owner) throw CancellationException("account changed")
                    runForOwner(lockers)
                }
            }
        }
    }

    private suspend fun migrateOwnership(owner: String) {
        if (session == null) return
        com.latenighthack.social.runtime.storePages(database, MessageStoreDefinitionV2,
            MessageStoreDefinitionV2.roomIdKey).collect { page ->
            database.transaction("social.messages") {
                check(session.currentOwner() == owner)
                for (row in page) if (row.ownerAccountId.isEmpty()) {
                    val id = row.messageId ?: continue
                    if (session.owns("")) store.saveMessage(row.copy(ownerAccountId = owner))
                    store.deleteMessage(RoomId(rawValue = row.roomId), id)
                }
            }
        }
        pending.pages().collect { page ->
            database.transaction("social.messages") {
                check(session.currentOwner() == owner)
                for (row in page) if (row.ownerAccountId.isEmpty()) {
                    val id = row.messageId ?: continue
                    if (session.owns("")) pending.savePending(row.copy(ownerAccountId = owner))
                    pending.deletePending(RoomId(rawValue = row.roomId), id)
                }
            }
        }
        com.latenighthack.social.runtime.storePages(database, DeadLetterStoreDefinitionV2,
            DeadLetterStoreDefinitionV2.roomIdKey).collect { page ->
            database.transaction("social.messages") {
                check(session.currentOwner() == owner)
                for (row in page) if (row.ownerAccountId.isEmpty()) {
                    val id = row.messageId ?: continue
                    if (session.owns("")) deadLetters.saveDeadLettered(row.copy(ownerAccountId = owner))
                    deadLetters.deleteDeadLettered(RoomId(rawValue = row.roomId), id)
                }
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
            launch {
                for (bump in bumps) {
                    if (bump.owner == session.currentOwner() && bump.generation == (session?.generation?.value ?: 0L)) {
                        performBump(bump.room)
                    }
                }
            }
            launch { messageClient(lockers).notifications.collect { onNotification(it) } }

            val observers = mutableMapOf<RoomId, Job>()
            rooms.watchRooms().collect { roomIds ->
                val active = roomIds.toSet()
                mutex.withLock {
                    subscribedRooms.clear(); subscribedRooms.addAll(active)
                    members.keys.retainAll(active); unverified.keys.retainAll(active)
                }
                roomsMutex.withLock { roomLists.entries.removeAll { it.key !in active && it.value.observers == 0 } }
                for (removed in observers.keys.toList() - active) observers.remove(removed)?.cancel()
                for (roomId in active) if (roomId !in observers) observers[roomId] = launch {
                    coroutineScope {
                        launch { messageClient(lockers).subscribeToRoom(roomId) }
                        launch { watchMembership(roomId) }
                        launch {
                            messageClient(lockers).watchAll(roomId).collect { snapshot ->
                                for ((id, signed) in snapshot) {
                                    val payload = BoundedMessagePayload.decode(signed.content) ?: continue
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
                unverified.remove(roomId)?.values().orEmpty()
            }
            // Membership advanced: re-evaluate anything that was held because its sender was unknown.
            for (signed in buffered) tryIngest(roomId, signed)
        }
    }

    private suspend fun onNotification(notification: IncomingNotification) {
        if (notification.payload.size > 70_000) return
        val signed = runCatching { SignedContent.fromByteArray(notification.payload) }.getOrNull() ?: return
        // A malformed or transiently-failing message must not tear down the shared notification collector.
        try {
            tryIngest(notification.roomId, signed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun tryIngest(roomId: RoomId, signed: SignedContent): Unit = socialTelemetry.measure("messages", "ingest") {
        val payload = BoundedMessagePayload.decode(signed.content) ?: return@measure
        if (!payload.roomId.contentEquals(roomId.rawValue) || payload.orderingCounter < 0 || payload.orderingCounter == Long.MAX_VALUE) return@measure
        val senderId = ProfileId { rawValue = payload.senderProfileId }

        if (signed.content.size > 65_536 || payload.messageId.size != 32) return@measure
        val senderKey = try { Secp256r1PublicKey.decode(payload.senderProfileId) }
            catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                return@measure
            }
        if (!MessageSigning.verify(signed, senderKey)) return@measure
        val isMember = mutex.withLock {
            if (roomId !in subscribedRooms) return@withLock false
            if (senderId in members[roomId].orEmpty()) true else {
                unverified.getOrPut(roomId) {
                    com.latenighthack.social.runtime.BoundedCache(128, 30_000, elapsedClock)
                }.put(payload.messageId.toList(), signed)
                false
            }
        }
        if (!isMember) return@measure

        if (roomList(roomId).ingest(payload, signed)) bestEffortBump(roomId)
    }

    // Bump the room's updated_at to the front. Best-effort: a lost bump (network error, shutdown) must
    // never fail a send or tear down a collector — the send/receive itself is what matters.
    private fun bestEffortBump(roomId: RoomId) {
        bumps.trySend(RoomBump(roomId, session.currentOwner(), session?.generation?.value ?: 0L))
    }

    private suspend fun performBump(roomId: RoomId) {
        try {
            kotlinx.coroutines.withTimeout(5000) { rooms.markUpdated(roomId) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun <T> withSession(block: suspend () -> T): T =
        if (session == null) block() else session.withAccount(block)


    private suspend fun sendOwned(roomId: RoomId, draft: Draft) {
        session.currentOwner()
        val senderId = rooms.localProfile(roomId) ?: error("not a member of this room")
        val list = roomList(roomId)

        // A draft fans out into one message per component: each attachment's photo component in order,
        // then the text as a Text component (skipped when blank, so an attachment-only send is fine).
        val components = draft.attachments.map { it.component ?: Component { } } +
            listOfNotNull(draft.text.takeIf { it.isNotBlank() }?.let { text ->
                Component { contents.text { this.text = text } }
            })

        val counters = list.reserveCounters(components.size)
        components.forEach { BoundedMessagePayload.requireEncodable(it) }
        val prepared = components.mapIndexed { index, component ->
            val messageId = MessageId(rawValue = Random.nextBytes(32))
            val payload = MessagePayload(
                roomId = roomId.rawValue,
                senderProfileId = senderId.rawValue,
                sentAtMillis = Clock.System.now().toEpochMilliseconds(),
                orderingCounter = counters[index],
                component = component,
                messageId = messageId.rawValue,
            )
            val bytes = payload.toByteArray()
            require(BoundedMessagePayload.decode(bytes) != null) { "message payload exceeds supported bounds" }
            val signature = myProfiles.sign(senderId, MessageSigning.LABEL, bytes)
            session?.requireOperationOwner()
            val signed = signature ?: error("no signing key for the room's profile")
            Triple(messageId, payload, signed)
        }
        list.addOwn(prepared)
        wake.trySend(Unit)
        // Bump the room to the front the moment the user sends, reflecting their intent — not when the
        // message eventually lands. Queued to the owned metadata worker and best-effort: markUpdated reorders the
        // room list locally first, so the send neither blocks on nor fails from the synced write.
        bestEffortBump(roomId)
    }


    private suspend fun retryOwned(roomId: RoomId, messageId: MessageId) {
        ready.await()
        val dead = deadLetters.getDeadLettered(roomId, messageId, session.currentOwner()) ?: return
        if (!session.owns(dead.ownerAccountId)) return
        val signed = dead.message ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENDING) {
            pending.savePending(dead.copy(attempts = 0L, nextAttemptMillis = now))
            deadLetters.deleteDeadLettered(roomId, messageId, dead.ownerAccountId)
        }
        wake.trySend(Unit)
    }

    private suspend fun drainLoop(lockers: LockersClient) {
        while (true) {
            val now = Clock.System.now().toEpochMilliseconds()
            var soonest: Long? = null
            var queueDepth = 0
            var unknownAge = 0
            var oldestAge = 0.0
            pending.pages().collect { page ->
                val own = page.filter { session.owns(it.ownerAccountId) }
                queueDepth += own.size
                unknownAge += own.count { it.createdAtMillis <= 0 }
                oldestAge = maxOf(oldestAge, own.filter { it.createdAtMillis > 0 }.maxOfOrNull { (now - it.createdAtMillis).coerceAtLeast(0) / 1000.0 } ?: 0.0)
                own.minOfOrNull { it.nextAttemptMillis }?.let { soonest = minOf(soonest ?: it, it) }
                for (batch in own.filter { it.nextAttemptMillis <= now }.chunked(4)) coroutineScope {
                    batch.map { entry -> launch { attemptSend(lockers, entry) } }.forEach { it.join() }
                }
            }
            observer.record("message_queue", seconds = oldestAge, depth = queueDepth)
            observer.record("message_unknown_age", depth = unknownAge)
            socialTelemetry.event("messages", "queue", kind = "queue_depth", value = queueDepth.toDouble())
            socialTelemetry.event("messages", "queue", kind = "queue_age", value = oldestAge)
            val wait = if (soonest == null) idleWaitMillis
            else (soonest!! - Clock.System.now().toEpochMilliseconds()).coerceIn(1L, idleWaitMillis)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private suspend fun attemptSend(lockers: LockersClient, entry: PendingMessage) {
        observer.measure("message_processing") { socialTelemetry.measure("messages", "attemptSend") {
            if (entry.createdAtMillis > 0) observer.record("message_wait", seconds = (Clock.System.now().toEpochMilliseconds() - entry.createdAtMillis).coerceAtLeast(0) / 1000.0)
            attemptSendOwned(lockers, entry)
        } }
    }

    private suspend fun attemptSendOwned(lockers: LockersClient, entry: PendingMessage) {
        val messageId = entry.messageId ?: return
        val roomId = RoomId(rawValue = entry.roomId)
        session?.requireOperationOwner()
        val admittedGeneration = session?.generation?.value ?: 0L
        val signed = entry.message ?: run { pending.deletePending(roomId, messageId, entry.ownerAccountId); return }
        try {
            kotlinx.coroutines.withTimeout(30_000) {
            // Empty body ({ it } keeps it unchanged); the message rides as the notification payload.
            messageClient(lockers).updateLocker(
                roomId,
                LockerId(messageId.rawValue, MessagesKeyspaces.MESSAGING),
                notificationBuilder = { payload { rawValue = signed.toByteArray() } },
            ) { current ->
                if (!session.owns(entry.ownerAccountId) || admittedGeneration != (session?.generation?.value ?: 0L)) {
                    throw CancellationException("account changed")
                }
                check(current.content.isEmpty() || current == signed) { "message id already contains different content" }
                signed
            }
            session?.requireOperationOwner()
            roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT) {
                pending.deletePending(roomId, messageId, entry.ownerAccountId)
            }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            recordSendFailure(entry, roomId, messageId, signed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            recordSendFailure(entry, roomId, messageId, signed)
        }
    }

    private suspend fun recordSendFailure(entry: PendingMessage, roomId: RoomId, messageId: MessageId, signed: SignedContent) {
            session?.requireOperationOwner()
            val attempts = entry.attempts + 1
            if (attempts >= maxAttempts) {
                observer.record("message_dead_letter", "failure")
                socialTelemetry.event("messages", "dead_letter", "dead_letter")
                roomList(roomId).setStatus(messageId, signed, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_FAILED) {
                    deadLetters.saveDeadLettered(entry.copy(attempts = attempts))
                    pending.deletePending(roomId, messageId, entry.ownerAccountId)
                }
            } else {
                observer.record("message_retry", "failure")
                socialTelemetry.event("messages", "retry", "retry")
                pending.savePending(entry.copy(
                    attempts = attempts,
                    nextAttemptMillis = Clock.System.now().toEpochMilliseconds() + backoffMillis(attempts),
                ))
            }
    }

    private fun backoffMillis(attempts: Long): Long {
        val shift = (attempts - 1).coerceIn(0L, 20L).toInt()
        return (backoffBaseMillis shl shift).coerceAtMost(backoffCapMillis)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

    private fun watchOwnedMessages(roomId: RoomId): Flow<List<MessageEntry>> = flow {
        val list = roomList(roomId, retain = true)
        try {
            list.ensureLoaded()
            emitAll(list.entries.map { if (session.owns(list.owner) && list.generation == (session?.generation?.value ?: 0L)) it else emptyList() })
        } finally { roomsMutex.withLock { list.observers-- } }
    }.distinctUntilChanged()

    override suspend fun loadEarlier(roomId: RoomId, before: MessageId, limit: Int): List<MessageEntry> =
        withSession { loadEarlierOwned(roomId, before, limit) }

    private suspend fun loadEarlierOwned(roomId: RoomId, before: MessageId, limit: Int): List<MessageEntry> {
        val owner = session.currentOwner()
        require(limit in 1..1000)
        val boundary = store.getMessage(roomId, before, session.currentOwner())?.takeIf { session.owns(it.ownerAccountId) }
            ?.message?.let { BoundedMessagePayload.decode(it.content) } ?: return emptyList()
        return store.getRecentMessages(roomId, { session == null || it.ownerAccountId == owner }, limit,
            MessageEntry(boundary, MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT)).mapNotNull {
                it.message?.let { signed -> BoundedMessagePayload.decode(signed.content)?.let { payload -> MessageEntry(payload, it.status) } }
            }
    }

    override suspend fun compareMessageOrder(roomId: RoomId, first: MessageId, second: MessageId): Int? =
        withSession { store.compareMessageOrder(roomId, first, second, session.currentOwner()) }


    private suspend fun roomList(roomId: RoomId, retain: Boolean = false): RoomMessageList {
        val owner = if (session == null) "" else session.owner.value ?: throw CancellationException("account changed")
        val admittedGeneration = session?.generation?.value ?: 0L
        ready.await()
        return roomsMutex.withLock {
            if (!session.owns(owner) || admittedGeneration != (session?.generation?.value ?: 0L)) throw CancellationException("account changed")
            session?.requireOperationOwner()
            val list = roomLists[roomId]?.takeIf { it.owner == owner && it.generation == (session?.generation?.value ?: 0L) } ?: run {
                if (roomLists.size >= 64) roomLists.entries.firstOrNull { it.value.observers == 0 }
                    ?.key?.let { roomLists.remove(it) }
                RoomMessageList(roomId).also { roomLists[roomId] = it }
            }
            if (retain) list.observers++
            list
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
        var observers = 0
        val owner = session.currentOwner()
        val generation = session?.generation?.value ?: 0L
        private fun ownsList() = session.owns(owner) && generation == (session?.generation?.value ?: 0L)
        val entries = MutableStateFlow<List<MessageEntry>>(emptyList())

        // The message ids this room holds, maintained in memory so receive-dedup checks a set rather
        // than round-tripping the store. Populated by loadLocked and kept in sync by putLocked.
        private val seen = mutableSetOf<List<Byte>>()
        private val mutex = Mutex()
        private var loaded = false
        private var lastCounter = 0L

        suspend fun reserveCounters(count: Int): List<Long> = mutex.withLock {
            loadLocked()
            check(lastCounter <= Long.MAX_VALUE - count) { "message counter exhausted" }
            List(count) { ++lastCounter }
        }

        suspend fun ensureLoaded() = mutex.withLock { loadLocked() }

        private suspend fun loadLocked() {
            if (loaded) return
            val restored = store.getRecentMessages(roomId, { session == null || it.ownerAccountId == owner }).mapNotNull { local ->
                val messageId = local.messageId ?: return@mapNotNull null
                val signed = local.message ?: return@mapNotNull null
                val payload = BoundedMessagePayload.decode(signed.content)
                    ?: return@mapNotNull null
                seen.add(messageId.rawValue.toList())
                MessageEntry(payload, local.status)
            }.sortedWith(messageOrder)
            if (!ownsList()) throw CancellationException("account changed")
            entries.value = restored
            lastCounter = entries.value.maxOfOrNull { it.payload.orderingCounter } ?: 0L
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
            val duplicate = payload.messageId.toList() in seen || store.getMessage(roomId, messageId, owner) != null
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
                BoundedMessagePayload.decode(signed.content)?.let { putLocked(it, status) }
            }
        }

        // Insert or replace the entry for a message id, keeping the list sorted by sentAtMillis and the
        // id set current. Callers hold [mutex].
        private fun putLocked(payload: MessagePayload, status: MessageDeliveryStatus) {
            val idList = payload.messageId.toList()
            lastCounter = maxOf(lastCounter, payload.orderingCounter)
            seen.clear()
            seen.addAll(entries.value.map { it.payload.messageId.toList() })
            seen.add(idList)
            val without = entries.value.filterNot { it.payload.messageId.toList() == idList }
            entries.value = (without + MessageEntry(payload, status)).sortedWith(messageOrder).takeLast(1000)
        }

        private fun local(messageId: MessageId, signed: SignedContent, status: MessageDeliveryStatus): LocalMessage {
            if (!ownsList()) throw CancellationException("account changed")
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
