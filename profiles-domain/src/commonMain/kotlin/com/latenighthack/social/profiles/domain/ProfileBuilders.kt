package com.latenighthack.social.profiles.domain

import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileBuilder
import com.latenighthack.social.profiles.v1.Profile_DisclosurePayloadBuilder
import com.latenighthack.social.profiles.v1.fromByteArray
import com.latenighthack.social.profiles.v1.toByteArray

/**
 * Sets a disclosure by content type: builds the payload's oneof content via [builder], drops any
 * existing disclosure carrying the same content variant, and appends the new payload as an unsigned
 * [SignedContent] (content = the encoded payload). Signing is left to [MyProfilesManager.updateProfile],
 * which re-signs every disclosure it writes.
 */
fun ProfileBuilder.replaceDisclosure(builder: Profile_DisclosurePayloadBuilder.OneOfContentBuilder.() -> Unit) {
    val payload = Profile_DisclosurePayloadBuilder().apply { content.apply(builder) }.build()
    val variant = payload.content?.let { it::class } ?: return

    disclosures = disclosures.filterNot { signed ->
        Profile.DisclosurePayload.fromByteArray(signed.content).content?.let { c -> c::class } == variant
    }
    disclosures {
        addSignedContent { content = payload.toByteArray() }
    }
}
