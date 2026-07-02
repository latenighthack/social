package com.latenighthack.social.rooms.domain

import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * Keyspaces the rooms feature reserves. Keyspace numbering is a cross-feature allocation
 * concern: account reserves `1`, profiles reserve `2` and `3`, rooms reserve `4`–`7`.
 */
internal object RoomsKeyspaces {
    /** Sealed invites dropped into a profile's own room. Left UNLOCKED so anyone can invite. */
    val INBOX = LockerKeyspace { value = 4L }

    /** The single [com.latenighthack.social.rooms.v1.RoomInfo] locker inside a room. */
    val ROOM_INFO = LockerKeyspace { value = 5L }

    /** Roster: one [com.latenighthack.social.rooms.v1.Member] locker per member, keyed by profile id. */
    val MEMBERSHIP = LockerKeyspace { value = 6L }

    /** One [com.latenighthack.social.rooms.v1.MemberProfile] locker per member, keyed by profile id. */
    val MEMBER_PROFILES = LockerKeyspace { value = 7L }

    /** The room-info locker's fixed id (one per room). */
    val ROOM_INFO_LOCKER = LockerId(ByteArray(32), ROOM_INFO)
}
