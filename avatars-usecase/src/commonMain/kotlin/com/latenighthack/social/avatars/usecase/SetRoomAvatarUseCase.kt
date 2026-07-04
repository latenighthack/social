package com.latenighthack.social.avatars.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.remotecontent.domain.RemoteContentUploader
import com.latenighthack.social.remotecontent.domain.Upload
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.domain.replaceDisclosure

/**
 * Sets a room's avatar from the given photo [bytes]: starts a set-and-forget upload (the download URL
 * is available before the transfer completes) and binds the URL onto the room's shared info as an
 * avatar disclosure. Requires the user to be a member of [roomId] (updateInfo errors otherwise).
 * Returns the [Upload] handle so the caller can observe the background transfer; the URL may briefly
 * 404 for viewers until it finishes.
 */
class SetRoomAvatarUseCase(
    private val uploader: RemoteContentUploader,
    private val rooms: RoomsManager,
) {
    suspend fun set(roomId: RoomId, bytes: ByteArray, mimeType: String?): Upload {
        val upload = uploader.enqueue(bytes, mimeType)
        rooms.updateInfo(roomId) { replaceDisclosure { avatar { downloadUrl = upload.downloadUrl } } }
        return upload
    }
}
