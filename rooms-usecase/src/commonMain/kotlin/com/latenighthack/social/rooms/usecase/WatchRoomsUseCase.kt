package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager
import kotlinx.coroutines.flow.Flow

/** Watches the ids of the rooms the user belongs to. */
class WatchRoomsUseCase(
    private val rooms: RoomsManager,
) {
    fun watch(): Flow<List<RoomId>> = rooms.watchRooms()
}
