package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.DraftsManager
import com.latenighthack.social.messages.v1.Draft

/** Saves a room's draft as the user composes it. */
class SaveDraftUseCase(
    private val drafts: DraftsManager,
) {
    suspend fun save(roomId: RoomId, draft: Draft) = drafts.save(roomId, draft)
}
