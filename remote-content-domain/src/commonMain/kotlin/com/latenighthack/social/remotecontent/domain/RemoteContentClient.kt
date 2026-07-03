package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.remotecontent.v1.CreateContentRequest
import com.latenighthack.social.remotecontent.v1.RemoteContentServiceRpc
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.contentType

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

/**
 * Client-side helper for the remote-content service. It makes the CreateContent
 * gRPC call and performs the raw HTTP upload/download, so a caller gets the whole
 * flow without wiring the HTTP itself.
 */
interface RemoteContentClient {
    suspend fun createContent(mimeType: String?): CreatedContent

    suspend fun upload(uploadUrl: String, bytes: ByteArray)

    suspend fun download(downloadUrl: String): DownloadedContent

    /** Convenience: create then upload in one call; returns the id and download URL. */
    suspend fun upload(bytes: ByteArray, mimeType: String?): CreatedContent
}

class RemoteContentClientImpl(
    rpcClient: RpcClient,
    private val httpClient: HttpClient,
) : RemoteContentClient {
    private val rpc = RemoteContentServiceRpc(rpcClient)

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
        httpClient.put(uploadUrl) { setBody(bytes) }
    }

    override suspend fun download(downloadUrl: String): DownloadedContent {
        val response: HttpResponse = httpClient.get(downloadUrl)
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
}
