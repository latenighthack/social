package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.DraftsManager
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.messages.v1.DraftAttachment
import com.latenighthack.social.remotecontent.domain.RemoteContentUploader
import com.latenighthack.social.remotecontent.domain.Upload

/**
 * Attaches a photo to a room's draft: starts a set-and-forget upload of the [bytes] (the download URL
 * is available before the transfer completes), builds an Image [Component] referencing that URL, and
 * adds it to the draft as a [DraftAttachment] keyed by the upload's content id. Returns the [Upload]
 * handle so the caller can observe the background transfer; the URL may briefly 404 until it finishes.
 */
class PhotoAttachUseCase(
    private val uploader: RemoteContentUploader,
    private val drafts: DraftsManager,
) {
    suspend fun attach(roomId: RoomId, bytes: ByteArray, mimeType: String?): Upload {
        val upload = uploader.enqueue(bytes, mimeType)
        val component = Component { contents.image { image { url = upload.downloadUrl } } }
        drafts.addAttachment(roomId, DraftAttachment {
            contentId = upload.contentId.rawValue
            this.component = component
        })
        return upload
    }
}
