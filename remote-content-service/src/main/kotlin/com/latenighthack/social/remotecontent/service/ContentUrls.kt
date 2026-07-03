package com.latenighthack.social.remotecontent.service

import java.util.Base64

/**
 * Builds the URL a content id is uploaded to and served from. Upload and download
 * share the same path; the HTTP method (PUT vs GET) distinguishes them. When a
 * [publicBaseUrl] is configured the URL is absolute; otherwise it is root-relative
 * (the caller already knows the host it reached the gRPC service on).
 */
class ContentUrls(private val publicBaseUrl: String) {
    fun forContent(id: ByteArray): String {
        val path = "$CONTENT_PATH/${encodeId(id)}"
        return if (publicBaseUrl.isBlank()) path else publicBaseUrl.trimEnd('/') + path
    }

    companion object {
        const val CONTENT_PATH = "/content"

        fun encodeId(id: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(id)

        fun decodeId(param: String): ByteArray? =
            try {
                Base64.getUrlDecoder().decode(param).takeIf { it.isNotEmpty() }
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
