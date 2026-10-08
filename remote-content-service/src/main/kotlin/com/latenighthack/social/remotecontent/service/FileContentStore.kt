package com.latenighthack.social.remotecontent.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Filesystem-backed [ContentStore]. Each content id maps to a bytes file plus a
 * `.mime` sidecar, sharded under the first two hex characters of the id. The base
 * directory defaults to ./content and is typically set from an environment
 * variable at startup.
 */
class FileContentStore(private val baseDir: File,
    private val maxStoredBytes: Long = 2L * 1024 * 1024 * 1024,
    private val maxContentCount: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis) : ContentStore {
    init { require(maxStoredBytes >= MAX_CONTENT_BYTES && maxContentCount > 0) }
    override suspend fun create(id: ByteArray, mimeType: String?, uploadToken: ByteArray): Unit = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            baseDir.mkdirs()
            quotaLock {
            ensureCapacity()

            val file = fileFor(id)
            file.parentFile.mkdirs()
            check(!file.exists() && !File(file.path + ".upload").exists()) { "content id already reserved" }
            File(file.path + ".expires").writeText((clock() + 15 * 60_000).toString())
            File(file.path + ".upload").writeBytes(hash(uploadToken))
            val mimeFile = File(file.path + MIME_SUFFIX)
            if (mimeType != null) {
                mimeFile.writeText(mimeType)
            } else {
                mimeFile.delete()
            }
            }
        }
    }

    override suspend fun put(id: ByteArray, bytes: ByteArray, uploadToken: ByteArray): Unit = writeMutex.withLock {
        require(bytes.size <= MAX_CONTENT_BYTES) { "content exceeds 16 MiB" }
        withContext(Dispatchers.IO) {
            quotaLock {
            val file = fileFor(id)
            val capability = File(file.path + ".upload")
            if (!capability.exists() || (!file.exists() && File(file.path + ".expires").takeIf { it.exists() }
                    ?.readText()?.toLongOrNull()?.let { it <= clock() } == true)) throw UploadRejected()
            FileChannel.open(File(file.path + ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    if (!MessageDigest.isEqual(capability.readBytes(), hash(uploadToken))) throw UploadRejected()
                    if (file.exists()) {
                        if (!file.readBytes().contentEquals(bytes)) throw UploadRejected(conflict = true)
                    } else file.writeBytes(bytes)
                }
            }
            }
        }
    }

    /** Cross-process quota is reconstructed from the owned directory, including pending reservations. */
    private fun <T> quotaLock(block: () -> T): T = FileChannel.open(File(baseDir, ".quota.lock").toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel -> channel.lock().use { block() } }

    private fun ensureCapacity() {
        var used = 0L
        var count = 0
        var scanned = 0
        for (capability in baseDir.walkTopDown().filter { it.isFile }) {
            check(++scanned <= maxContentCount * 6) { "content directory exceeds configured quota" }
            if (!capability.name.endsWith(".upload")) continue
            val file = File(capability.path.removeSuffix(".upload"))
            val expiry = File(file.path + ".expires")
            if (!file.exists() && expiry.takeIf { it.exists() }?.readText()?.toLongOrNull()?.let { it <= clock() } == true) {
                capability.delete(); expiry.delete(); File(file.path + ".mime").delete(); File(file.path + ".lock").delete()
                continue
            }
            count++
            used += if (file.exists()) file.length() else MAX_CONTENT_BYTES.toLong()
            check(count < maxContentCount && used <= maxStoredBytes - MAX_CONTENT_BYTES) { "content storage quota exhausted" }
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    override suspend fun get(id: ByteArray): StoredContent? {
        return withContext(Dispatchers.IO) {
            val file = fileFor(id)
            if (!file.exists() || file.length() > MAX_CONTENT_BYTES) {
                return@withContext null
            }
            val mimeFile = File(file.path + MIME_SUFFIX)
            val mimeType = if (mimeFile.exists()) mimeFile.readText().ifBlank { null } else null
            StoredContent(file.readBytes(), mimeType)
        }
    }

    private fun fileFor(id: ByteArray): File {
        val hex = id.toHex()
        val shard = hex.take(2).ifEmpty { "00" }
        val rest = hex.drop(2).ifEmpty { hex }
        return File(File(baseDir, shard), rest)
    }

    private companion object {
        val writeMutex = Mutex()
        const val MAX_CONTENT_BYTES = 16 * 1024 * 1024
        const val MIME_SUFFIX = ".mime"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
