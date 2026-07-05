package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.v1.InviteCode

/** Mints a shareable, revocable invite code for a group room. */
class CreateInviteCodeUseCase(
    private val rooms: RoomsManager,
) {
    suspend fun create(roomId: RoomId): InviteCode = rooms.createInviteCode(roomId)
}
