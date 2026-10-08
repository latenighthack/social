package com.latenighthack.social.profiles.domain
import com.latenighthack.ktstore.*
import com.latenighthack.social.profiles.v1.LocalProfile
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.profiles.v1.fromByteArray
import com.latenighthack.social.profiles.v1.toByteArray

private val LocalProfile.profileIdKeyStorage: ByteArray get() = requireNotNull(profileId).toByteArray()

object ProfileStoreDefinitionV1 : StoreDefinition<LocalProfile>(
    StoreName("profiles"), "LocalProfile-protobuf-v1", LocalProfile.Companion::fromByteArray, LocalProfile::toByteArray,
) {
    val profileIdKey = bytesIndex(IndexName("profileIdtoByteArray"), LocalProfile::profileIdKeyStorage, "toByteArray-v1").also { primaryKey(it) }
}
