package com.latenighthack.social.profiles.domain

import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.fromByteArray

/** The profile's current display name, or null if none has been disclosed. */
fun Profile.displayName(): String? =
    disclosures.firstNotNullOfOrNull { signed ->
        val payload = Profile.DisclosurePayload.fromByteArray(signed.content)
        (payload.content as? Profile.DisclosurePayload.OneOfContent.displayName)?.value?.value
    }
