package com.latenighthack.social.rooms.domain

import com.latenighthack.social.rooms.v1.RoomRecord
import com.latenighthack.social.rooms.v1.copy
import kotlin.test.*

class MembershipCompletionRegressionTest {
    @Test fun leaveArrivingBeforeTheCompletionBuilderCannotBeResurrected() {
        val pending = RoomRecord(roomId = byteArrayOf(1), membershipPending = true, initialInfo = byteArrayOf(2),
            encryptedSharedPrivateKey = byteArrayOf(3))
        val leaving = pending.copy(left = true, leaving = true)
        assertEquals(leaving, completeMembershipRecord(leaving))
        val tombstone = leaving.copy(leaving = false, encryptedSharedPrivateKey = ByteArray(0))
        assertEquals(tombstone, completeMembershipRecord(tombstone))
        val completed = completeMembershipRecord(pending)
        assertFalse(completed.membershipPending)
        assertContentEquals(pending.encryptedSharedPrivateKey, completed.encryptedSharedPrivateKey)
    }
}
