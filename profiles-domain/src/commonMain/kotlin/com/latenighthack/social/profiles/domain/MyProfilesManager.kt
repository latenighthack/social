package com.latenighthack.social.profiles.domain

import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileBuilder
import com.latenighthack.social.profiles.v1.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's own set of profiles. Each profile is a key pair whose secret half lives as a
 * locker in the account room; the profile is presented publicly as signed disclosures in the
 * profile's own key-locked room. All profiles are held in memory and loaded proactively once
 * the account is ready. Starts with just display name.
 */
interface MyProfilesManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Mints a new profile with the given display name and returns its id. */
    suspend fun createProfile(displayName: String): ProfileId

    /**
     * Applies [builder] to the profile's current value, re-signs every resulting disclosure with
     * the profile key, and writes it back. The caller sets disclosure payloads via the ktproto
     * builder; signatures are (re)computed here.
     */
    suspend fun updateProfile(profileId: ProfileId, builder: ProfileBuilder.() -> Unit)

    /**
     * The ECDH shared secret between the owned profile [profileId] and [peerPublicKey] (a 33-byte
     * compressed secp256r1 key), or null if [profileId] is not one of the user's profiles. Used by
     * the rooms feature to derive rendezvous rooms and to unseal invites addressed to a profile —
     * the profile's private key never leaves this manager.
     */
    suspend fun deriveSharedSecret(profileId: ProfileId, peerPublicKey: ByteArray): ByteArray?

    /**
     * Signs [content] with the owned profile [profileId]'s key under the domain-separation [label],
     * returning a standard [SignedContent] envelope, or null if [profileId] is not one of the user's
     * profiles. Lets other features vouch content as one of the user's profiles without the profile
     * private key ever leaving this manager (e.g. the messages feature signs each message).
     */
    suspend fun sign(profileId: ProfileId, label: Long, content: ByteArray): SignedContent?

    /** The ids of the user's profiles, updated as profiles are added. */
    fun getProfileList(): Flow<List<ProfileId>>

    /**
     * Whether a profile-source locker for this account is already present in the local cache — a
     * network-free existence check that reads only persisted lockers (no subscribe, no fetch, no
     * wait for [isLoaded] or account Ready). Lets the launch flow route a returning user straight
     * to their home while the session connects and [isLoaded] settles in the background. Returns
     * false when no account key exists yet or nothing has been cached.
     */
    suspend fun hasProfileCached(): Boolean

    /**
     * Whether the initial profile load from the account room has completed for the current session
     * (false until the account is Ready and its profiles have been read). Lets callers tell "no
     * profiles yet" apart from "not loaded yet".
     */
    val isLoaded: StateFlow<Boolean>

    fun getProfile(id: ProfileId): Profile?
    fun watchProfile(id: ProfileId): Flow<Profile?>
    fun getProfiles(ids: List<ProfileId>): List<Profile?>
    fun watchProfiles(ids: List<ProfileId>): Flow<List<Profile?>>
}
