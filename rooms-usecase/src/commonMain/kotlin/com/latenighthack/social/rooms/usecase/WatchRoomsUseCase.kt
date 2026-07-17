package com.latenighthack.social.rooms.usecase

import com.latenighthack.social.profiles.domain.ProfilesManager
import com.latenighthack.social.rooms.domain.RoomsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/** Watches the rooms the user belongs to, joining each with its info and members. */
class WatchRoomsUseCase(
    private val rooms: RoomsManager,
    private val profiles: ProfilesManager,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch(): Flow<List<Room>> =
        rooms.watchRooms().flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    ids.map { id ->
                        // Seed each sub-flow so the outer combine can emit the full list without
                        // waiting on every room. watchInfo() stays silent for a room with no
                        // ROOM_INFO locker yet (a just-created room racing its own info write, or a
                        // derived/widget child room that never gets one), which would otherwise
                        // withhold the entire list. Room.info is nullable; info/members fill in.
                        combine(
                            rooms.watchInfo(id).onStart { emit(null) },
                            watchRoomMembers(rooms, profiles, id).onStart { emit(emptyList()) },
                        ) { info, members -> Room(id, info, members) }
                    },
                ) { it.toList() }
            }
        }
}
