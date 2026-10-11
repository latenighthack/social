package com.latenighthack.social.remotecontent.service

import com.latenighthack.social.observability.*

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
) : RemoteContentServer, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry

    override suspend fun createContent(
        context: GrpcRequestContext,
        request: CreateContentRequest,
    ): CreateContentResponse = socialTelemetry.measure("remote_content", "createContent", "none") { (run observedOperation@ {
        require(request.mimeType.length <= 128 && request.mimeType.none { it.code < 32 }) { "invalid MIME type" }
        check(reservations.reserve()) { "content reservation budget exhausted; retry later" }
        val id = ByteArray(CONTENT_ID_BYTES).also(random::nextBytes)
        val token = ByteArray(32).also(random::nextBytes)
        contentStore.create(id, request.mimeType.takeIf { it.isNotBlank() }, token)
        val url = urls.forContent(id)
        return@observedOperation CreateContentResponse {
            contentId = ContentId { rawValue = id }
            uploadUrl = urls.forUpload(id, token)
            downloadUrl = url
        }

        }) }


    private val reservations = ReservationBudget()
    private val random = SecureRandom()


    private companion object {
        const val CONTENT_ID_BYTES = 16
    }
}

/** Bounded service-instance admission, in addition to the host's network and disk quotas. */
internal class ReservationBudget(private val capacity: Int = 120, private val now: () -> Long = System::currentTimeMillis) {
    private var start = now()
    private var remaining = capacity
    @Synchronized fun reserve(): Boolean {
        if (now() - start >= 60_000) { start = now(); remaining = capacity }
        if (remaining <= 0) return false
        remaining--; return true
    }
}
