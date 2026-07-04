package com.latenighthack.social.messages.usecase

import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.profiles.v1.ProfileId

/** A message shown in a room: who sent it, when, and its root [component] (text, photo, ...). */
data class Message(
    val senderProfileId: ProfileId,
    val sentAtMillis: Long,
    val component: Component,
)
