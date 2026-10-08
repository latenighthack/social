package com.latenighthack.social.runtime

import com.latenighthack.lockers.connector.LockersClient

/**
 * Feature lifecycle over a host-owned configured Database. The host composes all
 * storage definitions and opens/migrates the handle before prepare or start.
 * prepare validates/binds each feature's stores; start attaches locker flows.
 * Managers never change the database schema or close a shared handle.
 */
interface DomainLifecycle {
    val taskHealth: kotlinx.coroutines.flow.StateFlow<TaskHealth> get() = IdleTaskHealth
    suspend fun prepare() {}
    fun start(lockers: LockersClient)
    fun stop()
    suspend fun stopAndJoin() { stop() }
}

private val IdleTaskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
