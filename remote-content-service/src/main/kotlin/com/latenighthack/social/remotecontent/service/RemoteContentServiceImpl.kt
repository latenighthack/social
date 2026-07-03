package com.latenighthack.social.remotecontent.service

import com.latenighthack.ktbuf.net.GrpcRequestContext
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.remotecontent.v1.CreateContentRequest
import com.latenighthack.social.remotecontent.v1.CreateContentResponse
import com.latenighthack.social.remotecontent.v1.RemoteContentServer
import java.security.SecureRandom

/**
 * The RemoteContent gRPC service. CreateContent mints a random content id, records
 * its (optional) MIME type in the [ContentStore], and returns the upload +
 * download URLs. The bytes themselves move over plain HTTP (see [remoteContent]).
 */
class RemoteContentServiceImpl(
    private val contentStore: ContentStore,
    private val urls: ContentUrls,
) : RemoteContentServer {
    private val random = SecureRandom()

    override suspend fun createContent(
        context: GrpcRequestContext,
        request: CreateContentRequest,
    ): CreateContentResponse {
        val id = ByteArray(CONTENT_ID_BYTES).also(random::nextBytes)
        contentStore.create(id, request.mimeType.takeIf { it.isNotBlank() })
        val url = urls.forContent(id)
        return CreateContentResponse {
            contentId = ContentId { rawValue = id }
            uploadUrl = url
            downloadUrl = url
        }
    }

    private companion object {
        const val CONTENT_ID_BYTES = 16
    }
}
