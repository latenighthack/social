package com.latenighthack.social.avatars.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.remotecontent.domain.RemoteContentUploader
import com.latenighthack.social.rooms.domain.RoomsManager
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the avatars use cases. Requires RemoteContentProviders, ProfilesProviders,
 * and RoomsProviders in the component (the uploader, profiles, and rooms managers are dependencies).
 */
interface AvatarsUseCaseProviders {
    @Provides
    fun setMyAvatarUseCase(uploader: RemoteContentUploader, myProfiles: MyProfilesManager): SetMyAvatarUseCase =
        SetMyAvatarUseCase(uploader, myProfiles)

    @Provides
    fun setRoomAvatarUseCase(uploader: RemoteContentUploader, rooms: RoomsManager): SetRoomAvatarUseCase =
        SetRoomAvatarUseCase(uploader, rooms)
}
