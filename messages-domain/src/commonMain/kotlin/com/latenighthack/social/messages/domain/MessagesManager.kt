package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.MessagePayload
import kotlinx.coroutines.flow.Flow

/**
 * The user's messages: a chat layer riding on rooms. Each message is a signed locker (a
 * [SignedContent] signed by the sender's profile key) written into a room's messages keyspace;
 * the room's own lock already restricts writes to members, and the
 * signature attributes each message to the exact member who wrote it. The manager observes messages
 * for every room the user belongs to, caches them locally, and bumps a room's `updated_at` whenever
 * a new message arrives.
 */
interface MessagesManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Sends [draft] to [roomId], authored by the profile the user is in that room as. */
    suspend fun send(roomId: RoomId, draft: Draft)

    /** The verified messages in [roomId], oldest first. */
    fun watchMessages(roomId: RoomId): Flow<List<MessagePayload>>
}
