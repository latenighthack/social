package com.latenighthack.social.profiles.domain

import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.social.common.domain.sign as signContent
import com.latenighthack.social.common.domain.verify as verifyContent
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.profiles.v1.copy
import com.latenighthack.social.profiles.v1.toByteArray

/**
 * Signs and verifies the disclosures that make up a [Profile]. Each disclosure is a standard
 * [SignedContent] whose `content` is an encoded [Profile.DisclosurePayload] signed by the profile
 * key. The [profileId] is stamped into the payload before signing, binding the disclosure to its
 * profile so a signature cannot be replayed under a different profile.
 */
internal object Disclosures {
    /**
     * Int64 domain-separation tag folded into the signing transcript for profile disclosures. These
     * tags are a global allocation (like keyspaces): each SignedContent use site picks a distinct
     * value so a signature made for one purpose can't validate for another. 1 = profile disclosure.
     */
    const val LABEL = 1L

    suspend fun sign(
        profileKey: Secp256r1KeyPair,
        profileId: ProfileId,
        payload: Profile.DisclosurePayload,
    ): SignedContent {
        val bound = payload.copy(profileId = profileId)
        return signContent(profileKey, LABEL, bound.toByteArray())
    }

    /** True if [disclosure] carries a valid signature by [profileKey] (the profile's own key). */
    suspend fun verify(disclosure: SignedContent, profileKey: Secp256r1PublicKey): Boolean =
        verifyContent(disclosure, LABEL, profileKey)
}
