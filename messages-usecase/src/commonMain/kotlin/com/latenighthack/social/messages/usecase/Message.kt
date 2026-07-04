package com.latenighthack.social.messages.usecase

import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.profiles.v1.ProfileId

/**
 * A message shown in a room: who sent it, when, its root [component] (text, photo, ...), and the
 * profiles that have read it (whose read pointer is at or past this message).
 */
data class Message(
    val senderProfileId: ProfileId,
    val sentAtMillis: Long,
    val component: Component,
    val readBy: Set<ProfileId>,
)
