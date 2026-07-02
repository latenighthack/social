package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager

/** Leaves a room, removing the user's own roster and profile entries. */
class LeaveRoomUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun leave(roomId: RoomId) = rooms.leave(roomId)
}
