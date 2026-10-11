package com.latenighthack.social.profiles.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.ProfilesManager
import me.tatarka.inject.annotations.Provides

/** kotlin-inject bindings for the profiles use cases. Requires ProfilesProviders in the component. */
interface ProfilesUseCaseProviders {
    @Provides
    fun updateMyDisplayNameUseCase(myProfiles: MyProfilesManager): UpdateMyDisplayNameUseCase =
        UpdateMyDisplayNameUseCase(myProfiles)

    @Provides
    fun myProfileInfoUseCase(myProfiles: MyProfilesManager): MyProfileInfoUseCase =
        MyProfileInfoUseCase(myProfiles)

    @Provides
    fun watchMyProfileUseCase(myProfiles: MyProfilesManager): WatchMyProfileUseCase =
        WatchMyProfileUseCase(myProfiles)

    @Provides
    fun myProfileInvitationUseCase(myProfiles: MyProfilesManager): MyProfileInvitationUseCase =
        MyProfileInvitationUseCase(myProfiles)

    @Provides
    fun hasProfileUseCase(myProfiles: MyProfilesManager): HasProfileUseCase =
        HasProfileUseCase(myProfiles)

    @Provides
    fun hasCachedProfileUseCase(myProfiles: MyProfilesManager): HasCachedProfileUseCase =
        HasCachedProfileUseCase(myProfiles)

    @Provides
    fun profileInfoUseCase(profiles: ProfilesManager): ProfileInfoUseCase = ProfileInfoUseCase(profiles)
}
