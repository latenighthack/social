package com.latenighthack.social.remotecontent.service

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** In-memory [ContentStore] for tests and local development; nothing is persisted. */
class InMemoryContentStore : ContentStore {
    private class Entry(val mimeType: String?, val bytes: ByteArray?)

    private val entries = ConcurrentHashMap<String, Entry>()

    override suspend fun create(id: ByteArray, mimeType: String?) {
        entries[key(id)] = Entry(mimeType, null)
    }

    override suspend fun put(id: ByteArray, bytes: ByteArray) {
        val k = key(id)
        entries[k] = Entry(entries[k]?.mimeType, bytes)
    }

    override suspend fun get(id: ByteArray): StoredContent? {
        val entry = entries[key(id)] ?: return null
        val bytes = entry.bytes ?: return null
        return StoredContent(bytes, entry.mimeType)
    }

    private fun key(id: ByteArray): String = Base64.getEncoder().encodeToString(id)
}
