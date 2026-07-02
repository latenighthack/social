package com.latenighthack.social.rooms.usecase

import com.latenighthack.social.profiles.domain.ProfilesManager
import com.latenighthack.social.rooms.domain.RoomsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

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
                        combine(
                            rooms.watchInfo(id),
                            watchRoomMembers(rooms, profiles, id),
                        ) { info, members -> Room(id, info, members) }
                    },
                ) { it.toList() }
            }
        }
}
