package com.latenighthack.social.debug.usecase

import com.latenighthack.social.debug.domain.DebugManager
import kotlinx.coroutines.flow.Flow

/**
 * Watches the client's known lockers, grouped by keyspace with each value decoded into a readable
 * hierarchical map, for debugging. Re-emits as lockers change.
 */
class WatchLockersUseCase(
    private val debug: DebugManager,
) {
    fun watch(): Flow<Map<String, Any?>> = debug.watchLockers()
}
