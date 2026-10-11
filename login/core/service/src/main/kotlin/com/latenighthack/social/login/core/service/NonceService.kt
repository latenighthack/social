package com.latenighthack.social.login.core.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-use, short-lived nonces for the social authenticate flow. The client binds an issued nonce
 * into the native Apple/Google auth request; the provider echoes it (Apple: its SHA-256 hex) into the
 * id token's `nonce` claim, and the server consumes it here — so a captured id token cannot be
 * replayed to recover the account key. Production uses the host challenge store so consumption
 * survives restart and is shared across instances. The standalone test fallback is in memory.
 */
class NonceService(
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val store: ChallengeStore? = null,
    private val nonceStore: NonceStore? = null,
) {
    constructor(ttlMillis: Long = 5 * 60 * 1000L, clock: () -> Long = System::currentTimeMillis,
        random: SecureRandom = SecureRandom(), store: NonceStore) : this(ttlMillis, clock, random, nonceStore = store)

    // nonce -> expiry epoch millis
    private val issued = ConcurrentHashMap<String, Long>()

    val expiresInSeconds: Long = ttlMillis / 1000

    suspend fun issue(): String {
        prune()
        nonceStore?.prune(clock())
        val nonce = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
        if (nonceStore != null) nonceStore.put(nonce, clock() + ttlMillis)
        else if (store == null) issued[nonce] = clock() + ttlMillis else store.put(
            com.latenighthack.social.login.v1.ChallengeRecord(lookupKey = lookup(nonce), expiryMillis = clock() + ttlMillis,
                attemptsRemaining = 1))
        return nonce
    }

    /**
     * Consumes [rawNonce] if it was issued, is unexpired, and [tokenNonceClaim] matches it — either
     * verbatim (Google) or as its SHA-256 hex (Apple hashes the request nonce). Single-use: the nonce
     * is spent whether or not the claim matched.
     */
    suspend fun consume(rawNonce: String, tokenNonceClaim: String?): Boolean {
        if (rawNonce.isEmpty()) return false
        val expiry = if (nonceStore != null) nonceStore.take(rawNonce) else if (store == null) issued.remove(rawNonce) else store.database.transaction("social.login.credentials") {
            val record = store.getByLookup(lookup(rawNonce)) ?: return@transaction null
            store.deleteByLookup(lookup(rawNonce))
            record.expiryMillis
        }
        if (expiry == null) return false
        if (expiry <= clock()) return false
        if (tokenNonceClaim.isNullOrEmpty()) return false
        return tokenNonceClaim == rawNonce || tokenNonceClaim.equals(sha256Hex(rawNonce), ignoreCase = true)
    }

    private fun lookup(nonce: String) = byteArrayOf(2) + MessageDigest.getInstance("SHA-256").digest(nonce.encodeToByteArray())

    private fun prune() {
        val now = clock()
        issued.entries.removeIf { it.value <= now }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
}
