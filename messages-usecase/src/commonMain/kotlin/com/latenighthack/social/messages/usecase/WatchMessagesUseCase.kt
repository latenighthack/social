package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.MessagesManager
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.profiles.v1.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Watches the messages in a room, oldest first, as view models. */
class WatchMessagesUseCase(
    private val messages: MessagesManager,
) {
    fun watch(roomId: RoomId): Flow<List<Message>> =
        messages.watchMessages(roomId).map { payloads ->
            payloads.map {
                Message(
                    senderProfileId = ProfileId { rawValue = it.senderProfileId },
                    sentAtMillis = it.sentAtMillis,
                    component = it.component ?: Component { },
                )
            }
        }
}
