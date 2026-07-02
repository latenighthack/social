package com.latenighthack.social.rooms.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.v1.RoomInfo
import kotlinx.coroutines.flow.Flow

/**
 * The user's rooms: shared, mutable, multi-participant spaces built on the lockers lock system.
 * Two kinds — rendezvous (1:1, met at the sha256 of an ECDH between two profiles) and groups
 * (many-member, keyed by a shared group key). The manager owns the shared key material for every
 * room the user is in (persisted device-locally, never synced), routes it to the client as the
 * write key for those rooms, and watches each of the user's profile inboxes for sealed invites.
 *
 * All membership is flat: every member holds the shared room key and may invite others, edit the
 * room info, and write the membership list. Removal/kick and key rotation are deferred.
 */
interface RoomsManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Creates a group room with the given name and returns its id. Acts as the user's first profile. */
    suspend fun createGroup(name: String): RoomId

    /** Opens the rendezvous room shared with [peerProfileId] and sends the peer a bootstrap invite. */
    suspend fun openRendezvous(peerProfileId: ProfileId): RoomId

    /** Invites [inviteeProfileIds] into a group [roomId] by sealing the group key into each inbox. */
    suspend fun invite(roomId: RoomId, inviteeProfileIds: List<ProfileId>)

    /** Leaves [roomId]: removes the user's own roster + profile entries and drops the local record. */
    suspend fun leave(roomId: RoomId)

    /** Sets the shared room info name. */
    suspend fun updateInfo(roomId: RoomId, name: String)

    /** The ids of the rooms the user belongs to, updated as rooms are joined or left. */
    fun watchRooms(): Flow<List<RoomId>>

    fun watchInfo(roomId: RoomId): Flow<RoomInfo?>

    /** The profile ids of the room's current members. */
    fun watchMembers(roomId: RoomId): Flow<List<ProfileId>>
}
