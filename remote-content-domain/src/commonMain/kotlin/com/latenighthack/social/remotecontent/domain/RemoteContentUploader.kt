// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.remotecontent.domain

import kotlinx.coroutines.flow.asStateFlow

import com.latenighthack.ktstore.Database
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.remotecontent.v1.PendingUpload
import com.latenighthack.social.runtime.*
import com.latenighthack.social.remotecontent.v1.copy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import com.latenighthack.social.runtime.TaskHealth
import com.latenighthack.social.runtime.recoverTask
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Where an upload is in its lifecycle, from enqueued through the background transfer to done. This is
 * the durable-queue state; byte-level transfer progress is observed separately via
 * [RemoteContentClient.watchUpload].
 */
/** Stable failure categories; never expose capability URLs or raw transport exception messages. */
enum class UploadFailure { AUTHORIZATION_REJECTED, INVALID_CONTENT, PERMANENT_REJECTION, RETRIES_EXHAUSTED }

sealed interface UploadStatus {
    /** Enqueued and waiting — either not yet attempted, or between retries after a failed attempt. */
    data object Queued : UploadStatus

    /** The HTTP PUT is in flight. */
    data object Uploading : UploadStatus

    /** The bytes have been fully uploaded and are being served from the download URL. */
    data object Completed : UploadStatus

    /** Bytes are retained durably; background retry is paused until the host chooses an action. */
    data class Failed(val reason: UploadFailure) : UploadStatus
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
 * for the transfer, which happens in the background and is retried up to eight attempts, surviving restarts. Permanent or exhausted failures retain their bytes and are exposed as [UploadStatus.Failed].
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

    /** Retry a failed upload using the same authorization. Expired authorization requires reattachment. */
    suspend fun retry(contentId: ContentId): Unit = throw UnsupportedOperationException("retry is unavailable")

