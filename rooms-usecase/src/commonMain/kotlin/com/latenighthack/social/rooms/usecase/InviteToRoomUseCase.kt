package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.domain.RoomsManager

/**
 * Invites a peer directly to a group room by sealing the group grant into the peer's profile
 * inbox; the peer's manager auto-joins on receipt.
 */
class InviteToRoomUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun invite(roomId: RoomId, peerProfileId: ProfileId) = rooms.inviteToRoom(roomId, peerProfileId)
}
