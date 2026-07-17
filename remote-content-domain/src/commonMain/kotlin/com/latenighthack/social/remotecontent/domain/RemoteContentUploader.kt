// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.remotecontent.v1.PendingUpload
import com.latenighthack.social.runtime.DomainLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Where an upload is in its lifecycle, from enqueued through the background transfer to done. This is
 * the durable-queue state; byte-level transfer progress is observed separately via
 * [RemoteContentClient.watchUpload].
 */
sealed interface UploadStatus {
    /** Enqueued and waiting — either not yet attempted, or between retries after a failed attempt. */
    data object Queued : UploadStatus

    /** The HTTP PUT is in flight. */
    data object Uploading : UploadStatus

    /** The bytes have been fully uploaded and are being served from the download URL. */
    data object Completed : UploadStatus
}

/** An observable upload: its content id, the URL it is served from, and its current [status]. */
data class Upload(
    val contentId: ContentId,
    val downloadUrl: String,
    val status: UploadStatus,
)

/**
 * Durable, set-and-forget uploads. [enqueue] mints the content id + URL synchronously (so the caller
 * gets a usable download URL immediately), durably queues the bytes, and returns — it does NOT wait
 * for the transfer, which happens in the background and is retried until it lands, surviving restarts.
 * The returned download URL is valid from the moment it is returned, though it 404s until the bytes
 * have been uploaded, so consumers must treat it as eventually consistent. [watchUploads] /
 * [watchUpload] expose each upload's progress and status so a UI can reflect the background transfer.
 */
interface RemoteContentUploader {
    /**
     * Mints the URL, durably queues the bytes, and returns an [Upload] handle (status [UploadStatus.Queued])
     * without waiting for the transfer. Use its `downloadUrl` immediately; observe its progress with
     * [watchUpload].
     */
    suspend fun enqueue(bytes: ByteArray, mimeType: String?): Upload

    /** The uploads known this session (in-flight, retrying, or recently completed). */
    fun watchUploads(): Flow<List<Upload>>

    /** A single upload's progress and status, or null if it is unknown to this session. */
    fun watchUpload(contentId: ContentId): Flow<Upload?>
}

/**
 * Backs [RemoteContentUploader] with a [PendingUploadStore] and a resumable background drain loop.
 * The real lifecycle is the no-arg [start]/[stop] — this uploader drives off the
 * [RemoteContentClient] transport, not lockers. It also satisfies [DomainLifecycle] (whose [start]
 * ignores its [LockersClient] and just delegates) so the app boots and stops it through the same
 * `Set<DomainLifecycle>` as every other manager. Resumable: [stop] cancels the loop and leaves the
 * queue intact for a later [start] to resume.
 */
class RemoteContentUploaderImpl(
    private val client: RemoteContentClient,
    private val delegate: StoreDelegate,
    private val retryIntervalMillis: Long = DEFAULT_RETRY_INTERVAL_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RemoteContentUploader, DomainLifecycle {

    private val store = PendingUploadStore(delegate)

    // Observable status per upload, keyed by content id bytes. Completed entries are retained (bytes
    // already dropped from the durable store, so this is metadata only) so observers see completion.
    private val uploads = MutableStateFlow<Map<List<Byte>, Upload>>(emptyMap())

    // Nudges the drain loop to attempt immediately when a new upload is enqueued, instead of waiting
    // out the retry interval. Conflated: coalesced nudges are fine since the loop drains everything.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var job: Job? = null

    override suspend fun prepare() {
        store.prepare()
    }

    /** Launches the background drain loop. Idempotent; resumes a queue left by a prior [stop]. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** [DomainLifecycle] entry point; the [lockers] client is unused (see the class doc). */
    override fun start(lockers: LockersClient) = start()

    override fun stop() {
        job?.cancel()
        job = null
    }

    override suspend fun enqueue(bytes: ByteArray, mimeType: String?): Upload {
        // Mint the id + URLs up front; this is the only step that needs the server to be reachable,
        // and it hands back the download URL before the bytes are transferred.
        val created = client.createContent(mimeType)
        store.savePending(PendingUpload {
            contentId = created.contentId
            uploadUrl = created.uploadUrl
            downloadUrl = created.downloadUrl
            this.bytes = bytes
            createdAtMillis = Clock.System.now().toEpochMilliseconds()
        })
        val upload = Upload(created.contentId, created.downloadUrl, UploadStatus.Queued)
        uploads.update { it + (created.contentId.rawValue.toList() to upload) }
        wake.trySend(Unit)
        return upload
    }

    override fun watchUploads(): Flow<List<Upload>> =
        uploads.map { it.values.toList() }.distinctUntilChanged()

    override fun watchUpload(contentId: ContentId): Flow<Upload?> =
        uploads.map { it[contentId.rawValue.toList()] }.distinctUntilChanged()

    private suspend fun run() {
        // Re-surface uploads that survived a restart as queued, so observers see them resume. Anything
        // already tracked in memory (freshly enqueued) wins over the persisted snapshot.
        val resumed = store.getAllPending().mapNotNull { pending ->
            val contentId = pending.contentId ?: return@mapNotNull null
            contentId.rawValue.toList() to Upload(contentId, pending.downloadUrl, UploadStatus.Queued)
        }.toMap()
        uploads.update { resumed + it }
        while (true) {
            drainOnce()
            // Wait for a freshly enqueued upload, or fall through after the interval to retry
            // whatever failed last pass.
            withTimeoutOrNull(retryIntervalMillis) { wake.receive() }
        }
    }

    private suspend fun drainOnce() {
        for (pending in store.getAllPending().sortedBy { it.createdAtMillis }) {
            val contentId = pending.contentId ?: continue
            val key = contentId.rawValue.toList()
            setStatus(key, UploadStatus.Uploading)
            try {
                // Byte-level progress is tracked by the transport and observed via watchUpload(uploadUrl).
                client.upload(pending.uploadUrl, pending.bytes)
                store.deletePending(contentId)
                setStatus(key, UploadStatus.Completed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the entry for the next pass; a transient network/server error must not drop it.
                setStatus(key, UploadStatus.Queued)
            }
        }
    }

    private fun setStatus(key: List<Byte>, status: UploadStatus) {
        uploads.update { map -> map[key]?.let { map + (key to it.copy(status = status)) } ?: map }
    }

    private companion object {
        const val DEFAULT_RETRY_INTERVAL_MILLIS = 15_000L
    }
}
