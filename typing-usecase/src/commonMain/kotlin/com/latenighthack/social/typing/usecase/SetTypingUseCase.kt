package com.latenighthack.social.typing.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.typing.domain.TypingManager

/** Signals whether the user is typing in a room; debouncing and stop-sends are handled by the manager. */
class SetTypingUseCase(
    private val typing: TypingManager,
) {
    suspend fun set(roomId: RoomId, isTyping: Boolean) = typing.setTyping(roomId, isTyping)
}
