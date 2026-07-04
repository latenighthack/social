package com.latenighthack.social.typing.domain

import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * Keyspaces the typing feature reserves. Keyspace numbering is a cross-feature allocation concern:
 * account reserves `1`, profiles reserve `2`–`3`, rooms reserve `4`–`8`, messages reserve `9`,
 * contacts reserve `10`, typing reserves `11`.
 */
internal object TypingKeyspaces {
    /**
     * One placeholder locker per member, keyed by the member's profile id, inside each room the user
     * is in. Covered by the room's existing lock, so only members can write. The locker body is an
     * empty placeholder; the typing signal rides as an event attached to each update.
     */
    val TYPING = LockerKeyspace { value = 11L }
}
