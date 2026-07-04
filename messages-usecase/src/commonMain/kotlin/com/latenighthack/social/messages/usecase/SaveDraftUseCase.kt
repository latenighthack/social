package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.DraftsManager

/** Saves a room's draft text as the user composes it, leaving any attachments untouched. */
class SaveDraftUseCase(
    private val drafts: DraftsManager,
) {
    suspend fun save(roomId: RoomId, text: String) = drafts.setText(roomId, text)
}
