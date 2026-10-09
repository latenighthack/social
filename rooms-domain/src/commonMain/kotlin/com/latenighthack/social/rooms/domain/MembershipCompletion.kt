package com.latenighthack.social.rooms.domain

import com.latenighthack.social.rooms.v1.RoomRecord
import com.latenighthack.social.rooms.v1.copy

/** Completion rebases on the current account record; a concurrent leave remains authoritative. */
internal fun completeMembershipRecord(current: RoomRecord): RoomRecord =
    if (current.left) current else current.copy(membershipPending = false, initialInfo = ByteArray(0))
