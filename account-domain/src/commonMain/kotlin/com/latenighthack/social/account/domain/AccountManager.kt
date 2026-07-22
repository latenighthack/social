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

    /**
     * The account's private room derived from the locally persisted identity key, or null if no
     * key exists yet. Reads only local key storage — no connection or [Ready] state required — so
     * callers can address the account room opportunistically before the session connects.
     */
    suspend fun localAccountRoom(): RoomId?

    /**
     * Adopt [privateKey] as the identity and restore the account from its private room,
     * returning the 33-byte compressed account id. Throws [NoAccountToRestoreException] if the
     * room has no account state to restore, or [IllegalArgumentException] if the key is invalid.
     */
    suspend fun restoreAccount(privateKey: ByteArray): ByteArray

    /** Revoke the identity and sign out. */
    suspend fun signOut()

    /**
     * Export the account's identity — its 33-byte compressed account id and its raw private key — so
     * a custodial login service can hold it on the user's behalf (see the login feature). This is the
     * only path that surfaces the private key outside the account; it is otherwise handed only to the
     * lockers client via [AccountKeySource]. Throws if no identity exists yet.
     */
    suspend fun exportIdentity(): Identity

    /** An account's identity secret: its account id paired with the raw private key that backs it. */
    class Identity(val accountId: ByteArray, val privateKey: ByteArray)

    /** The observable account state. */
    sealed interface Lifecycle {
        /** No key yet — offer to create an account. */
        data object NoAccount : Lifecycle

        /** A key exists; the session is opening or reconnecting. */
        @Deprecated("no longer emitted: a present key now goes straight to Ready (offline-first); observe the client's isConnected for connectivity")
        data object Connecting : Lifecycle

        /**
         * A key exists; account id and private room are derived locally, so this is emitted with
         * or without a connection (cached state serves reads offline). The private room's
         * server-side lock/init runs on the first connected tick.
         */
        data class Ready(val accountId: ByteArray, val privateRoom: RoomId) : Lifecycle

        /** A terminal session failure (key rejected, upgrade required). */
        data class Fatal(val error: StreamFatalError) : Lifecycle

        /** The account was signed out (key revoked). */
        data object SignedOut : Lifecycle
    }
}

/** Thrown by [AccountManager.restoreAccount] when the key's private room holds no account state. */
class NoAccountToRestoreException(
    message: String = "no account state in the private room to restore",
) : Exception(message)
