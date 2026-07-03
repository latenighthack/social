package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.DraftsManager
import com.latenighthack.social.messages.domain.MessagesManager
import com.latenighthack.social.messages.v1.Draft

/** Sends a room's draft, then clears it from the draft manager. */
class SendMessageUseCase(
    private val messages: MessagesManager,
    private val drafts: DraftsManager,
) {
    suspend fun send(roomId: RoomId, draft: Draft) {
        messages.send(roomId, draft)
        drafts.clear(roomId)
    }
}
