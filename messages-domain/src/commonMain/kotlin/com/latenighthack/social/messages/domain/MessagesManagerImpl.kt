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
import com.latenighthack.social.messages.v1.LocalMessage
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.MessagePayload
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray
import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.runtime.DomainLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
        if (!storesInitialized) {
            store.prepare()
            delegate.createStores()
            storesInitialized = true
        }

        // Hydrate from the local cache and seed the processed set BEFORE subscribing, so replayed
        // history isn't counted as newly received (which would re-bump every room's updated_at).
        val grouped = mutableMapOf<RoomId, MutableList<MessagePayload>>()
        for (local in store.getAllMessages()) {
            val messageId = local.messageId ?: continue
            val signed = local.message ?: continue
            processed.add(LockerId(messageId.rawValue, MessagesKeyspaces.MESSAGES))
            val roomId = RoomId(rawValue = local.roomId)
            grouped.getOrPut(roomId) { mutableListOf() }.add(MessagePayload.fromByteArray(signed.content))
        }
        _messages.value = grouped.mapValues { (_, list) -> list.sortedBy { it.sentAtMillis } }

        // Watch each room the user belongs to for new messages, as rooms appear.
        rooms.watchRooms().collect { roomIds ->
            for (roomId in roomIds) {
                if (watchedRooms.add(roomId)) {
                    scope.launch { watchRoom(lockers, roomId) }
                }
            }
        }
    }

    private suspend fun watchRoom(lockers: LockersClient, roomId: RoomId) {
        messageClient(lockers).watchAll(roomId, MessagesKeyspaces.MESSAGES).collect { messages ->
            for ((lockerId, signed) in messages) {
                if (lockerId in processed) continue
                ingest(roomId, lockerId, signed)
            }
        }
    }

    private suspend fun ingest(roomId: RoomId, lockerId: LockerId, signed: SignedContent) {
        val payload = MessagePayload.fromByteArray(signed.content)
        // Reject a message whose bound room doesn't match, or whose signature doesn't verify against
        // the sender it claims — so a member can't forge another member's authorship.
        if (!payload.roomId.contentEquals(roomId.rawValue)) return
        val senderKey = Secp256r1PublicKey.decode(payload.senderProfileId)
        if (!MessageSigning.verify(signed, senderKey)) return
        if (!processed.add(lockerId)) return

        store.saveMessage(LocalMessage(
            roomId = roomId.rawValue,
            messageId = MessageId(rawValue = lockerId.rawValue),
            message = signed,
        ))
        val updated = (_messages.value[roomId].orEmpty() + payload).sortedBy { it.sentAtMillis }
        _messages.value = _messages.value + (roomId to updated)
        rooms.markUpdated(roomId)
    }

    override suspend fun send(roomId: RoomId, text: String) {
        val lockers = lockers ?: error("send requires start(lockers) first")
        val senderId = rooms.localProfile(roomId) ?: error("not a member of this room")
        val payload = MessagePayload(
            roomId = roomId.rawValue,
            senderProfileId = senderId.rawValue,
            sentAtMillis = Clock.System.now().toEpochMilliseconds(),
            text = text,
        )
        val signed = myProfiles.sign(senderId, MessageSigning.LABEL, payload.toByteArray())
            ?: error("no signing key for the room's profile")
        // Written under a fresh random locker id; the room lock (shared key via the rooms key source)
        // authorizes it. The write echoes back through watchAll and is ingested by the same path.
        messageClient(lockers).updateLocker(roomId, LockerId(Random.nextBytes(32), MessagesKeyspaces.MESSAGES)) { signed }
    }

    override fun watchMessages(roomId: RoomId): Flow<List<MessagePayload>> =
        _messages.map { it[roomId].orEmpty() }.distinctUntilChanged()

    private fun messageClient(lockers: LockersClient): TypedLockerClient<SignedContent> =
        lockers.typed(MessagesKeyspaces.MESSAGES, SignedContent::toByteArray, SignedContent.Companion::fromByteArray)
}
