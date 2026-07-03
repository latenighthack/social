package com.latenighthack.social.remotecontent.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Filesystem-backed [ContentStore]. Each content id maps to a bytes file plus a
 * `.mime` sidecar, sharded under the first two hex characters of the id. The base
 * directory defaults to ./content and is typically set from an environment
 * variable at startup.
 */
class FileContentStore(private val baseDir: File) : ContentStore {
    override suspend fun create(id: ByteArray, mimeType: String?) {
        withContext(Dispatchers.IO) {
            val file = fileFor(id)
            file.parentFile.mkdirs()
            val mimeFile = File(file.path + MIME_SUFFIX)
            if (mimeType != null) {
                mimeFile.writeText(mimeType)
            } else {
                mimeFile.delete()
            }
        }
    }

    override suspend fun put(id: ByteArray, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val file = fileFor(id)
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
    }

    override suspend fun get(id: ByteArray): StoredContent? {
        return withContext(Dispatchers.IO) {
            val file = fileFor(id)
            if (!file.exists()) {
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
        const val MIME_SUFFIX = ".mime"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
