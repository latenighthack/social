package com.latenighthack.social.login.service

import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.login.v1.ChallengeRecord
import com.latenighthack.social.login.v1.fromByteArray
import com.latenighthack.social.login.v1.toByteArray

/**
 * Ephemeral proof records: pending magic-link / OTP challenges and issued bind tickets, keyed by
 * [ChallengeRecord.lookupKey]. A challenge is keyed by (provider, subject) so a new start overwrites
 * the prior one; a bind ticket is keyed by a hash of the high-entropy ticket. Challenge secrets are
 * stored only as PBKDF2 salted hashes (see [Pbkdf2Hasher]). Backed by ktstore, in-memory by default.
 */
class ChallengeStore(delegate: StoreDelegate) : Store<ChallengeRecord>(
    delegate,
    "login_challenges",
    ChallengeRecord::toByteArray,
    ChallengeRecord.Companion::fromByteArray,
) {
    private val lookupKey = bytesIndex(ChallengeRecord::lookupKey).also { primaryKey(it) }

    suspend fun getByLookup(lookup: ByteArray): ChallengeRecord? = get(lookupKey.eq(lookup))

    suspend fun put(record: ChallengeRecord) = save(record)

    suspend fun deleteByLookup(lookup: ByteArray) = delete(lookupKey.eq(lookup))
}
