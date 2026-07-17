package com.latenighthack.social.runtime

import com.latenighthack.lockers.connector.LockersClient

/**
 * The three-phase lifecycle every feature manager shares: the graph constructs the manager, [prepare]
 * registers its stores on the shared store delegate, and [start] drives it over a [LockersClient]
 * once that client — built from the managers' own key sources — exists. Each feature contributes its
 * managers into a `Set<DomainLifecycle>` so the app can boot and stop them all without naming each one.
 * Resumable: [stop] leaves the manager ready to [start] again.
 *
 * Ordering matters: the store delegate creates every registered store in one shot (on web the
 * IndexedDB upgrade fires exactly once), and `LockersClient.create` performs that single
 * `createStores()` call. So the app must [prepare] every manager first, then build the client, then
 * [start] every manager. A manager must never call `createStores()` itself, and its stores are only
 * usable once the client has been built.
 */
interface DomainLifecycle {
    suspend fun prepare() {}
    fun start(lockers: LockersClient)
    fun stop()
}
