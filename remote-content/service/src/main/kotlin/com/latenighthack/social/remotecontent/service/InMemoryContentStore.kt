package com.latenighthack.social.remotecontent.service

import java.util.Base64
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** In-memory [ContentStore] for tests and local development; nothing is persisted. */
class InMemoryContentStore : ContentStore {
    private class Entry(val mimeType: String?, val bytes: ByteArray?, val tokenHash: ByteArray)

    private val entries = ConcurrentHashMap<String, Entry>()

    override suspend fun create(id: ByteArray, mimeType: String?, uploadToken: ByteArray) {
        entries[key(id)] = Entry(mimeType, null, hash(uploadToken))
    }

    override suspend fun put(id: ByteArray, bytes: ByteArray, uploadToken: ByteArray) {
        entries.compute(key(id)) { _, entry ->
            if (entry == null || !MessageDigest.isEqual(entry.tokenHash, hash(uploadToken))) throw UploadRejected()
            if (entry.bytes != null) {
                if (!entry.bytes.contentEquals(bytes)) throw UploadRejected(conflict = true)
                entry
            } else Entry(entry.mimeType, bytes.copyOf(), entry.tokenHash)
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    override suspend fun get(id: ByteArray): StoredContent? {
        val entry = entries[key(id)] ?: return null
        val bytes = entry.bytes ?: return null
        return StoredContent(bytes.copyOf(), entry.mimeType)
    }

    private fun key(id: ByteArray): String = Base64.getEncoder().encodeToString(id)
}
