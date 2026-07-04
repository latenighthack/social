package com.latenighthack.social.messages.domain

import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.TypedLockerClient
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.common.v1.fromByteArray
import com.latenighthack.social.common.v1.toByteArray
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.LocalMessage
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.MessagePayload
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Observes the messages keyspace of every room the user is a member of, verifies each message's
 * signature, and mirrors the verified messages into memory + a persistent [MessageStore]. Sending
 * writes a signed message locker whose room-scope lock (routed the shared room key by the rooms key
 * source) authorizes the write for members only. A newly received message bumps its room's
 * `updated_at` through [RoomsManager.markUpdated]. Resumable: [start] launches the collectors,
 * [stop] cancels them and leaves the manager reusable.
 */
class MessagesManagerImpl(
    private val rooms: RoomsManager,
    private val myProfiles: MyProfilesManager,
    private val delegate: StoreDelegate,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MessagesManager, DomainLifecycle {

    private val store = MessageStore(delegate)

    private val _messages = MutableStateFlow<Map<RoomId, List<MessagePayload>>>(emptyMap())

    // Guards the read-modify-write of processed + _messages, which the per-room collectors run
    // concurrently on a multi-threaded dispatcher.
    private val mutex = Mutex()

    // Locker ids already ingested (seeded from the cache at start), so replays don't re-store/re-bump.
    private val processed = mutableSetOf<LockerId>()
    private val watchedRooms = mutableSetOf<RoomId>()

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
        // A prior stop() cancelled the per-room collectors, so forget which rooms were being watched
        // and re-establish them below (processed is re-seeded from the cache just after).
        watchedRooms.clear()

        if (!storesInitialized) {
            store.prepare()
            delegate.createStores()
            storesInitialized = true
        }

        // Hydrate from the local cache and seed the processed set BEFORE subscribing, so replayed
        // history isn't counted as newly received (which would re-bump every room's updated_at). This
        // runs before any collector launches, so it needs no locking.
        val grouped = mutableMapOf<RoomId, MutableList<MessagePayload>>()
        for (local in store.getAllMessages()) {
            val messageId = local.messageId ?: continue
            val signed = local.message ?: continue
            val payload = runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull() ?: continue
            processed.add(LockerId(messageId.rawValue, MessagesKeyspaces.MESSAGES))
            val roomId = RoomId(rawValue = local.roomId)
            grouped.getOrPut(roomId) { mutableListOf() }.add(payload)
        }
        _messages.value = grouped.mapValues { (_, list) -> list.sortedBy { it.sentAtMillis } }

        // Watch each room the user belongs to for new messages, as rooms appear. The per-room
        // collectors are launched as children of this coroutine (not the retained scope) so stop() —
        // which cancels this job — tears them down too; supervisorScope keeps one collector's failure
        // from cancelling the others.
        supervisorScope {
            rooms.watchRooms().collect { roomIds ->
                for (roomId in roomIds) {
                    if (watchedRooms.add(roomId)) {
                        launch { watchRoom(lockers, roomId) }
                    }
                }
            }
        }
    }

    private suspend fun watchRoom(lockers: LockersClient, roomId: RoomId) {
        // Combine the message stream with the room's membership so ingestion re-runs when either
        // changes: a message that arrives just before its sender's membership syncs is re-evaluated
        // (not lost) once the member set catches up, without polling the network per message.
        combine(
            messageClient(lockers).watchAll(roomId, MessagesKeyspaces.MESSAGES),
            rooms.watchMembers(roomId),
        ) { messages, members -> messages to members.toSet() }.collect { (messages, members) ->
            for ((lockerId, signed) in messages) {
                if (mutex.withLock { lockerId in processed }) continue
                // One malformed or transiently-failing message (e.g. a best-effort room bump lost to a
                // shutdown/network error) must not tear down this room's collector.
                try {
                    ingest(roomId, lockerId, signed, members)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun ingest(roomId: RoomId, lockerId: LockerId, signed: SignedContent, members: Set<ProfileId>) {
        // Parse the envelope and resolve the claimed signer before trusting anything. The content is
        // attacker-chosen (any member can write to the messages keyspace), so a malformed payload or
        // an undecodable sender key must be skipped, not allowed to crash this room's collector.
        val payload = runCatching { MessagePayload.fromByteArray(signed.content) }.getOrNull() ?: return
        if (!payload.roomId.contentEquals(roomId.rawValue)) return
        // The signature proves the author controls senderProfileId; requiring that profile to be a
        // current member is what stops a member posting under a profile that isn't in the room.
        val senderId = ProfileId { rawValue = payload.senderProfileId }
        if (senderId !in members) return
        val senderKey = runCatching { Secp256r1PublicKey.decode(payload.senderProfileId) }.getOrNull() ?: return
        if (!MessageSigning.verify(signed, senderKey)) return

        val stored = mutex.withLock {
            if (!processed.add(lockerId)) return@withLock false
            store.saveMessage(LocalMessage(
                roomId = roomId.rawValue,
                messageId = MessageId(rawValue = lockerId.rawValue),
                message = signed,
            ))
            val updated = (_messages.value[roomId].orEmpty() + payload).sortedBy { it.sentAtMillis }
            _messages.value = _messages.value + (roomId to updated)
            true
        }
        if (stored) rooms.markUpdated(roomId)
    }

    override suspend fun send(roomId: RoomId, draft: Draft) {
        val lockers = lockers ?: error("send requires start(lockers) first")
        val senderId = rooms.localProfile(roomId) ?: error("not a member of this room")

        // A draft fans out into one message per component: each attachment's photo component in order,
        // then the text as a Text component (skipped when blank, so an attachment-only send is fine).
        val components = draft.attachments.map { it.component ?: Component { } } +
            listOfNotNull(draft.text.takeIf { it.isNotBlank() }?.let { text ->
                Component { contents.text { this.text = text } }
            })

        for (component in components) {
            val payload = MessagePayload(
                roomId = roomId.rawValue,
                senderProfileId = senderId.rawValue,
                sentAtMillis = Clock.System.now().toEpochMilliseconds(),
                component = component,
            )
            val signed = myProfiles.sign(senderId, MessageSigning.LABEL, payload.toByteArray())
                ?: error("no signing key for the room's profile")
            // Written under a fresh random locker id; the room lock (shared key via the rooms key source)
            // authorizes it. The write echoes back through watchAll and is ingested by the same path.
            messageClient(lockers).updateLocker(roomId, LockerId(Random.nextBytes(32), MessagesKeyspaces.MESSAGES)) { signed }
        }
    }

    override fun watchMessages(roomId: RoomId): Flow<List<MessagePayload>> =
        _messages.map { it[roomId].orEmpty() }.distinctUntilChanged()

    private fun messageClient(lockers: LockersClient): TypedLockerClient<SignedContent> =
        lockers.typed(MessagesKeyspaces.MESSAGES, SignedContent::toByteArray, SignedContent.Companion::fromByteArray)
}
