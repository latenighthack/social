package com.latenighthack.social.login.core.service

import com.latenighthack.ktstore.*
import java.nio.ByteBuffer
import java.security.MessageDigest

interface NonceStore {
    suspend fun put(nonce: String, expiry: Long)
    suspend fun take(nonce: String): Long?
    suspend fun prune(now: Long)
}
class InMemoryNonceStore : NonceStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override suspend fun put(nonce: String, expiry: Long) { values[nonce] = expiry }
    override suspend fun take(nonce: String): Long? = values.remove(nonce)
    override suspend fun prune(now: Long) { values.entries.removeIf { it.value <= now } }
}
internal data class NonceRow(val id: ByteArray, val expires: Long) {
    fun encode(): ByteArray = ByteBuffer.allocate(40).put(id).putLong(expires).array()
    companion object { fun decode(bytes: ByteArray): NonceRow { require(bytes.size == 40); val buffer = ByteBuffer.wrap(bytes); return NonceRow(ByteArray(32).also(buffer::get), buffer.long) } }
}
internal object NonceDefinition : StoreDefinition<NonceRow>(StoreName("social_login_nonces"), "sha256-expiry-v1", NonceRow::decode, NonceRow::encode) {
    val id = bytesIndex(IndexName("id"), NonceRow::id, "sha256-v1").also { primaryKey(it) }
    val expires = mappedIndex(IndexName("expires"), NonceRow::expires, object : StorageCodec<Long, ByteArray> {
        override fun encode(value: Long) = OrderedKeyEncoding.long(value)
        override fun key(name: IndexName) = StoreKey.SerializedKey(name.value)
    }, "ordered-long-v1")
}
class DurableNonceStore(private val database: Database) : NonceStore {
    private class Rows(database: Database) : Store<NonceRow>(database, NonceDefinition) {
        suspend fun put(row: NonceRow) = save(row)
        suspend fun find(id: ByteArray) = get(NonceDefinition.id.eq(id))
        suspend fun remove(id: ByteArray) = delete(NonceDefinition.id.eq(id))
    }
    private val rows = Rows(database)
    private fun id(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    override suspend fun put(nonce: String, expiry: Long) { rows.put(NonceRow(id(nonce), expiry)) }
    override suspend fun take(nonce: String): Long? {
        val id = id(nonce)
        return database.transaction("social.nonce." + java.util.Base64.getEncoder().encodeToString(id)) {
            val value = rows.find(id) ?: return@transaction null
            rows.remove(id)
            value.expires
        }
    }
    override suspend fun prune(now: Long) {
        // Small bounded maintenance batch; never scans or decodes the full nonce table.
        database.transaction(setOf(NonceDefinition.storeName)) {
            deleteBatch(NonceDefinition.storeName, NonceDefinition.expires.query(128, upper = now))
        }
    }
}
