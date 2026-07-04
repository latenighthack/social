package com.latenighthack.social.typing.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.profiles.v1.ProfileId
import kotlinx.coroutines.flow.Flow

/**
 * Typing indicators for rooms. Each member has a placeholder locker (keyed by their profile id) in a
 * room's typing keyspace; the "started typing" / "stopped typing" signal rides as an ephemeral event
 * attached to an update of that locker, so nothing meaningful is persisted. The manager observes
 * those events for every room the user is in and keeps a per-room map of who is currently typing,
 * dropping a member once their last signal ages past the timeout even with no explicit stop.
 */
interface TypingManager {
    fun start(lockers: LockersClient)
    fun stop()

    /**
     * Signals that the user is (or is no longer) typing in [roomId], as the profile they are in the
     * room as. "Started typing" signals are debounced to at most one send per debounce window while
     * [isTyping] stays true; a "stopped typing" signal is sent immediately.
     */
    suspend fun setTyping(roomId: RoomId, isTyping: Boolean)

    /**
     * The profile ids currently typing in [roomId] — those whose most recent signal is within the
     * timeout — excluding the user's own profile. Re-emits as members time out.
     */
    fun watchTyping(roomId: RoomId): Flow<Set<ProfileId>>
}
