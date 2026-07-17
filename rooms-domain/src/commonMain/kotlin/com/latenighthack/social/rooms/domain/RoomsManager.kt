package com.latenighthack.social.rooms.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.v1.InviteCode
import com.latenighthack.social.rooms.v1.RoomInfo
import com.latenighthack.social.rooms.v1.RoomInfoBuilder
import kotlinx.coroutines.flow.Flow

/**
 * The user's rooms: shared, mutable, multi-participant spaces built on the lockers lock system.
 * Two kinds — rendezvous (1:1, met at the sha256 of an ECDH between two profiles) and groups
 * (many-member, keyed by a shared group key). The manager owns the shared key material for every
 * room the user is in (persisted device-locally, never synced), routes it to the client as the
 * write key for those rooms, and watches each of the user's profile inboxes for sealed invites.
 *
 * All membership is flat: every member holds the shared room key and may edit the room info and the
 * membership list. Group access is granted through server-mediated, revocable invite codes (a member
 * hands the server the room key so it can admit joiners); rendezvous still bootstraps 1:1 through a
 * sealed inbox invite. Removal/kick and key rotation are deferred.
 */
interface RoomsManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Creates a group room with the given name and returns its id. Acts as the user's first profile. */
    suspend fun createGroup(name: String): RoomId

    /** Opens the rendezvous room shared with [peerProfileId] and sends the peer a bootstrap invite. */
    suspend fun openRendezvous(peerProfileId: ProfileId): RoomId

    /**
     * Mints a shareable, revocable invite code for a group [roomId] via the Join service, handing the
     * server the room's shared key so it can admit joiners. Anyone holding the returned code can
     * [joinByCode]; each redeemed grant is sealed to the joiner's own profile. Fails if not a member.
     */
    suspend fun createInviteCode(roomId: RoomId): InviteCode

    /** Revokes [code] for group [roomId] so it can no longer be used to join. No-op if not a member. */
    suspend fun revokeInviteCode(roomId: RoomId, code: InviteCode)

    /** Redeems an invite [code]: fetches the sealed group grant, joins as the user's primary profile. */
    suspend fun joinByCode(code: InviteCode): RoomId

    /**
     * The deterministic child room id of [parentRoomId] for ([purpose], [salt]) — a pure function
     * of the parent's shared key, so any parent member can compute it (e.g. to read the child's
     * lockers) without joining. Fails if not a member of the parent.
     */
    suspend fun deriveChildRoomId(parentRoomId: RoomId, purpose: String, salt: ByteArray): RoomId

    /**
     * Joins the deterministic child room of [parentRoomId] for ([purpose], [salt]). The child key
     * is derived from the parent room's shared private key (domain-separated SHA-256), and the
     * room id is public-keyed from it — every parent member derives the same room with zero grant
     * exchange, and the derivation leaks nothing without the parent key. Idempotent: adopting,
     * locking, and membership writes are all safe to repeat. Fails if not a member of the parent.
     */
    suspend fun openDerivedRoom(parentRoomId: RoomId, purpose: String, salt: ByteArray): RoomId

    /** Leaves [roomId]: removes the user's own roster + profile entries and drops the local record. */
    suspend fun leave(roomId: RoomId)

    /**
     * Bumps [roomId]'s personal `updated_at` to now, moving it to the front of [watchRooms]. Other
     * systems call this for various reasons (e.g. new activity in a room). No-op if not a member.
     */
    suspend fun markUpdated(roomId: RoomId)

    /**
     * Applies [builder] to the room's current info, re-signs every resulting disclosure with the
     * shared room key, and writes it back. The caller sets disclosure payloads via the ktproto
     * builder (e.g. `replaceDisclosure { name { value = "…" } }`); signatures are (re)computed here.
     */
    suspend fun updateInfo(roomId: RoomId, builder: RoomInfoBuilder.() -> Unit)

    /**
     * The ids of the rooms the user belongs to, ordered by `updated_at` newest-first, updated as
     * rooms are joined, left, or bumped via [markUpdated].
     */
    fun watchRooms(): Flow<List<RoomId>>

    fun watchInfo(roomId: RoomId): Flow<RoomInfo?>

    /** The profile ids of the room's current members. */
    fun watchMembers(roomId: RoomId): Flow<List<ProfileId>>

    /** Which of the user's profiles they belong to [roomId] as, or null if not a member. */
    fun localProfile(roomId: RoomId): ProfileId?
}
