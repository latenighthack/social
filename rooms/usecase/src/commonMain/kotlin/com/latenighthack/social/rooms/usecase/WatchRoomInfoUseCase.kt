package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.v1.RoomInfo
import kotlinx.coroutines.flow.Flow

/** Watches a room's shared info. */
class WatchRoomInfoUseCase(
    private val rooms: RoomsManager,
) {
    fun watch(roomId: RoomId): Flow<RoomInfo?> = rooms.watchInfo(roomId)
}
