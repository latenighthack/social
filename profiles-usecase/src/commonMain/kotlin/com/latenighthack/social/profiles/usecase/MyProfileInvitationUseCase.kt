package com.latenighthack.social.profiles.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The user's default profile invitation: the primary profile's public key (its [ProfileId] raw
 * value, a 33-byte compressed secp256r1 key) base64-encoded, which a peer scans to address a
 * connection to this profile. Mono-profile for now — the first profile is the user's; emits null
 * until one exists.
 */
class MyProfileInvitationUseCase(
    private val myProfiles: MyProfilesManager,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun watch(): Flow<String?> =
        myProfiles.getProfileList().map { ids ->
            ids.firstOrNull()?.let { Base64.encode(it.rawValue) }
        }
}
