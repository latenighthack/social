package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.domain.MessagesManager
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.readreceipts.domain.ReadReceiptsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Watches the messages in a room, oldest first, each annotated with the profiles that have read it. */
class WatchMessagesUseCase(
    private val messages: MessagesManager,
    private val readReceipts: ReadReceiptsManager,
) {
    fun watch(roomId: RoomId): Flow<List<Message>> =
        combine(
            messages.watchMessages(roomId),
            messages.watchMessageIds(roomId),
            readReceipts.watchReadReceipts(roomId),
        ) { entries, orderedIds, receipts ->
            // Each receipt is a member's "read up to here" pointer; a message at index i is read by a
            // member whose pointer resolves to index >= i. A pointer to a not-yet-synced message
            // resolves to -1 and counts as unread until that message arrives (eventually consistent).
            val readerIndices = receipts.mapValues { (_, pointer) ->
                orderedIds.indexOfFirst { it.rawValue.contentEquals(pointer.rawValue) }
            }
            entries.mapIndexed { index, entry ->
                Message(
                    senderProfileId = ProfileId { rawValue = entry.payload.senderProfileId },
                    sentAtMillis = entry.payload.sentAtMillis,
                    component = entry.payload.component ?: Component { },
                    readBy = readerIndices.filterValues { it >= index }.keys,
                    status = entry.status,
                )
            }
        }
}
