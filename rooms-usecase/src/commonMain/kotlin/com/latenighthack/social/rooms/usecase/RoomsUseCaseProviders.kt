package com.latenighthack.social.rooms.usecase

import com.latenighthack.social.profiles.domain.ProfilesManager
import com.latenighthack.social.rooms.domain.RoomsManager
import me.tatarka.inject.annotations.Provides

/** kotlin-inject bindings for the rooms use cases. Requires RoomsProviders in the component. */
interface RoomsUseCaseProviders {
    @Provides
    fun createGroupUseCase(rooms: RoomsManager): CreateGroupUseCase = CreateGroupUseCase(rooms)

    @Provides
    fun openRendezvousUseCase(rooms: RoomsManager): OpenRendezvousUseCase = OpenRendezvousUseCase(rooms)

    @Provides
    fun createInviteCodeUseCase(rooms: RoomsManager): CreateInviteCodeUseCase = CreateInviteCodeUseCase(rooms)

    @Provides
    fun revokeInviteCodeUseCase(rooms: RoomsManager): RevokeInviteCodeUseCase = RevokeInviteCodeUseCase(rooms)

    @Provides
    fun joinByCodeUseCase(rooms: RoomsManager): JoinByCodeUseCase = JoinByCodeUseCase(rooms)

    @Provides
    fun leaveRoomUseCase(rooms: RoomsManager): LeaveRoomUseCase = LeaveRoomUseCase(rooms)

    @Provides
    fun updateRoomInfoUseCase(rooms: RoomsManager): UpdateRoomInfoUseCase = UpdateRoomInfoUseCase(rooms)

    @Provides
    fun watchRoomInfoUseCase(rooms: RoomsManager): WatchRoomInfoUseCase = WatchRoomInfoUseCase(rooms)

    @Provides
    fun watchRoomsUseCase(rooms: RoomsManager, profiles: ProfilesManager): WatchRoomsUseCase =
        WatchRoomsUseCase(rooms, profiles)

    @Provides
    fun watchMembersUseCase(rooms: RoomsManager, profiles: ProfilesManager): WatchMembersUseCase =
        WatchMembersUseCase(rooms, profiles)
}
