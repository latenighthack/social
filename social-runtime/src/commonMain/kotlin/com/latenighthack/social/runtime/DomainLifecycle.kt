package com.latenighthack.social.runtime

import com.latenighthack.lockers.connector.LockersClient

/**
 * The two-phase lifecycle every feature manager shares: the graph constructs the manager, then it
 * is driven over a [LockersClient] once that client — built from the managers' own key sources —
 * exists. Each feature contributes its managers into a `Set<DomainLifecycle>` so the app can start
 * and stop them all without naming each one. Resumable: [stop] leaves the manager ready to [start]
 * again.
 */
interface DomainLifecycle {
    fun start(lockers: LockersClient)
    fun stop()
}
