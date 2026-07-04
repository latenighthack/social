package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.DraftAttachment
import kotlinx.coroutines.flow.Flow

/**
 * A room's single composed-but-unsent [Draft] — one text plus any number of attachments — held
 * locally and durably. Drafts never leave the device (they are not written to lockers). The text and
 * the attachments are mutated independently so composing text and attaching photos don't clobber each
 * other; the draft is observed to restore the editor and cleared once the message manager sends it.
 */
interface DraftsManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Sets the text of [roomId]'s draft, leaving its attachments untouched. */
    suspend fun setText(roomId: RoomId, text: String)

    /** Appends [attachment] to [roomId]'s draft. */
    suspend fun addAttachment(roomId: RoomId, attachment: DraftAttachment)

    /** Removes the attachment with [contentId] from [roomId]'s draft, if present. */
    suspend fun removeAttachment(roomId: RoomId, contentId: ByteArray)

    /** The draft for [roomId], or null when none is composed. */
    fun watchDraft(roomId: RoomId): Flow<Draft?>

    /** Removes the draft for [roomId]. */
    suspend fun clear(roomId: RoomId)
}
