package com.latenighthack.social.remotecontent.service

/** Raw content bytes plus the MIME type recorded when the content id was created. */
class StoredContent(val bytes: ByteArray, val mimeType: String?)

/**
 * The pluggable backing store for remote content. An id and its MIME type are
 * reserved by [create] when the gRPC CreateContent call mints them; the raw bytes
 * arrive later via [put] (the HTTP upload). [get] serves the bytes back on
 * download and returns null until they have actually been uploaded.
 */
interface ContentStore {
    suspend fun create(id: ByteArray, mimeType: String?)

    suspend fun put(id: ByteArray, bytes: ByteArray)

    suspend fun get(id: ByteArray): StoredContent?
}
