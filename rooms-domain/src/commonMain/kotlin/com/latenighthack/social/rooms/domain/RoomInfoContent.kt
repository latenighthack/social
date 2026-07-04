package com.latenighthack.social.rooms.domain

import com.latenighthack.social.rooms.v1.RoomInfo
import com.latenighthack.social.rooms.v1.fromByteArray

/** The room's current name, or null if none has been disclosed. */
fun RoomInfo.name(): String? =
    disclosures.firstNotNullOfOrNull { signed ->
        val payload = RoomInfo.DisclosurePayload.fromByteArray(signed.content)
        (payload.content as? RoomInfo.DisclosurePayload.OneOfContent.name)?.value?.value
    }

/** The URL the room's avatar photo is served from, or null if none has been disclosed. */
fun RoomInfo.avatar(): String? =
    disclosures.firstNotNullOfOrNull { signed ->
        val payload = RoomInfo.DisclosurePayload.fromByteArray(signed.content)
        (payload.content as? RoomInfo.DisclosurePayload.OneOfContent.avatar)?.value?.downloadUrl
    }
