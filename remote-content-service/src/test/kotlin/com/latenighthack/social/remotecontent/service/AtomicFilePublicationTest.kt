package com.latenighthack.social.remotecontent.service

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class AtomicFilePublicationTest {
    @Test fun concurrentReadersOnlySeeCompletePublishedBytes() = runTest {
        val directory = Files.createTempDirectory("content-atomic").toFile()
        try {
            val writer = FileContentStore(directory)
            val reader = FileContentStore(directory)
            val id = byteArrayOf(1, 2)
            val token = ByteArray(32) { 3 }
            val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
            writer.create(id, "image/png", token)
            val publication = async(Dispatchers.Default) { writer.put(id, bytes, token) }
            repeat(100) { reader.get(id)?.let { assertContentEquals(bytes, it.bytes) } }
            publication.await()
            assertContentEquals(bytes, reader.get(id)!!.bytes)
        } finally { directory.deleteRecursively() }
    }
}
