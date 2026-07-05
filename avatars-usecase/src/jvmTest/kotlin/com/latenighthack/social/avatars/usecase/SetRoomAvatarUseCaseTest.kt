package com.latenighthack.social.avatars.usecase

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.remotecontent.domain.RemoteContentUploader
import com.latenighthack.social.remotecontent.domain.Upload
import com.latenighthack.social.remotecontent.domain.UploadStatus
import com.latenighthack.social.remotecontent.v1.ContentId
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.domain.avatar
import com.latenighthack.social.rooms.v1.InviteCode
import com.latenighthack.social.rooms.v1.RoomInfo
import com.latenighthack.social.rooms.v1.RoomInfoBuilder
import com.latenighthack.social.rooms.v1.copy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SetRoomAvatarUseCaseTest {

    private class FakeUploader(private val url: String) : RemoteContentUploader {
        var uploadedBytes: ByteArray? = null
        override suspend fun enqueue(bytes: ByteArray, mimeType: String?): Upload {
            uploadedBytes = bytes
            return Upload(ContentId { rawValue = byteArrayOf(0) }, url, UploadStatus.Queued)
        }

        override fun watchUploads(): Flow<List<Upload>> = flowOf(emptyList())
        override fun watchUpload(contentId: ContentId): Flow<Upload?> = flowOf(null)
    }

    private class FakeRooms : RoomsManager {
        var updated: RoomInfo? = null
        override suspend fun updateInfo(roomId: RoomId, builder: RoomInfoBuilder.() -> Unit) {
            updated = RoomInfo { }.copy(builder)
        }

        override fun start(lockers: LockersClient) = error("unused")
        override fun stop() = error("unused")
        override suspend fun createGroup(name: String): RoomId = error("unused")
        override suspend fun openRendezvous(peerProfileId: ProfileId): RoomId = error("unused")
        override suspend fun createInviteCode(roomId: RoomId): InviteCode = error("unused")
        override suspend fun revokeInviteCode(roomId: RoomId, code: InviteCode) = error("unused")
        override suspend fun joinByCode(code: InviteCode): RoomId = error("unused")
        override suspend fun leave(roomId: RoomId) = error("unused")
        override suspend fun markUpdated(roomId: RoomId) = error("unused")
        override fun watchRooms(): Flow<List<RoomId>> = error("unused")
        override fun watchInfo(roomId: RoomId): Flow<RoomInfo?> = error("unused")
        override fun watchMembers(roomId: RoomId): Flow<List<ProfileId>> = error("unused")
        override fun localProfile(roomId: RoomId): ProfileId? = error("unused")
    }

    @Test
    fun `set uploads the bytes and binds the download URL as the room avatar`() = runBlocking {
        val uploader = FakeUploader("https://cdn.example/room")
        val rooms = FakeRooms()

        SetRoomAvatarUseCase(uploader, rooms).set(RoomId(rawValue = ByteArray(32)), byteArrayOf(3), "image/jpeg")

        assertContentEquals(byteArrayOf(3), uploader.uploadedBytes)
        assertThat(rooms.updated?.avatar()).isEqualTo("https://cdn.example/room")
    }
}
