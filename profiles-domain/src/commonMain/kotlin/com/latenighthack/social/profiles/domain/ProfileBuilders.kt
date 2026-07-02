package com.latenighthack.social.profiles.domain

import com.latenighthack.social.profiles.v1.Profile_Disclosure_PayloadBuilder
import com.latenighthack.social.profiles.v1.ProfileBuilder

/**
 * Sets a disclosure by content type: builds the payload's oneof content via [builder], drops any
 * existing disclosure carrying the same content variant, and appends the new payload. Signing is
 * left to [MyProfilesManager.updateProfile], which re-signs every disclosure it writes.
 */
fun ProfileBuilder.replaceDisclosure(builder: Profile_Disclosure_PayloadBuilder.OneOfContentBuilder.() -> Unit) {
    val payload = Profile_Disclosure_PayloadBuilder().apply { content.apply(builder) }.build()
    val variant = payload.content?.let { it::class } ?: return

    disclosures = disclosures.filterNot { it.payload?.content?.let { c -> c::class } == variant }
    disclosures {
        addDisclosure { this.payload = payload }
    }
}
