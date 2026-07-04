package com.latenighthack.social.avatars.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.replaceDisclosure
import com.latenighthack.social.remotecontent.domain.RemoteContentUploader
import kotlinx.coroutines.flow.first

/**
 * Sets the user's profile avatar from the given photo [bytes]: uploads them (set-and-forget — the
 * download URL is returned before the transfer completes) and binds the URL onto the user's profile
 * as an avatar disclosure. Mono-profile for now: updates the first profile. The upload finishes
 * eventually-consistently in the background, so the URL may briefly 404 for viewers.
 */
class SetMyAvatarUseCase(
    private val uploader: RemoteContentUploader,
    private val myProfiles: MyProfilesManager,
) {
    suspend fun set(bytes: ByteArray, mimeType: String?) {
        val profileId = myProfiles.getProfileList().first().firstOrNull()
            ?: error("a profile must exist before setting an avatar")
        val url = uploader.upload(bytes, mimeType)
        myProfiles.updateProfile(profileId) { replaceDisclosure { avatar { downloadUrl = url } } }
    }
}
