package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.DraftsManager
import com.latenighthack.social.messages.v1.Draft
import kotlinx.coroutines.flow.Flow

/** Watches a room's draft to restore the editor, or null when none is composed. */
class WatchDraftUseCase(
    private val drafts: DraftsManager,
) {
    fun watch(roomId: RoomId): Flow<Draft?> = drafts.watchDraft(roomId)
}
