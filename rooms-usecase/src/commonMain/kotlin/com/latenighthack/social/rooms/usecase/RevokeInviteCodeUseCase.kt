package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.v1.InviteCode

/** Revokes an invite code so it can no longer be used to join the group room. */
class RevokeInviteCodeUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun revoke(roomId: RoomId, code: InviteCode) = rooms.revokeInviteCode(roomId, code)
}
