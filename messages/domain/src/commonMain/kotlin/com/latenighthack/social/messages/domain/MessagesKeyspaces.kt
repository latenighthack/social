package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * Keyspaces the messages feature reserves. Keyspace numbering is a cross-feature allocation
 * concern: account reserves `1`, profiles reserve `2`–`3`, rooms reserve `4`–`8`, messages reserve `9`.
 */
internal object MessagesKeyspaces {
    /**
     * Durable message lockers, keyed by the signed sender-minted message id. Observers replay the
     * snapshot after restart; notification payloads are only a latency optimization. Room locks gate
     * member writes, and profile signatures authenticate individual authors.
     */
    val MESSAGING = LockerKeyspace { value = 9L }

    /** The MESSAGING locker's fixed id (one per room). */
    val MESSAGING_LOCKER = LockerId(ByteArray(32), MESSAGING)
}
