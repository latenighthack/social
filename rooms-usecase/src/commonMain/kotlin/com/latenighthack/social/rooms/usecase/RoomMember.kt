package com.latenighthack.social.rooms.usecase

import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileId

/** A room member with their joined profile, or null until it resolves. */
data class RoomMember(
    val profileId: ProfileId,
    val profile: Profile?,
)
