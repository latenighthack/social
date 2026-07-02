package com.latenighthack.social.account.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.StreamFatalError
import kotlinx.coroutines.flow.StateFlow

/**
 * The account: the identity key, the public-key-locked "private room" it authors, and the
 * session over both. It owns the key material; [AccountKeySource] is the thin adapter that
 * hands that material to the `LockersClient`. Build the client from an [AccountKeySource]
 * wrapping this manager, then [start] the manager with that client.
 */
interface AccountManager {
    val lifecycle: StateFlow<Lifecycle>

    /** Begin driving the lifecycle over [lockers]. Idempotent; resumable after [stop]. */
    fun start(lockers: LockersClient)

    /** Stop driving the lifecycle. Resume with [start]. */
    fun stop()

    /** Create the identity if none exists; returns the 33-byte compressed account id. */
    suspend fun createAccount(): ByteArray

    /** Revoke the identity and sign out. */
    suspend fun signOut()

    /** The observable account state. */
    sealed interface Lifecycle {
        /** No key yet — offer to create an account. */
        data object NoAccount : Lifecycle

        /** A key exists; the session is opening or reconnecting. */
        data object Connecting : Lifecycle

        /** Connected, private room locked and loaded. */
        data class Ready(val accountId: ByteArray, val privateRoom: RoomId) : Lifecycle

        /** A terminal session failure (key rejected, upgrade required). */
        data class Fatal(val error: StreamFatalError) : Lifecycle

        /** The account was signed out (key revoked). */
        data object SignedOut : Lifecycle
    }
}
