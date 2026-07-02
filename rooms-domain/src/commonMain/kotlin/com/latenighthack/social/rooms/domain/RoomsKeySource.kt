package com.latenighthack.social.rooms.domain

import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockKeySource

/**
 * The lock key source handed to the `LockersClient` once rooms are in play: writes to a room the
 * user is a member of are signed by that room's shared key from [rooms]; everything else (profile
 * rooms, the account room, and open inbox writes into other profiles' rooms) falls back to the
 * profile/account chain. A concrete chain — not a general registry.
 */
class RoomsKeySource(
    private val rooms: RoomsManagerImpl,
    private val fallback: LockKeySource,
) : LockKeySource {

    override suspend fun writeKeyFor(roomId: RoomId, lockerId: LockerId): Secp256r1KeyPair? =
        rooms.writeKey(roomId) ?: fallback.writeKeyFor(roomId, lockerId)
}
