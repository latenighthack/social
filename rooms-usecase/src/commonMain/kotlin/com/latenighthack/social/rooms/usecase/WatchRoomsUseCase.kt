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
    fun watch(): Flow<List<Room>> = com.latenighthack.social.runtime.keyedFlows(
        rooms.watchRooms(),
        initial = { id -> Room(id, null, emptyList()) },
        watch = { id ->
            combine(
                rooms.watchInfo(id).onStart { emit(null) },
                watchRoomMembers(rooms, profiles, id).onStart { emit(emptyList()) },
            ) { info, members -> Room(id, info, members) }
        },
    )
}
