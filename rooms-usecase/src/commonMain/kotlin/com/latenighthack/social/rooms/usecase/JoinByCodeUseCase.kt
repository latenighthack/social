package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.v1.InviteCode

/** Redeems an invite code, joining the group room as the user's primary profile. */
class JoinByCodeUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun join(code: InviteCode): RoomId = rooms.joinByCode(code)
}
