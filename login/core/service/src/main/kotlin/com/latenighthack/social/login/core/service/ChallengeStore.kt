package com.latenighthack.social.login.core.service

import com.latenighthack.ktstore.*
import kotlinx.coroutines.sync.withLock
import com.latenighthack.social.login.v1.ChallengeRecord
import com.latenighthack.social.login.v1.fromByteArray
import com.latenighthack.social.login.v1.toByteArray

/**
 * Ephemeral proof records: pending magic-link / OTP challenges and issued bind tickets, keyed by
 * [ChallengeRecord.lookupKey]. A challenge is keyed by (provider, subject) so a new start overwrites
 * the prior one; a bind ticket is keyed by a hash of the high-entropy ticket. Challenge secrets are
 * stored only as PBKDF2 salted hashes (see [Pbkdf2Hasher]). Backed by ktstore, in-memory by default.
 */
class ChallengeStore(internal val database: Database) : Store<ChallengeRecord>(database, ChallengeStoreDefinitionV1) {
    private var pruneAfter: LocalContinuation? = null
    private val pruneMutex = kotlinx.coroutines.sync.Mutex()

    private val lookupKey = ChallengeStoreDefinitionV1.lookupKey

    suspend fun pruneExpired(now: Long, excludeLookup: ByteArray? = null) = pruneMutex.withLock {
        val page = database.transaction(setOf(ChallengeStoreDefinitionV1.storeName), TransactionMode.READ_ONLY) {
            query(ChallengeStoreDefinitionV1.storeName, lookupKey.query(128, after = pruneAfter))
        }
        pruneAfter = page.continuation
        for (value in page.records) {
            val row = if (value is ByteArray) ChallengeStoreDefinitionV1.decode(value) else value as ChallengeRecord
            if (excludeLookup?.contentEquals(row.lookupKey) == true) continue
            if (row.expiryMillis == 0L || row.expiryMillis > now) continue
            database.transaction("social.login.credentials") {
                val current = getByLookup(row.lookupKey)
                if (current != null && current.expiryMillis != 0L && current.expiryMillis <= now) deleteByLookup(row.lookupKey)
            }
        }
    }

    suspend fun getByLookup(lookup: ByteArray): ChallengeRecord? = get(lookupKey.eq(lookup))

    suspend fun put(record: ChallengeRecord) = save(record)

    suspend fun deleteByLookup(lookup: ByteArray) = delete(lookupKey.eq(lookup))
}
