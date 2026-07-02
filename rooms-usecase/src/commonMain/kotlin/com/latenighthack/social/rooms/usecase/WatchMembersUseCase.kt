package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.profiles.domain.ProfilesManager
import com.latenighthack.social.rooms.domain.RoomsManager
import kotlinx.coroutines.flow.Flow

/** Watches a room's current members, joining each member's profile. */
class WatchMembersUseCase(
    private val rooms: RoomsManager,
    private val profiles: ProfilesManager,
) {
    fun watch(roomId: RoomId): Flow<List<RoomMember>> = watchRoomMembers(rooms, profiles, roomId)
}
