package com.latenighthack.social.avatars.usecase

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.avatar
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileBuilder
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.profiles.v1.copy
import com.latenighthack.social.remotecontent.domain.RemoteContentUploader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SetMyAvatarUseCaseTest {

    private class FakeUploader(private val url: String) : RemoteContentUploader {
        var uploadedBytes: ByteArray? = null
        var uploadedMime: String? = null
        override suspend fun upload(bytes: ByteArray, mimeType: String?): String {
            uploadedBytes = bytes
            uploadedMime = mimeType
            return url
        }
    }

    private class FakeMyProfiles(private val profileId: ProfileId?) : MyProfilesManager {
        var updated: Profile? = null
        override fun getProfileList(): Flow<List<ProfileId>> = flowOf(listOfNotNull(profileId))
        override suspend fun updateProfile(profileId: ProfileId, builder: ProfileBuilder.() -> Unit) {
            updated = Profile { }.copy(builder)
        }

        override fun start(lockers: LockersClient) = error("unused")
        override fun stop() = error("unused")
        override suspend fun createProfile(displayName: String): ProfileId = error("unused")
        override suspend fun deriveSharedSecret(profileId: ProfileId, peerPublicKey: ByteArray): ByteArray? =
            error("unused")
        override suspend fun sign(profileId: ProfileId, label: Long, content: ByteArray): SignedContent? =
            error("unused")
        override fun getProfile(id: ProfileId): Profile? = error("unused")
        override fun watchProfile(id: ProfileId): Flow<Profile?> = error("unused")
        override fun getProfiles(ids: List<ProfileId>): List<Profile?> = error("unused")
        override fun watchProfiles(ids: List<ProfileId>): Flow<List<Profile?>> = error("unused")
    }

    @Test
    fun `set uploads the bytes and binds the download URL as the profile avatar`() = runBlocking {
        val uploader = FakeUploader("https://cdn.example/pic")
        val profiles = FakeMyProfiles(ProfileId { rawValue = byteArrayOf(1) })

        SetMyAvatarUseCase(uploader, profiles).set(byteArrayOf(7, 7), "image/png")

        assertContentEquals(byteArrayOf(7, 7), uploader.uploadedBytes)
        assertThat(uploader.uploadedMime).isEqualTo("image/png")
        assertThat(profiles.updated?.avatar()).isEqualTo("https://cdn.example/pic")
    }

    @Test
    fun `set fails when the user has no profile yet`() {
        val uploader = FakeUploader("https://cdn.example/pic")
        val profiles = FakeMyProfiles(profileId = null)

        try {
            runBlocking { SetMyAvatarUseCase(uploader, profiles).set(byteArrayOf(1), "image/png") }
            error("expected failure")
        } catch (e: IllegalStateException) {
            assertThat(e.message).isEqualTo("a profile must exist before setting an avatar")
        }
    }
}
