package com.latenighthack.social.rooms.domain

import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.common.domain.sign as signContent
import com.latenighthack.social.common.domain.verify as verifyContent
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.rooms.v1.RoomInfo
import com.latenighthack.social.rooms.v1.copy
import com.latenighthack.social.rooms.v1.toByteArray

/**
 * Signs and verifies the disclosures that make up a [RoomInfo]. Each disclosure is a standard
 * [SignedContent] whose `content` is an encoded [RoomInfo.DisclosurePayload] signed by the shared
 * room key. Every member holds that key, so any member can produce a valid signature. The [roomId]
 * is stamped into the payload before signing, binding the disclosure to its room so a signature
 * cannot be replayed under a different room.
 */
internal object RoomInfoDisclosures {
    /**
     * Int64 domain-separation tag folded into the signing transcript for room-info disclosures. These
     * tags are a global allocation (like keyspaces): each SignedContent use site picks a distinct
     * value so a signature made for one purpose can't validate for another. 2 = room-info disclosure.
     */
    const val LABEL = 2L

    suspend fun sign(
        roomKey: Secp256r1KeyPair,
        roomId: RoomId,
        payload: RoomInfo.DisclosurePayload,
    ): SignedContent {
        val bound = payload.copy(roomId = roomId.rawValue)
        return signContent(roomKey, LABEL, bound.toByteArray())
    }

    /** True if [disclosure] carries a valid signature by [roomKey] (the shared room key). */
    suspend fun verify(disclosure: SignedContent, roomKey: Secp256r1PublicKey): Boolean =
        verifyContent(disclosure, LABEL, roomKey)
}
