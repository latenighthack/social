package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * Keyspaces the messages feature reserves. Keyspace numbering is a cross-feature allocation
 * concern: account reserves `1`, profiles reserve `2`–`3`, rooms reserve `4`–`8`, messages reserve `9`.
 */
internal object MessagesKeyspaces {
    /**
     * The single MESSAGING locker per room. A send bumps its version with an empty body and rides the
     * message as the locker's notification payload; the room's existing lock gates the write to
     * members and the shared locker's read-before-write version bump linearizes concurrent sends.
     */
    val MESSAGING = LockerKeyspace { value = 9L }

    /** The MESSAGING locker's fixed id (one per room). */
    val MESSAGING_LOCKER = LockerId(ByteArray(32), MESSAGING)
}
