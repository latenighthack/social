package com.latenighthack.social.debug.domain

import com.latenighthack.ktbuf.bytes.toBase64String
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.LockerKeyspace
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockerClient
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.runtime.DomainLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

class DebugManagerImpl(
    private val codecs: LockerCodecs,
) : DebugManager, DomainLifecycle {

    private var lockers: LockersClient? = null

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
    }

    override fun stop() {
        lockers = null
    }

    override fun watchLockers(): Flow<Map<String, Any?>> = flow {
        val client = lockers ?: error("debug requires start(lockers) first")
        // Seed with the current cache, then fold each change's payload onto it. Folding the delta
        // (rather than re-reading the cache on every change) avoids depending on the ordering
        // between the connector's cache write and its change emission.
        val seed = client.getAllKnownLockers().associateBy { LockerKey(it.roomId, it.lockerId) }
        emitAll(
            client.lockerChanges
                .runningFold(seed) { acc, update -> acc.applyUpdate(update) }
                .map { it.toDump() },
        )
    }.distinctUntilChanged()

    // roomId + lockerId (which itself carries the keyspace) uniquely identify a locker; both have
    // content-based equals/hashCode, so they are safe as a map key.
    private data class LockerKey(val roomId: RoomId, val lockerId: LockerId)

    private fun Map<LockerKey, LockerClient.LockerUpdate>.applyUpdate(
        update: LockerClient.LockerUpdate,
    ): Map<LockerKey, LockerClient.LockerUpdate> {
        val key = LockerKey(update.roomId, update.lockerId)
        return if (update.deleted) this - key else this + (key to update)
    }

    private fun Map<LockerKey, LockerClient.LockerUpdate>.toDump(): Map<String, Any?> {
        val byKeyspace = values.groupBy { it.lockerId.keyspace?.value ?: 0L }
        val dump = LinkedHashMap<String, Any?>()
        for (keyspaceValue in byKeyspace.keys.sorted()) {
            val updates = byKeyspace.getValue(keyspaceValue)
            val entry = codecs.entryFor(LockerKeyspace { value = keyspaceValue })
            val label = entry?.let { "$keyspaceValue (${it.name})" } ?: "$keyspaceValue"
            dump[label] = updates.map { it.toEntry(entry?.codec) }
        }
        return dump
    }

    private fun LockerClient.LockerUpdate.toEntry(codec: LockerCodec?): Map<String, Any?> {
        val value = codec?.let { runCatching { it.decode(payload) }.getOrNull() }
            ?: mapOf("raw" to payload.toBase64String())
        return mapOf(
            "room" to roomId.rawValue.toBase64String(),
            "key" to lockerId.rawValue.toBase64String(),
            "value" to value,
        )
    }
}
