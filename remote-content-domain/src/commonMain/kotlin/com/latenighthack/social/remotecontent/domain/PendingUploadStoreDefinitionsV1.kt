package com.latenighthack.social.remotecontent.domain
import com.latenighthack.ktstore.*
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.remotecontent.v1.PendingUpload
import com.latenighthack.social.remotecontent.v1.fromByteArray
import com.latenighthack.social.remotecontent.v1.toByteArray

private val PendingUpload.contentIdKeyStorage: ByteArray get() = requireNotNull(contentId).toByteArray()

object PendingUploadStoreDefinitionV1 : StoreDefinition<PendingUpload>(
    StoreName("pending_uploads"), "PendingUpload-protobuf-v1", PendingUpload.Companion::fromByteArray, PendingUpload::toByteArray,
) {
    val contentIdKey = bytesIndex(IndexName("contentIdtoByteArray"), PendingUpload::contentIdKeyStorage, "toByteArray-v1").also { primaryKey(it) }
}
