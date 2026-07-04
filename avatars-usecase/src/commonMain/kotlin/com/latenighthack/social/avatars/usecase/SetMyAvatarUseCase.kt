package com.latenighthack.social.avatars.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.replaceDisclosure
import com.latenighthack.social.remotecontent.domain.RemoteContentUploader
import com.latenighthack.social.remotecontent.domain.Upload
import kotlinx.coroutines.flow.first

/**
 * Sets the user's profile avatar from the given photo [bytes]: starts a set-and-forget upload (the
 * download URL is available before the transfer completes) and binds the URL onto the user's profile
 * as an avatar disclosure. Mono-profile for now: updates the first profile. Returns the [Upload]
 * handle so the caller can observe the background transfer; the URL may briefly 404 for viewers until
 * it finishes.
 */
class SetMyAvatarUseCase(
    private val uploader: RemoteContentUploader,
    private val myProfiles: MyProfilesManager,
) {
    suspend fun set(bytes: ByteArray, mimeType: String?): Upload {
        val profileId = myProfiles.getProfileList().first().firstOrNull()
            ?: error("a profile must exist before setting an avatar")
        val upload = uploader.enqueue(bytes, mimeType)
        myProfiles.updateProfile(profileId) { replaceDisclosure { avatar { downloadUrl = upload.downloadUrl } } }
        return upload
    }
}
