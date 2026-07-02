package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.domain.replaceDisclosure

/** Sets the shared room info name. */
class UpdateRoomInfoUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun update(roomId: RoomId, name: String) {
        val newName = name
        rooms.updateInfo(roomId) { replaceDisclosure { name { value = newName } } }
    }
}
