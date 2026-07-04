package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.readreceipts.domain.ReadReceiptsManager

/** Marks a room's most recent message as read by the user; the manager resolves which message that is. */
class MarkReadUseCase(
    private val readReceipts: ReadReceiptsManager,
) {
    suspend fun markRead(roomId: RoomId) = readReceipts.markRead(roomId)
}
