package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.domain.RoomsManager

/** Invites a profile into a group room by sealing the group key into their inbox. */
class InviteToGroupUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun invite(roomId: RoomId, profileId: ProfileId) = rooms.invite(roomId, profileId)
}
