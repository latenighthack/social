package com.latenighthack.social.login.core.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-use, short-lived nonces for the social authenticate flow. The client binds an issued nonce
 * into the native Apple/Google auth request; the provider echoes it (Apple: its SHA-256 hex) into the
 * id token's `nonce` claim, and the server consumes it here — so a captured id token cannot be
 * replayed to recover the account key. In-memory only: a nonce is bound to the server that issued it.
 */
class NonceService(
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    // nonce -> expiry epoch millis
    private val issued = ConcurrentHashMap<String, Long>()

    val expiresInSeconds: Long = ttlMillis / 1000

    fun issue(): String {
        prune()
        val nonce = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
        issued[nonce] = clock() + ttlMillis
        return nonce
    }

    /**
     * Consumes [rawNonce] if it was issued, is unexpired, and [tokenNonceClaim] matches it — either
     * verbatim (Google) or as its SHA-256 hex (Apple hashes the request nonce). Single-use: the nonce
     * is spent whether or not the claim matched.
     */
    fun consume(rawNonce: String, tokenNonceClaim: String?): Boolean {
        if (rawNonce.isEmpty()) return false
        val expiry = issued.remove(rawNonce) ?: return false
        if (expiry < clock()) return false
        if (tokenNonceClaim.isNullOrEmpty()) return false
        return tokenNonceClaim == rawNonce || tokenNonceClaim.equals(sha256Hex(rawNonce), ignoreCase = true)
    }

    private fun prune() {
        val now = clock()
        issued.entries.removeIf { it.value < now }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
}
