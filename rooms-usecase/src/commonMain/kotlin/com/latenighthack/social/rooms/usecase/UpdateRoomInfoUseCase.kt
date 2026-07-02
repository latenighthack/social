package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager

/** Sets the shared room info name. */
class UpdateRoomInfoUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun update(roomId: RoomId, name: String) = rooms.updateInfo(roomId, name)
}
