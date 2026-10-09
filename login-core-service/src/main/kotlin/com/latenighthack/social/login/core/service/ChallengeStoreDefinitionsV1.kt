package com.latenighthack.social.login.core.service
import com.latenighthack.ktstore.*
import com.latenighthack.social.login.v1.ChallengeRecord
import com.latenighthack.social.login.v1.fromByteArray
import com.latenighthack.social.login.v1.toByteArray

object ChallengeStoreDefinitionV1 : StoreDefinition<ChallengeRecord>(
    StoreName("login_challenges"), "ChallengeRecord-protobuf-v1", ChallengeRecord.Companion::fromByteArray, ChallengeRecord::toByteArray,
) {
    val lookupKey = bytesIndex(IndexName("lookupKey"), ChallengeRecord::lookupKey, "raw-bytes-v1").also { primaryKey(it) }
}
