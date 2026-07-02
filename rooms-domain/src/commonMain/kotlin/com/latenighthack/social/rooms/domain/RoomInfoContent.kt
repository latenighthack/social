package com.latenighthack.social.rooms.domain

import com.latenighthack.social.rooms.v1.RoomInfo

/** The room's current name, or null if none has been disclosed. */
fun RoomInfo.name(): String? =
    disclosures.firstNotNullOfOrNull {
        (it.payload?.content as? RoomInfo.Disclosure.Payload.OneOfContent.name)?.value?.value
    }
