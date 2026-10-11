package com.latenighthack.social.messages.domain

import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.social.common.domain.verify as verifyContent
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.messages.v1.MessagePayload

/**
 * Verifies a message's authorship. A message is a standard [SignedContent] whose `content` is an
 * encoded [MessagePayload] signed by the sender's profile key. The signature is what proves *which
 * member* wrote it — the room lock only proves *a* member did. Signing is performed by the profile
 * key held in profiles-domain; this object owns the label and the verify side, mirroring how the
 * profile and room-info disclosures sign their content.
 */
internal object MessageSigning {
    /**
     * Int64 domain-separation tag folded into the signing transcript for messages. These tags are a
     * global allocation (like keyspaces): each SignedContent use site picks a distinct value so a
     * signature made for one purpose can't validate for another. 3 = message.
     */
    const val LABEL = 3L

    /** True if [message] carries a valid signature by [senderKey] (the sender's own profile key). */
    suspend fun verify(message: SignedContent, senderKey: Secp256r1PublicKey): Boolean =
        verifyContent(message, LABEL, senderKey)
}
