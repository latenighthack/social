package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.remotecontent.v1.PendingUpload
import com.latenighthack.social.remotecontent.v1.fromByteArray
import com.latenighthack.social.remotecontent.v1.toByteArray

/**
 * Device-local durable queue of uploads whose bytes have not yet been PUT. Keyed by content id, so
 * re-enqueuing the same content overwrites rather than duplicates. Survives restarts, letting the
 * uploader resume in-flight transfers.
 */
internal class PendingUploadStore(delegate: StoreDelegate) : Store<PendingUpload>(
    delegate,
    "pending_uploads",
    PendingUpload::toByteArray,
    PendingUpload.Companion::fromByteArray,
) {
    private val contentIdKey = serializedIndex(PendingUpload::contentId, ContentId::toByteArray)
        .also { primaryKey(it) }

    suspend fun getAllPending(): List<PendingUpload> = getAll()

    suspend fun savePending(pending: PendingUpload) = save(pending)

    suspend fun deletePending(contentId: ContentId) = delete(contentIdKey.eq(contentId.toByteArray()))
}
