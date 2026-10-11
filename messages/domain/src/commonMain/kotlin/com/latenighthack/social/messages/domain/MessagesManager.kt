package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.MessageDeliveryStatus
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.MessagePayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The user's messages: a chat layer riding on rooms. All sends in a room go through one gated
 * MESSAGING locker: a send bumps its version with an empty body (the room's own lock restricts writes
 * to members, and the shared locker's read-before-write version bump linearizes concurrent sends) and
 * rides the signed message as the locker's notification payload. The signature attributes each message
 * to the exact member who wrote it. Messages are the local store's durable record — received, sent,
 * and not-yet-sent — since the locker carries no body; sends are enqueued to a durable outbox and
 * driven to delivery (with retry and a dead-letter queue) by the manager, not the caller.
 */
interface MessagesManager {
    fun start(lockers: LockersClient)
    fun stop()

    /**
     * Enqueues [draft] for delivery to [roomId], authored by the profile the user is in that room as.
     * Returns once the message is stored locally and queued; the manager drives the actual send.
     */
    suspend fun send(roomId: RoomId, draft: Draft)

    /** Re-queues a dead-lettered message in [roomId] for another delivery attempt. */
    suspend fun retry(roomId: RoomId, messageId: MessageId)

    /** The messages in [roomId], oldest first, each with its local delivery status. */
    fun watchMessages(roomId: RoomId): Flow<List<MessageEntry>>

    /** The ids of the messages in [roomId], oldest first — index-aligned with [watchMessages]. */
    /** Older durable history; the live flow retains the most recent 1000 messages. */
    suspend fun loadEarlier(roomId: RoomId, before: MessageId, limit: Int = 100): List<MessageEntry> = emptyList()

    /** Compare causal positions, including archived messages; null means either id is not known. */
    suspend fun compareMessageOrder(roomId: RoomId, first: MessageId, second: MessageId): Int? {
        val ids = watchMessageIds(roomId).first()
        val a = ids.indexOf(first); val b = ids.indexOf(second)
        return if (a < 0 || b < 0) null else a.compareTo(b)
    }

    fun watchMessageIds(roomId: RoomId): Flow<List<MessageId>>
}

/** A message in a room: its verified [payload] and local delivery [status]. */
data class MessageEntry(
    val payload: MessagePayload,
    val status: MessageDeliveryStatus,
)
