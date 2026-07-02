package com.latenighthack.social.profiles.domain

import com.latenighthack.social.profiles.v1.Profile

/** The profile's current display name, or null if none has been disclosed. */
fun Profile.displayName(): String? =
    disclosures.firstNotNullOfOrNull {
        (it.payload?.content as? Profile.Disclosure.Payload.OneOfContent.displayName)?.value?.value
    }
