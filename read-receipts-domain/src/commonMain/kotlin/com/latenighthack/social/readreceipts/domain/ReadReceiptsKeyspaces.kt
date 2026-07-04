package com.latenighthack.social.readreceipts.domain

import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * Keyspaces the read-receipts feature reserves. Keyspace numbering is a cross-feature allocation
 * concern: account reserves `1`, profiles reserve `2`–`3`, rooms reserve `4`–`8`, messages reserve
 * `9`, contacts reserve `10`, typing reserves `11`, read receipts reserve `12`.
 */
internal object ReadReceiptsKeyspaces {
    /**
     * One ReadReceipt locker per member, keyed by the member's profile id, inside each room the user
     * is in. Covered by the room's existing lock, so only members can write. The body persists the
     * member's read pointer — the message id of the most recently read message in that room.
     */
    val READ_RECEIPTS = LockerKeyspace { value = 12L }
}
