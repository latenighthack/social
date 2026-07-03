package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * Keyspaces the messages feature reserves. Keyspace numbering is a cross-feature allocation
 * concern: account reserves `1`, profiles reserve `2`–`3`, rooms reserve `4`–`8`, messages reserve `9`.
 */
internal object MessagesKeyspaces {
    /**
     * One signed message locker per message, keyed by a random message id, inside each room the user
     * is a member of. Covered by the room's existing lock, so only members can write.
     */
    val MESSAGES = LockerKeyspace { value = 9L }
}
