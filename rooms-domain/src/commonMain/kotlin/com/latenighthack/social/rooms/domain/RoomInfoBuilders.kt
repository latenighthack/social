package com.latenighthack.social.rooms.domain

import com.latenighthack.social.rooms.v1.RoomInfoBuilder
import com.latenighthack.social.rooms.v1.RoomInfo_Disclosure_PayloadBuilder

/**
 * Sets a disclosure by content type: builds the payload's oneof content via [builder], drops any
 * existing disclosure carrying the same content variant, and appends the new payload. Signing is
 * left to [RoomsManager.updateInfo], which re-signs every disclosure it writes.
 */
fun RoomInfoBuilder.replaceDisclosure(builder: RoomInfo_Disclosure_PayloadBuilder.OneOfContentBuilder.() -> Unit) {
    val payload = RoomInfo_Disclosure_PayloadBuilder().apply { content.apply(builder) }.build()
    val variant = payload.content?.let { it::class } ?: return

    disclosures = disclosures.filterNot { it.payload?.content?.let { c -> c::class } == variant }
    disclosures {
        addDisclosure { this.payload = payload }
    }
}
