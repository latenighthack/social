package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktstore.*
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.remotecontent.v1.PendingUpload
import com.latenighthack.social.remotecontent.v1.fromByteArray
import com.latenighthack.social.remotecontent.v1.toByteArray

/**
 * Device-local durable queue of uploads whose bytes have not yet been PUT. Keyed by content id, so
 * re-enqueuing the same content overwrites rather than duplicates. Survives restarts, letting the
 * uploader resume in-flight transfers.
 */
internal class PendingUploadStore(private val handle: Database) : Store<PendingUpload>(handle, PendingUploadStoreDefinitionV1) {
    private val contentIdKey = PendingUploadStoreDefinitionV1.contentIdKey

    fun pages() = com.latenighthack.social.runtime.storePages(handle, PendingUploadStoreDefinitionV1, contentIdKey, pageSize = 4)

    suspend fun getPending(contentId: ContentId): PendingUpload? = get(contentIdKey.eq(contentId.toByteArray()))

    suspend fun getAllPending(): List<PendingUpload> = getAll()

    suspend fun savePending(pending: PendingUpload) = save(pending)

    suspend fun deletePending(contentId: ContentId) = delete(contentIdKey.eq(contentId.toByteArray()))
}
