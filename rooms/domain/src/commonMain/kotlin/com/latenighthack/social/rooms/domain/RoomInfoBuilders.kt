package com.latenighthack.social.rooms.domain

import com.latenighthack.social.rooms.v1.RoomInfo
import com.latenighthack.social.rooms.v1.RoomInfoBuilder
import com.latenighthack.social.rooms.v1.RoomInfo_DisclosurePayloadBuilder
import com.latenighthack.social.rooms.v1.fromByteArray
import com.latenighthack.social.rooms.v1.toByteArray

/**
 * Sets a disclosure by content type: builds the payload's oneof content via [builder], drops any
 * existing disclosure carrying the same content variant, and appends the new payload as an unsigned
 * [SignedContent] (content = the encoded payload). Signing is left to [RoomsManager.updateInfo],
 * which re-signs every disclosure it writes.
 */
fun RoomInfoBuilder.replaceDisclosure(builder: RoomInfo_DisclosurePayloadBuilder.OneOfContentBuilder.() -> Unit) {
    val payload = RoomInfo_DisclosurePayloadBuilder().apply { content.apply(builder) }.build()
    val variant = payload.content?.let { it::class } ?: return

    disclosures = disclosures.filterNot { signed ->
        RoomInfo.DisclosurePayload.fromByteArray(signed.content).content?.let { c -> c::class } == variant
    }
    disclosures {
        addSignedContent { content = payload.toByteArray() }
    }
}
