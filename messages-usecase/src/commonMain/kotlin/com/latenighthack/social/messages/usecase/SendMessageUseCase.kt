package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.DraftsManager
import com.latenighthack.social.messages.domain.MessagesManager
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.runtime.AccountSession
import com.latenighthack.social.runtime.withAccount
import com.latenighthack.social.runtime.requireOperationOwner

/** Sends a room's draft, then clears it from the draft manager. */
class SendMessageUseCase(
    private val messages: MessagesManager,
    private val drafts: DraftsManager,
    private val session: AccountSession? = null,
) {
    suspend fun send(roomId: RoomId, draft: Draft) {
        if (session == null) sendOwned(roomId, draft)
        else session.withAccount { sendOwned(roomId, draft) }
    }

    private suspend fun sendOwned(roomId: RoomId, draft: Draft) {
        messages.send(roomId, draft)
        session?.requireOperationOwner()
        drafts.clearIfUnchanged(roomId, draft)
    }
}
