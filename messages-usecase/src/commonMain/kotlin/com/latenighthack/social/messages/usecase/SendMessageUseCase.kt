package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.MessagesManager

/** Sends a text message to a room. */
class SendMessageUseCase(
    private val messages: MessagesManager,
) {
    suspend fun send(roomId: RoomId, text: String) = messages.send(roomId, text)
}
