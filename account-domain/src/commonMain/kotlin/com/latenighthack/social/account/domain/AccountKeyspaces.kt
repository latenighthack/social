package com.latenighthack.social.account.domain

import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * Keyspace + locker ids the account feature reserves inside its private room.
 * Keyspace numbering is a cross-feature allocation concern; account reserves `1`.
 */
internal object AccountKeyspaces {
    val ACCOUNT_STATE = LockerKeyspace { value = 1L }

    /** The single locker holding the account's AccountState within the private room. */
    val ACCOUNT_STATE_LOCKER = LockerId(ByteArray(32), ACCOUNT_STATE)
}
