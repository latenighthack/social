package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.MessagesManager
import com.latenighthack.social.messages.v1.MessageId

/** Re-queues a dead-lettered (FAILED) message in a room for another delivery attempt. */
class RetryFailedMessageUseCase(
    private val messages: MessagesManager,
) {
    suspend fun retry(roomId: RoomId, messageId: MessageId) {
        messages.retry(roomId, messageId)
    }
}
