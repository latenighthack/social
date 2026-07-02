package com.latenighthack.social.account.domain

import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.AuthenticationKeySource
import com.latenighthack.lockers.connector.LockKeySource

/**
 * The account's key source for the `LockersClient`: it just forwards to its [account] for
 * key material and tells the account when the session is (re)generated or revoked. (Making
 * several key sources cooperate for different rooms is a later, separate concern.)
 */
class AccountKeySource(
    private val account: AccountManagerImpl,
) : AuthenticationKeySource, LockKeySource {

    override suspend fun getSessionKeyPair(): Secp256r1KeyPair = account.sessionKeyPair()

    override suspend fun hasSessionKeyPair(): Boolean = account.hasSessionKey()

    override suspend fun generateSessionKeyPair() = account.regenerateSession()

    override suspend fun revokeKeys() = account.revokeSession()

    override suspend fun writeKeyFor(roomId: RoomId, lockerId: LockerId): Secp256r1KeyPair? =
        account.writeKey(roomId, lockerId)
}
