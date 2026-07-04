package com.latenighthack.social.contacts.domain

import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.profiles.v1.ProfileId
import kotlinx.coroutines.flow.Flow

/**
 * The user's contact list: friends and blocks, one entry per profile. Each entry is a locker in the
 * user's own account room (locked to the account key and synced, so the list survives an account
 * restore), keyed by profile id. Friending and blocking are independent — a profile may be both.
 */
interface ContactsManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Marks [profileId] as a friend (refreshing the timestamp), leaving any block in place. */
    suspend fun add(profileId: ProfileId)

    /** Marks [profileId] as blocked (refreshing the timestamp), leaving any friend mark in place. */
    suspend fun block(profileId: ProfileId)

    /** Clears the friend mark on [profileId]; drops the entry entirely if it was not also blocked. */
    suspend fun unfriend(profileId: ProfileId)

    /** Clears the block on [profileId]; drops the entry entirely if it was not also a friend. */
    suspend fun unblock(profileId: ProfileId)

    /** The user's contacts, each carrying its friend and/or block state. */
    fun watchContacts(): Flow<List<Contact>>
}
