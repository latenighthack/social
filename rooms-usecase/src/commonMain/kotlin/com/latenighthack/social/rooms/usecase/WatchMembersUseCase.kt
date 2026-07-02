package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.domain.RoomsManager
import kotlinx.coroutines.flow.Flow

/** Watches the profile ids of a room's current members. */
class WatchMembersUseCase(
    private val rooms: RoomsManager,
) {
    fun watch(roomId: RoomId): Flow<List<ProfileId>> = rooms.watchMembers(roomId)
}
