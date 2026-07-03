package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.messages.v1.Draft
import kotlinx.coroutines.flow.Flow

/**
 * A room's single composed-but-unsent [Draft], held locally and durably. Drafts never leave the
 * device — they are not written to lockers. A draft is saved as the user composes, observed to
 * restore the editor, and cleared once the message manager has sent it.
 */
interface DraftsManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Saves (or replaces) the draft for [roomId]. */
    suspend fun save(roomId: RoomId, draft: Draft)

    /** The draft for [roomId], or null when none is composed. */
    fun watchDraft(roomId: RoomId): Flow<Draft?>

    /** Removes the draft for [roomId]. */
    suspend fun clear(roomId: RoomId)
}
