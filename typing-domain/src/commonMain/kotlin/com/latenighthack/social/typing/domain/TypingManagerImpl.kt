// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.typing.domain

import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.IncomingNotification
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.TypedLockerClient
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.AccountSession
import com.latenighthack.social.runtime.withAccount
import com.latenighthack.social.typing.v1.TypingPayload
import com.latenighthack.social.typing.v1.fromByteArray
import com.latenighthack.social.typing.v1.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.latenighthack.social.runtime.TaskHealth
import com.latenighthack.social.runtime.recoverTask
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.latenighthack.social.typing.v1.copy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Observes the typing keyspace of every room the user is in and keeps a per-room map of profile id →
 * the wall-clock time that member started typing. Each observed event sets or clears its sender's
 * entry; [watchTyping] filters the map to members whose signal is within [timeoutMillis] and re-emits
 * on a tick so a member drops off once they time out — no explicit stop needed. Sending a "started
 * typing" signal is debounced to one send per [debounceMillis] while typing stays true, so a stream
 * of keystrokes refreshes the indicator without spamming the network; "stopped typing" is sent at
 * once. Writes to the typing keyspace are authorized by the room's own lock (routed the shared room
 * key by the rooms key source), so only members can signal and no signing is needed. Resumable:
 * [start] launches the collectors, [stop] cancels them and leaves the manager reusable.
 */
class TypingManagerImpl(
    private val rooms: RoomsManager,
    private val myProfiles: com.latenighthack.social.profiles.domain.MyProfilesManager,
    private val debounceMillis: Long = 10_000,
    private val timeoutMillis: Long = 15_000,
    private val tickMillis: Long = 1_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val session: AccountSession? = null,
) : TypingManager, DomainLifecycle {

    // Rooms → (profile id → started-at millis) for every member with an outstanding typing signal.
    private val timeOrigin = kotlin.time.TimeSource.Monotonic.markNow()
    private suspend fun <T> withSession(block: suspend () -> T): T =
        if (session == null) block() else session.withAccount(block)

    private fun elapsedMillis() = timeOrigin.elapsedNow().inWholeMilliseconds

    private val _typing = MutableStateFlow<Map<RoomId, Map<ProfileId, Long>>>(emptyMap())

    // Guards _typing (mutated by the notification collector) and lastStartedSentAt (read-modify-write
    // in setTyping), both reachable concurrently on a multi-threaded dispatcher.
    private val mutex = Mutex()

    // When we last sent a "started typing" signal per room; presence also means "we believe we are
    // typing there", which gates whether a "stopped typing" send is needed.
    private val lastStartedSentAt = mutableMapOf<RoomId, Long>()

    // Rooms we've already subscribed for events (mutated only by the watchRooms collector).
    private val subscribedRooms = mutableSetOf<RoomId>()

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

    private suspend fun run(lockers: LockersClient) {
        // A prior stop() cancelled the per-room subscriptions, so forget them and re-subscribe below.
        subscribedRooms.clear()

        // One collector drains every room's typing events (the notifications flow spans all rooms,
        // filtered to this keyspace); subscribing each room is what makes its events flow. Launched as
        // children of this coroutine so stop() tears them down; supervisorScope isolates failures.
        coroutineScope {
            launch { typingClient(lockers).notifications.collect { onNotification(it) } }
            launch {
                while (true) {
                    delay(tickMillis)
                    val now = elapsedMillis()
                    mutex.withLock {
                        _typing.value = _typing.value.mapValues { (_, signals) ->
                            signals.filterValues { now - it < timeoutMillis }
                        }.filterValues { it.isNotEmpty() }
                    }
                }
            }

            rooms.watchRooms().collect { roomIds ->
                mutex.withLock {
                    subscribedRooms.retainAll(roomIds.toSet())
                    lastStartedSentAt.keys.retainAll(roomIds.toSet())
                    _typing.value = _typing.value.filterKeys { it in roomIds }
                }
                for (roomId in roomIds) {
                    if (subscribedRooms.add(roomId)) {
                        launch { typingClient(lockers).subscribeToRoom(roomId) }
                    }
                }
            }
        }
    }

    private suspend fun onNotification(notification: IncomingNotification) {
        if (notification.roomId !in mutex.withLock { subscribedRooms.toSet() } || notification.payload.size > 4096) return
        val signal = runCatching { TypingPayload.fromByteArray(notification.payload) }.getOrNull() ?: return
        val profileId = ProfileId { rawValue = notification.lockerId.rawValue }
        if (!com.latenighthack.social.common.domain.verifyProfileClaim(profileId.rawValue, notification.roomId.rawValue,
            signal.profileId, signal.roomId, signal.proof, 5, signal.copy(proof = null).toByteArray())) return
        mutex.withLock {
            val current = _typing.value[notification.roomId].orEmpty()
            val updated = if (signal.startedTypingMillis > 0L) {
                (current + (profileId to elapsedMillis())).entries.toList().takeLast(128).associate { it.toPair() }
            } else {
                current - profileId
            }
            _typing.value = _typing.value + (notification.roomId to updated)
        }
    }

    override suspend fun setTyping(roomId: RoomId, isTyping: Boolean): Unit = withSession {
        runner.command { setTypingOwned(roomId, isTyping) }
    }

    private suspend fun setTypingOwned(roomId: RoomId, isTyping: Boolean) {
        val lockers = lockers ?: error("setTyping requires start(lockers) first")
        val me = rooms.localProfile(roomId) ?: return
        val now = elapsedMillis()

        val signal = mutex.withLock {
            if (isTyping) {
                val last = lastStartedSentAt[roomId]
                if (last != null && now - last < debounceMillis) return@withLock null
                lastStartedSentAt[roomId] = now
                TypingPayload { startedTypingMillis = 1 }
            } else {
                if (lastStartedSentAt.remove(roomId) == null) return@withLock null
                TypingPayload { startedTypingMillis = 0L }
            }
        } ?: return

        val claim = signal.copy(roomId = roomId.rawValue, profileId = me.rawValue)
        val proof = myProfiles.sign(me, 5, claim.toByteArray()) ?: return
        val authenticated = claim.copy(proof = proof)
        // An empty placeholder body ({ it } keeps it unchanged); the signal rides as the attached event.
        typingClient(lockers).updateLocker(
            roomId,
            LockerId(me.rawValue, TypingKeyspaces.TYPING),
            notificationBuilder = { payload { rawValue = authenticated.toByteArray() } },
        ) { it }
    }

    override fun watchTyping(roomId: RoomId): Flow<Set<ProfileId>> =
        combine(_typing.map { it[roomId].orEmpty() }, rooms.watchMembers(roomId)) { entries, members ->
            val me = rooms.localProfile(roomId)
            (entries.keys intersect members.toSet()) - setOfNotNull(me)
        }.distinctUntilChanged()

    private fun typingClient(lockers: LockersClient): TypedLockerClient<TypingPayload> =
        lockers.typed(TypingKeyspaces.TYPING, TypingPayload::toByteArray, TypingPayload.Companion::fromByteArray)
}
