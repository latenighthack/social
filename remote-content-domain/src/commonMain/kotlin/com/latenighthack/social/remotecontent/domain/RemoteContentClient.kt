package com.latenighthack.social.remotecontent.domain

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
) : RemoteContentClient {
    private val rpc = RemoteContentServiceRpc(rpcClient)

    private val uploads = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    private val downloads = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())

    override suspend fun createContent(mimeType: String?): CreatedContent {
        val mime = mimeType.orEmpty()
        val response = rpc.createContent(CreateContentRequest { this.mimeType = mime })
        return CreatedContent(
            contentId = response.contentId ?: ContentId { rawValue = ByteArray(0) },
            uploadUrl = response.uploadUrl,
            downloadUrl = response.downloadUrl,
        )
    }

    override suspend fun upload(uploadUrl: String, bytes: ByteArray) {
        httpClient.put(uploadUrl) {
            setBody(bytes)
            onUpload { sent, total ->
                uploads.update { it + (uploadUrl to TransferProgress(sent, total ?: bytes.size.toLong())) }
            }
        }
    }

    override suspend fun download(downloadUrl: String): DownloadedContent {
        val response: HttpResponse = httpClient.get(downloadUrl) {
            onDownload { received, total ->
                downloads.update { it + (downloadUrl to TransferProgress(received, total ?: 0L)) }
            }
        }
        return DownloadedContent(
            bytes = response.body(),
            mimeType = response.contentType()?.toString(),
        )
    }

    override suspend fun upload(bytes: ByteArray, mimeType: String?): CreatedContent {
        val created = createContent(mimeType)
        upload(created.uploadUrl, bytes)
        return created
    }

    override fun watchUpload(uploadUrl: String): Flow<TransferProgress?> =
        uploads.map { it[uploadUrl] }.distinctUntilChanged()

    override fun watchDownload(downloadUrl: String): Flow<TransferProgress?> =
        downloads.map { it[downloadUrl] }.distinctUntilChanged()
}
