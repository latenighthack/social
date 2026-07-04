package com.latenighthack.social.contacts.domain

import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * Keyspaces the contacts feature reserves. Keyspace numbering is a cross-feature allocation
 * concern: account reserves `1`, profiles reserve `2`–`3`, rooms reserve `4`–`8`, messages reserve
 * `9`, contacts reserve `10`.
 */
internal object ContactsKeyspaces {
    /**
     * The user's friend/block list, stored inside the user's own account room, one ContactRecord
     * locker per contact keyed by profile id. The account room is locked to the account key and
     * synced, so the list survives an account restore. Mirrors how room records live there.
     */
    val CONTACTS = LockerKeyspace { value = 10L }
}
