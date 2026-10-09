package com.latenighthack.social.login.core.service

import com.latenighthack.ktstore.*
import com.latenighthack.social.login.v1.CredentialRecord
import com.latenighthack.social.login.v1.fromByteArray
import com.latenighthack.social.login.v1.toByteArray

/**
 * The durable custodial store: a bound login method → account key, keyed by the service-computed
 * [CredentialRecord.lookupKey] (provider tag + subject). The stored private key is AES-GCM ciphertext
 * (see [CustodyCrypto]), never plaintext. Backed by ktstore so a persistent [Database] is a
 * drop-in; the default deployment supplies an in-memory database (MVP, does not survive a restart).
 */
class CredentialStore(database: Database) : Store<CredentialRecord>(database, CredentialStoreDefinitionV1) {
    private val lookupKey = CredentialStoreDefinitionV1.lookupKey

    suspend fun getByLookup(lookup: ByteArray): CredentialRecord? = get(lookupKey.eq(lookup))

    suspend fun put(record: CredentialRecord) = save(record)
}
