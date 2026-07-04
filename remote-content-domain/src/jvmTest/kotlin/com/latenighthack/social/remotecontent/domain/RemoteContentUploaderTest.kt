package com.latenighthack.social.remotecontent.domain

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.hasSize
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.remotecontent.v1.ContentId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals

class RemoteContentUploaderTest {

    /**
     * A [RemoteContentClient] that mints deterministic ids/URLs and records each PUT, optionally
     * failing the first [failuresBeforeSuccess] attempts per URL to exercise retry.
     */
    private class FakeRemoteContentClient(
        private val failuresBeforeSuccess: Int = 0,
    ) : RemoteContentClient {
        val uploaded = ConcurrentHashMap<String, ByteArray>()
        private val attempts = ConcurrentHashMap<String, Int>()
        private var counter = 0

        override suspend fun createContent(mimeType: String?): CreatedContent {
            val n = counter++
            return CreatedContent(
                contentId = ContentId { rawValue = byteArrayOf(n.toByte()) },
                uploadUrl = "upload/$n",
                downloadUrl = "download/$n",
            )
        }

        override suspend fun upload(uploadUrl: String, bytes: ByteArray) {
            val attempt = attempts.merge(uploadUrl, 1, Int::plus)!!
            if (attempt <= failuresBeforeSuccess) throw RuntimeException("transient failure")
            uploaded[uploadUrl] = bytes
        }

        override suspend fun download(downloadUrl: String): DownloadedContent = error("unused")
        override suspend fun upload(bytes: ByteArray, mimeType: String?): CreatedContent = error("unused")
        override fun watchUpload(uploadUrl: String): Flow<TransferProgress?> = flowOf(null)
        override fun watchDownload(downloadUrl: String): Flow<TransferProgress?> = flowOf(null)
    }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) =
        withTimeout(10_000) { while (!condition()) delay(10) }

    @Test
    fun `enqueue returns the download URL immediately and durably queues the bytes before transfer`() =
        runBlocking {
            val delegate: StoreDelegate = InMemoryStoreDelegate()
            val fake = FakeRemoteContentClient()
            // Not started: no drain loop runs, so the bytes stay queued and are never PUT.
            val uploader = RemoteContentUploaderImpl(fake, delegate)

            val upload = uploader.enqueue(byteArrayOf(1, 2, 3), "image/png")

            assertThat(upload.downloadUrl).isEqualTo("download/0")
            assertThat(upload.status).isEqualTo(UploadStatus.Queued)
            assertThat(fake.uploaded.keys.toList()).isEmpty()
            // The freshly enqueued upload is observable as queued.
            assertThat(uploader.watchUpload(upload.contentId).first()?.status).isEqualTo(UploadStatus.Queued)
            val pending = PendingUploadStore(delegate).getAllPending()
            assertThat(pending).hasSize(1)
            assertThat(pending.single().uploadUrl).isEqualTo("upload/0")
            assertContentEquals(byteArrayOf(1, 2, 3), pending.single().bytes)
        }

    @Test
    fun `a started uploader transfers the bytes, drains the queue, and reports completion`() = runBlocking {
        val delegate = InMemoryStoreDelegate()
        val fake = FakeRemoteContentClient()
        val uploader = RemoteContentUploaderImpl(fake, delegate)
        uploader.start()

        val upload = uploader.enqueue(byteArrayOf(9, 8, 7), "image/jpeg")

        awaitUntil { fake.uploaded.containsKey("upload/0") }
        assertContentEquals(byteArrayOf(9, 8, 7), fake.uploaded["upload/0"])
        awaitUntil { PendingUploadStore(delegate).getAllPending().isEmpty() }
        // The upload is observable as completed once the transfer lands.
        awaitUntil { uploader.watchUpload(upload.contentId).first()?.status == UploadStatus.Completed }

        uploader.stop()
    }

    @Test
    fun `a transient PUT failure is retried until it succeeds`() = runBlocking {
        val delegate = InMemoryStoreDelegate()
        val fake = FakeRemoteContentClient(failuresBeforeSuccess = 2)
        // Short retry interval so the two failed attempts are re-driven quickly.
        val uploader = RemoteContentUploaderImpl(fake, delegate, retryIntervalMillis = 50)
        uploader.start()

        val upload = uploader.enqueue(byteArrayOf(4, 2), "image/png")

        awaitUntil { fake.uploaded.containsKey("upload/0") }
        assertContentEquals(byteArrayOf(4, 2), fake.uploaded["upload/0"])
        awaitUntil { PendingUploadStore(delegate).getAllPending().isEmpty() }
        awaitUntil { uploader.watchUpload(upload.contentId).first()?.status == UploadStatus.Completed }

        uploader.stop()
    }

    @Test
    fun `a persistently failing upload stays durably queued and observable as not completed`() = runBlocking {
        val delegate = InMemoryStoreDelegate()
        val fake = FakeRemoteContentClient(failuresBeforeSuccess = Int.MAX_VALUE)
        val uploader = RemoteContentUploaderImpl(fake, delegate, retryIntervalMillis = 50)
        uploader.start()

        val upload = uploader.enqueue(byteArrayOf(5, 5, 5), "image/png")

        // Let the loop attempt (and fail) a few times, then confirm the entry survives — the durable
        // queue is what lets a real restart resume the transfer.
        delay(300)
        assertThat(fake.uploaded.keys.toList()).isEmpty()
        val pending = PendingUploadStore(delegate).getAllPending()
        assertThat(pending).hasSize(1)
        assertContentEquals(byteArrayOf(5, 5, 5), pending.single().bytes)
        // Still tracked, and never completed while it keeps failing (queued or mid-attempt).
        val observed = uploader.watchUpload(upload.contentId).first()
        assertThat(observed).isNotNull()
        assertThat(observed!!.status == UploadStatus.Completed).isEqualTo(false)

        uploader.stop()
    }
}