    /** Delete the retained bytes of a failed upload. */
    suspend fun discardFailed(contentId: ContentId): Unit = throw UnsupportedOperationException("discard is unavailable")

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
    private val database: Database,
    private val retryIntervalMillis: Long = DEFAULT_RETRY_INTERVAL_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val session: AccountSession? = null,
) : RemoteContentUploader, DomainLifecycle {

    private var loadedOwner: String? = null
    private val store = PendingUploadStore(database)
    private val stateMutex = Mutex()

    // Observable status per upload, keyed by content id bytes. Completed entries are retained (bytes
    // already dropped from the durable store, so this is metadata only) so observers see completion.
    private val uploads = MutableStateFlow<Map<List<Byte>, Upload>>(emptyMap())

    // Nudges the drain loop to attempt immediately when a new upload is enqueued, instead of waiting
    // out the retry interval. Conflated: coalesced nudges are fine since the loop drains everything.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val mutableTaskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
    override val taskHealth = mutableTaskHealth.asStateFlow()
    private val runner = com.latenighthack.social.runtime.ManagerRunner(scope)

    override suspend fun prepare() {
        store.prepare()
    }

    /** Launches the background drain loop. Idempotent; resumes a queue left by a prior [stop]. */
    fun start() {
        runner.start { recoverTask(mutableTaskHealth) { run() } }
    }

    /** [DomainLifecycle] entry point; the [lockers] client is unused (see the class doc). */
    override fun start(lockers: LockersClient) = start()

    override fun stop() {
        runner.stop()
    }

    override suspend fun stopAndJoin() {
        runner.stopAndJoin()
    }

    override suspend fun enqueue(bytes: ByteArray, mimeType: String?): Upload = withSession { enqueueOwned(bytes, mimeType) }

    private suspend fun <T> withSession(block: suspend () -> T): T = if (session == null) block() else session.withAccount(block)

    private suspend fun enqueueOwned(bytes: ByteArray, mimeType: String?): Upload {
        // Mint the id + URLs up front; this is the only step that needs the server to be reachable,
        // and it hands back the download URL before the bytes are transferred.
        require(bytes.size <= 16 * 1024 * 1024) { "upload exceeds 16 MiB" }
        val owner = session.currentOwner()
        val created = client.createContent(mimeType)
        session?.requireOperationOwner()
        val upload = Upload(created.contentId, created.downloadUrl, UploadStatus.Queued)
        stateMutex.withLock {
        check(session.currentOwner() == owner) { "account changed during upload creation" }
        if (loadedOwner != owner) { uploads.value = emptyMap(); loadedOwner = owner }
        store.savePending(PendingUpload {
            contentId = created.contentId
            ownerAccountId = owner
            uploadUrl = created.uploadUrl
            downloadUrl = created.downloadUrl
            this.bytes = bytes
            createdAtMillis = Clock.System.now().toEpochMilliseconds()
        })
        uploads.update { (it + (created.contentId.rawValue.toList() to upload)).entries.toList().takeLast(1024).associate { it.toPair() } }
        }
        wake.trySend(Unit)
        return upload
    }

    override suspend fun retry(contentId: ContentId): Unit = withSession {
        stateMutex.withLock {
            val pending = store.getPending(contentId) ?: return@withLock
            check(session.owns(pending.ownerAccountId) && pending.failureReason.isNotEmpty()) { "upload is not an owned failure" }
            store.savePending(pending.copy(attempts = 0, failureReason = ""))
            uploads.update { (it + (contentId.rawValue.toList() to Upload(contentId, pending.downloadUrl, UploadStatus.Queued))).entries.toList().takeLast(1024).associate { entry -> entry.toPair() } }
        }
        wake.trySend(Unit)
        Unit
    }

    override suspend fun discardFailed(contentId: ContentId): Unit = withSession {
        stateMutex.withLock {
            val pending = store.getPending(contentId) ?: return@withLock
            check(session.owns(pending.ownerAccountId) && pending.failureReason.isNotEmpty()) { "upload is not an owned failure" }
            store.deletePending(contentId)
            uploads.update { it - setOf(contentId.rawValue.toList()) }
        }
    }

    override fun watchUploads(): Flow<List<Upload>> =
        combine(uploads, session.ownerChanges()) { map, owner -> if (owner != null && owner == loadedOwner) map.values.toList() else emptyList() }.distinctUntilChanged()

    override fun watchUpload(contentId: ContentId): Flow<Upload?> =
        combine(uploads, session.ownerChanges()) { map, owner -> if (owner != null && owner == loadedOwner) map[contentId.rawValue.toList()] else null }.distinctUntilChanged()

    private suspend fun run() {
        session.ownerChanges().collectLatest { owner ->
            stateMutex.withLock {
                if (loadedOwner != owner) uploads.value = emptyMap()
                loadedOwner = owner
            }
            if (owner != null && session != null) store.pages().collect { page ->
                stateMutex.withLock {
                    check(session.currentOwner() == owner)
                    for (row in page) {
                        if (row.ownerAccountId.isNotEmpty()) continue
                        if (session.owns("")) store.savePending(row.copy(ownerAccountId = owner))
                        else row.contentId?.let { store.deletePending(it) }
                    }
                }
            }
            if (owner != null) runForOwner()
        }
    }

    private suspend fun runForOwner() {
        // Re-surface uploads that survived a restart as queued, so observers see them resume. Anything
        // already tracked in memory (freshly enqueued) wins over the persisted snapshot.
        store.pages().collect { page ->
            val resumed = page.filter { session.owns(it.ownerAccountId) }.mapNotNull { pending ->
                val id = pending.contentId ?: return@mapNotNull null
                id.rawValue.toList() to Upload(id, pending.downloadUrl, failureStatus(pending) ?: UploadStatus.Queued)
            }.toMap()
            stateMutex.withLock { uploads.update { (resumed + it).entries.toList().takeLast(1024).associate { it.toPair() } } }
        }
        while (true) {
            drainOnce()
            // Wait for a freshly enqueued upload, or fall through after the interval to retry
            // whatever failed last pass.
            withTimeoutOrNull(retryIntervalMillis) { wake.receive() }
        }
    }

    private suspend fun drainOnce() {
        store.pages().collect { page ->
            for (batch in page.filter { session.owns(it.ownerAccountId) && it.failureReason.isEmpty() }.chunked(4)) kotlinx.coroutines.coroutineScope {
                batch.map { pending -> launch { transfer(pending) } }.forEach { it.join() }
            }
        }
    }

    // Transport adapters may throw platform-specific failures; cancellation is handled first.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun transfer(pending: com.latenighthack.social.remotecontent.v1.PendingUpload) {
        val contentId = pending.contentId ?: return
        val key = contentId.rawValue.toList()
        setStatus(key, UploadStatus.Uploading)
        try {
            kotlinx.coroutines.withTimeout(30_000) { client.upload(pending.uploadUrl, pending.bytes) }
            store.deletePending(contentId)
            setStatus(key, UploadStatus.Completed)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            recordFailure(pending, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalArgumentException) {
            recordFailure(pending, UploadFailure.INVALID_CONTENT)
        } catch (failure: io.ktor.client.plugins.ClientRequestException) {
            val permanent = when (failure.response.status.value) {
                401, 403 -> UploadFailure.AUTHORIZATION_REJECTED
                408, 429 -> null
                else -> UploadFailure.PERMANENT_REJECTION
            }
            recordFailure(pending, permanent)
        } catch (_: Exception) {
            recordFailure(pending, null)
        }
    }

    private fun failureStatus(pending: PendingUpload): UploadStatus.Failed? = if (pending.failureReason.isEmpty()) null else
        UploadStatus.Failed(UploadFailure.entries.firstOrNull { it.name == pending.failureReason } ?: UploadFailure.RETRIES_EXHAUSTED)

    private suspend fun recordFailure(pending: PendingUpload, permanent: UploadFailure?) {
        val attempts = (pending.attempts + 1).coerceAtMost(MAX_ATTEMPTS)
        val reason = permanent ?: UploadFailure.RETRIES_EXHAUSTED.takeIf { attempts >= MAX_ATTEMPTS }
        store.savePending(pending.copy(attempts = attempts, failureReason = reason?.name ?: ""))
        pending.contentId?.let { setStatus(it.rawValue.toList(), reason?.let { UploadStatus.Failed(it) } ?: UploadStatus.Queued) }
    }

    private suspend fun setStatus(key: List<Byte>, status: UploadStatus) = stateMutex.withLock {
        uploads.update { map -> map[key]?.let { (map + (key to it.copy(status = status))).entries.toList().takeLast(1024).associate { it.toPair() } } ?: map }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8L
        const val DEFAULT_RETRY_INTERVAL_MILLIS = 15_000L
    }
}
