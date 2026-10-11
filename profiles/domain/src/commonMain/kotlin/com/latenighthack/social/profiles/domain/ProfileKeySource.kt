package com.latenighthack.social.profiles.domain

import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockKeySource

/**
 * The lock key source handed to the `LockersClient` once profiles are in play: profile-room
 * writes are signed by the matching profile key from [myProfiles]; everything else (notably the
 * account room, where profile sources live) falls back to the account's key source. A concrete
 * two-source chain — not a general registry.
 */
class ProfileKeySource(
    private val myProfiles: MyProfilesManagerImpl,
    private val fallback: LockKeySource,
) : LockKeySource {

    override suspend fun writeKeyFor(roomId: RoomId, lockerId: LockerId): Secp256r1KeyPair? =
        myProfiles.writeKey(roomId, lockerId) ?: fallback.writeKeyFor(roomId, lockerId)
}
