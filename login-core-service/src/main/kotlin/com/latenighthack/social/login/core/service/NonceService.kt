package com.latenighthack.social.login.core.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-use, short-lived nonces for the social authenticate flow. The client binds an issued nonce
 * into the native Apple/Google auth request; the provider echoes it (Apple: its SHA-256 hex) into the
 * id token's `nonce` claim, and the server consumes it here — so a captured id token cannot be
 * replayed to recover the account key. The server extension supplies shared durable storage;
 * the in-memory default is reserved for isolated tests and development.
 */
class NonceService(
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val store: NonceStore = InMemoryNonceStore(),
) {
    // nonce -> expiry epoch millis

    val expiresInSeconds: Long = ttlMillis / 1000

    suspend fun issue(): String {
        store.prune(clock())
        val nonce = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
        store.put(nonce, clock() + ttlMillis)
        return nonce
    }

    /**
     * Consumes [rawNonce] if it was issued, is unexpired, and [tokenNonceClaim] matches it — either
     * verbatim (Google) or as its SHA-256 hex (Apple hashes the request nonce). Single-use: the nonce
     * is spent whether or not the claim matched.
     */
    suspend fun consume(rawNonce: String, tokenNonceClaim: String?): Boolean {
        if (rawNonce.isEmpty()) return false
        val expiry = store.take(rawNonce) ?: return false
        if (expiry <= clock()) return false
        if (tokenNonceClaim.isNullOrEmpty()) return false
        return tokenNonceClaim == rawNonce || tokenNonceClaim.equals(sha256Hex(rawNonce), ignoreCase = true)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
}
