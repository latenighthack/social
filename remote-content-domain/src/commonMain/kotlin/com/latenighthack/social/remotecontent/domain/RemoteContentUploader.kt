package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.remotecontent.v1.PendingUpload
import com.latenighthack.social.runtime.DomainLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

/**
 * Durable, set-and-forget uploads. [upload] mints the content id + URL synchronously (so the caller
 * gets a usable download URL immediately), durably enqueues the bytes, and returns — the actual HTTP
 * PUT happens in the background and is retried until it lands, surviving restarts. The download URL
 * is valid from the moment it is returned, though it 404s until the bytes have been uploaded, so
 * consumers must treat it as eventually consistent.
 */
interface RemoteContentUploader {
    /** Mints the URL, durably enqueues the bytes, and returns the download URL immediately. */
    suspend fun upload(bytes: ByteArray, mimeType: String?): String
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

    // Nudges the drain loop to attempt immediately when a new upload is enqueued, instead of waiting
    // out the retry interval. Conflated: coalesced nudges are fine since the loop drains everything.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Guards one-time store init, which both upload() and the drain loop can trigger concurrently.
    private val initMutex = Mutex()

    private var job: Job? = null
    private var storesInitialized = false

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

    override suspend fun upload(bytes: ByteArray, mimeType: String?): String {
        // Mint the id + URLs up front; this is the only step that needs the server to be reachable,
        // and it hands back the download URL before the bytes are transferred.
        val created = client.createContent(mimeType)
        ensureStores()
        store.savePending(PendingUpload {
            contentId = created.contentId
            uploadUrl = created.uploadUrl
            this.bytes = bytes
            createdAtMillis = Clock.System.now().toEpochMilliseconds()
        })
        wake.trySend(Unit)
        return created.downloadUrl
    }

    private suspend fun run() {
        ensureStores()
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
            try {
                client.upload(pending.uploadUrl, pending.bytes)
                store.deletePending(contentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the entry for the next pass; a transient network/server error must not drop it.
            }
        }
    }

    private suspend fun ensureStores() {
        if (storesInitialized) return
        initMutex.withLock {
            if (storesInitialized) return
            store.prepare()
            delegate.createStores()
            storesInitialized = true
        }
    }

    private companion object {
        const val DEFAULT_RETRY_INTERVAL_MILLIS = 15_000L
    }
}
