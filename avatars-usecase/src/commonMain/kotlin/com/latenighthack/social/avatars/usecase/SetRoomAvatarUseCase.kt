package com.latenighthack.social.avatars.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.remotecontent.domain.RemoteContentUploader
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.rooms.domain.replaceDisclosure

/**
 * Sets a room's avatar from the given photo [bytes]: uploads them (set-and-forget — the download URL
 * is returned before the transfer completes) and binds the URL onto the room's shared info as an
 * avatar disclosure. Requires the user to be a member of [roomId] (updateInfo errors otherwise). The
 * upload finishes eventually-consistently in the background, so the URL may briefly 404 for viewers.
 */
class SetRoomAvatarUseCase(
    private val uploader: RemoteContentUploader,
    private val rooms: RoomsManager,
) {
    suspend fun set(roomId: RoomId, bytes: ByteArray, mimeType: String?) {
        val url = uploader.upload(bytes, mimeType)
        rooms.updateInfo(roomId) { replaceDisclosure { avatar { downloadUrl = url } } }
    }
}
