package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.DraftsManager

/** Removes the attachment with [contentId] from a room's draft. */
class RemoveDraftAttachmentUseCase(
    private val drafts: DraftsManager,
) {
    suspend fun remove(roomId: RoomId, contentId: ByteArray) = drafts.removeAttachment(roomId, contentId)
}
