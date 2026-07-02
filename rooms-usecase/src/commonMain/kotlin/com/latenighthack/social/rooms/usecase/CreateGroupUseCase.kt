package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager

/** Creates a group room with the given name and returns its id. */
class CreateGroupUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun create(name: String): RoomId = rooms.createGroup(name)
}
