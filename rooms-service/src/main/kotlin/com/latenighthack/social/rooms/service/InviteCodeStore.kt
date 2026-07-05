package com.latenighthack.social.rooms.service

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A minted invite code's server-held secret: which room it admits to, the room's shared private key
 * (so the server can seal per-joiner grants), and the policy the server enforces before admitting.
 * `maxUses == 0` means unlimited; `expiryMillis == 0` means no expiry; an empty `allowedProfileId`
 * means any profile may join.
 */
class StoredInviteCode(
    val roomId: ByteArray,
    val groupPrivateKey: ByteArray,
    val expiryMillis: Long,
    val maxUses: Long,
    val allowedProfileId: ByteArray,
)

/**
 * The backing store for invite codes. The stored [StoredInviteCode.groupPrivateKey] is sensitive —
 * a durable implementation MUST encrypt it at rest under a service master key. The in-memory
 * implementation below is for tests and local dev and does not survive a restart.
 */
interface InviteCodeStore {
    suspend fun put(code: ByteArray, record: StoredInviteCode)

    suspend fun get(code: ByteArray): StoredInviteCode?

    suspend fun delete(code: ByteArray)

    /**
     * Atomically consume one use of [code]. Returns false if the code is absent or already
     * exhausted. Only called for use-limited codes (`maxUses > 0`).
     */
    suspend fun consumeUse(code: ByteArray): Boolean
}

/** In-memory [InviteCodeStore] for tests and local dev; nothing is persisted. */
class InMemoryInviteCodeStore : InviteCodeStore {
    private class Entry(val record: StoredInviteCode, val remaining: AtomicLong)

    private val entries = ConcurrentHashMap<String, Entry>()

    override suspend fun put(code: ByteArray, record: StoredInviteCode) {
        entries[key(code)] = Entry(record, AtomicLong(record.maxUses))
    }

    override suspend fun get(code: ByteArray): StoredInviteCode? = entries[key(code)]?.record

    override suspend fun delete(code: ByteArray) {
        entries.remove(key(code))
    }

    override suspend fun consumeUse(code: ByteArray): Boolean {
        val entry = entries[key(code)] ?: return false
        while (true) {
            val current = entry.remaining.get()
            if (current <= 0L) return false
            if (entry.remaining.compareAndSet(current, current - 1L)) return true
        }
    }

    private fun key(code: ByteArray): String = Base64.getEncoder().encodeToString(code)
}
