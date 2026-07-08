package com.latenighthack.social.login.core.service

import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.login.v1.CredentialRecord
import com.latenighthack.social.login.v1.fromByteArray
import com.latenighthack.social.login.v1.toByteArray

/**
 * The durable custodial store: a bound login method → account key, keyed by the service-computed
 * [CredentialRecord.lookupKey] (provider tag + subject). The stored private key is AES-GCM ciphertext
 * (see [CustodyCrypto]), never plaintext. Backed by ktstore so a persistent [StoreDelegate] is a
 * drop-in; the default deployment supplies an in-memory delegate (MVP, does not survive a restart).
 */
class CredentialStore(delegate: StoreDelegate) : Store<CredentialRecord>(
    delegate,
    "login_credentials",
    CredentialRecord::toByteArray,
    CredentialRecord.Companion::fromByteArray,
) {
    private val lookupKey = bytesIndex(CredentialRecord::lookupKey).also { primaryKey(it) }

    suspend fun getByLookup(lookup: ByteArray): CredentialRecord? = get(lookupKey.eq(lookup))

    suspend fun put(record: CredentialRecord) = save(record)
}
