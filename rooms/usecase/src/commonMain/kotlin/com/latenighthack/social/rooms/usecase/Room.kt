package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.v1.RoomInfo

/** A room the user belongs to, joined with its info and members. */
data class Room(
    val roomId: RoomId,
    val info: RoomInfo?,
    val members: List<RoomMember>,
)
