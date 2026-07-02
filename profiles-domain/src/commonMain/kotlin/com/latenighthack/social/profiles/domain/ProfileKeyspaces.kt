package com.latenighthack.social.profiles.domain

import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.LockerKeyspace
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.profiles.v1.ProfileId

/**
 * Keyspaces the profiles feature reserves. Keyspace numbering is a cross-feature
 * allocation concern; account reserves `1`, profiles reserve `2` and `3`.
 */
internal object ProfileKeyspaces {
    /** Profile-source lockers (id + private key) inside the user's account room. */
    val PROFILE_SOURCE = LockerKeyspace { value = 2L }

    /** The single Profile locker inside each profile's own room. */
    val PROFILE = LockerKeyspace { value = 3L }
}

/** The profile's own room, key-locked to the profile key embedded in the id. */
internal fun ProfileId.toRoomId(): RoomId = RoomKeying.publicKeyed(rawValue)

/** The source locker for this profile inside the account room. */
internal fun ProfileId.toSourceLockerId(): LockerId = LockerId(rawValue, ProfileKeyspaces.PROFILE_SOURCE)

/** The Profile locker for this profile inside its own room. */
internal fun ProfileId.toProfileLockerId(): LockerId = LockerId(rawValue, ProfileKeyspaces.PROFILE)
