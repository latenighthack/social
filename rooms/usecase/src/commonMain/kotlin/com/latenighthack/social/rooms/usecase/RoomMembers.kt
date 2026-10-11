package com.latenighthack.social.rooms.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.profiles.domain.ProfilesManager
import com.latenighthack.social.rooms.domain.RoomsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Watches a room's members, joining each member's profile (null until it resolves). */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun watchRoomMembers(
    rooms: RoomsManager,
    profiles: ProfilesManager,
    roomId: RoomId,
): Flow<List<RoomMember>> =
    rooms.watchMembers(roomId).flatMapLatest { ids ->
        ids.forEach { profiles.observe(it) }
        profiles.watchProfiles(ids).map { resolved ->
            ids.zip(resolved) { id, profile -> RoomMember(id, profile) }
        }
    }
