package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.domain.RoomsManager

/** Opens the rendezvous room shared with a peer profile and returns its id. */
class OpenRendezvousUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun open(peerProfileId: ProfileId): RoomId = rooms.openRendezvous(peerProfileId)
}
