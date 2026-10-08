package com.latenighthack.social.login.core.service
import com.latenighthack.ktstore.*
import com.latenighthack.social.login.v1.CredentialRecord
import com.latenighthack.social.login.v1.fromByteArray
import com.latenighthack.social.login.v1.toByteArray

object CredentialStoreDefinitionV1 : StoreDefinition<CredentialRecord>(
    StoreName("login_credentials"), "CredentialRecord-protobuf-v1", CredentialRecord.Companion::fromByteArray, CredentialRecord::toByteArray,
) {
    val lookupKey = bytesIndex(IndexName("lookupKey"), CredentialRecord::lookupKey, "raw-bytes-v1").also { primaryKey(it) }
}
