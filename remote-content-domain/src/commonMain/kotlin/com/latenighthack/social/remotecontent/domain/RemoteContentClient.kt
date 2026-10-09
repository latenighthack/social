package com.latenighthack.social.remotecontent.domain

import com.latenighthack.social.observability.*

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.remotecontent.v1.CreateContentRequest
import com.latenighthack.social.remotecontent.v1.RemoteContentServiceRpc
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource
import com.latenighthack.social.runtime.OperationsObserver
import com.latenighthack.social.runtime.record

/** A created content handle: its id and the URLs its bytes are uploaded to / served from. */
class CreatedContent(
    val contentId: ContentId,
    val uploadUrl: String,
    val downloadUrl: String,
)

/** Downloaded content: the raw bytes and the MIME type the server served them with. */
class DownloadedContent(
    val bytes: ByteArray,
    val mimeType: String?,
)

/** Progress of a transfer (upload or download): the bytes moved so far out of the total. */
data class TransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
)

/**
 * Client-side helper for the remote-content service. It makes the CreateContent gRPC call and
 * performs the raw HTTP upload/download, so a caller gets the whole flow without wiring the HTTP
 * itself. Transfer progress is not reported through callbacks: it is tracked per URL and observed via
 * [watchUpload] / [watchDownload]. Uploads and downloads are tracked separately — a URL can be
 * uploaded and later downloaded, and each is its own observable transfer.
 */
interface RemoteContentClient {
    suspend fun createContent(mimeType: String?): CreatedContent

    suspend fun upload(uploadUrl: String, bytes: ByteArray)

    suspend fun download(downloadUrl: String): DownloadedContent

    /** Convenience: create then upload in one call; returns the id and download URL. */
    suspend fun upload(bytes: ByteArray, mimeType: String?): CreatedContent

    /**
     * Live progress of the upload to [uploadUrl]: emits as bytes move and retains the latest value.
     * Null until an upload to [uploadUrl] has started.
     */
    fun watchUpload(uploadUrl: String): Flow<TransferProgress?>

    /**
     * Live progress of the download from [downloadUrl]: emits as bytes move and retains the latest
     * value. Null until a download from [downloadUrl] has started.
     */
    fun watchDownload(downloadUrl: String): Flow<TransferProgress?>
}

class RemoteContentClientImpl(
    rpcClient: RpcClient,
    private val httpClient: HttpClient,
    private val observer: OperationsObserver = OperationsObserver.NONE,
) : RemoteContentClient, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry

    private val rpc = RemoteContentServiceRpc(rpcClient)

    private val uploads = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    private val downloads = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())

    override suspend fun createContent(mimeType: String?): CreatedContent = socialTelemetry.measure("remote_content", "createContent") {
        val mime = mimeType.orEmpty()
        val response = rpc.createContent(CreateContentRequest { this.mimeType = mime })
        return@measure CreatedContent(
            contentId = response.contentId ?: ContentId { rawValue = ByteArray(0) },
            uploadUrl = response.uploadUrl,
            downloadUrl = response.downloadUrl,
        )
    }

    override suspend fun upload(uploadUrl: String, bytes: ByteArray): Unit = socialTelemetry.measure("remote_content", "upload") {
        val started = TimeSource.Monotonic.markNow()
        var outcome = "success"
        try { withTimeout(30_000) {
        val response = httpClient.put(uploadUrl) {
            setBody(bytes)
            onUpload { sent, total ->
                uploads.update { it + (uploadUrl to TransferProgress(sent, total ?: bytes.size.toLong())) }
            }
        }
        result(socialHttpResult(response.status.value))
        socialTelemetry.event("remote_content", "upload", result = socialHttpResult(response.status.value), kind = "bytes", value = bytes.size.toDouble())
        check(response.status.value in 200..299) { "Content upload rejected" }
        } } catch (cancelled: CancellationException) { outcome = "cancelled"; throw cancelled }
        catch (failure: Exception) { outcome = "failure"; throw failure }
        finally { observer.record("content_upload", outcome, started.elapsedNow().inWholeNanoseconds / 1e9, bytes = bytes.size.toLong()) }
    }

    override suspend fun download(downloadUrl: String): DownloadedContent = socialTelemetry.measure("remote_content", "download") {
        val started = TimeSource.Monotonic.markNow()
        var firstByte = false
        var size = 0L
        var outcome = "success"
        try { withTimeout(30_000) {
        val response: HttpResponse = httpClient.get(downloadUrl) {
            onDownload { received, total ->
                if (received > 0 && !firstByte) {
                    firstByte = true
                    observer.record("content_first_byte", seconds = started.elapsedNow().inWholeNanoseconds / 1e9, bytes = total ?: 0)
                }
                downloads.update { it + (downloadUrl to TransferProgress(received, total ?: 0L)) }
            }
        }
        result(socialHttpResult(response.status.value))
        check(response.status.value in 200..299) { "Content download rejected" }
        val bytes = response.body<ByteArray>()
        size = bytes.size.toLong()
        socialTelemetry.event("remote_content", "download", result = socialHttpResult(response.status.value), kind = "bytes", value = size.toDouble())
        DownloadedContent(
            bytes = bytes,
            mimeType = response.contentType()?.toString(),
        )
        } } catch (cancelled: CancellationException) { outcome = "cancelled"; throw cancelled }
        catch (failure: Exception) { outcome = "failure"; throw failure }
        finally { observer.record("content_download", outcome, started.elapsedNow().inWholeNanoseconds / 1e9, bytes = size) }
    }

    override suspend fun upload(bytes: ByteArray, mimeType: String?): CreatedContent = socialTelemetry.measure("remote_content", "upload") {
        val created = createContent(mimeType)
        upload(created.uploadUrl, bytes)
        return@measure created
    }

    override fun watchUpload(uploadUrl: String): Flow<TransferProgress?> =
        uploads.map { it[uploadUrl] }.distinctUntilChanged()

    override fun watchDownload(downloadUrl: String): Flow<TransferProgress?> =
        downloads.map { it[downloadUrl] }.distinctUntilChanged()
}
