package com.latenighthack.social.debug.domain

import com.latenighthack.lockers.connector.LockersClient
import kotlinx.coroutines.flow.Flow

/**
 * Introspection over the client's lockers, for debugging. Streams every locker the connector knows
 * about — grouped by keyspace, each value decoded into a readable hierarchical map by its registered
 * [LockerCodec] (raw base64 when none is registered) — and re-emits as lockers are added, changed, or
 * removed. Read-only: it never writes.
 */
interface DebugManager {
    fun start(lockers: LockersClient)
    fun stop()

    /**
     * A live view of all known lockers, grouped by keyspace. The map is keyspace label →
     * list of `{ "room", "key", "value" }` entries, where `value` is the decoded map (or
     * `{ "raw": <base64> }` when the keyspace has no codec). Re-emits on every locker change.
     */
    fun watchLockers(): Flow<Map<String, Any?>>
}
