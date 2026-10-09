package com.latenighthack.social.rooms.domain

import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.v1.RoomKind

/** Host consent/block policy, evaluated only after authenticating the invitation's author. */
fun interface RoomInvitePolicy {
    suspend fun accept(recipient: ProfileId, inviter: ProfileId, kind: RoomKind): Boolean
}

/** Retains automatic acceptance for hosts that do not supply a consent policy. */
object AcceptRoomInvites : RoomInvitePolicy {
    override suspend fun accept(recipient: ProfileId, inviter: ProfileId, kind: RoomKind) = true
}
