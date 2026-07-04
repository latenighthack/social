package com.latenighthack.social.typing.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.typing.domain.TypingManager
import kotlinx.coroutines.flow.Flow

/** Watches which profiles are currently typing in a room, excluding the user's own profile. */
class WatchTypingUseCase(
    private val typing: TypingManager,
) {
    fun watch(roomId: RoomId): Flow<Set<ProfileId>> = typing.watchTyping(roomId)
}
