package com.latenighthack.social.messages.usecase

import com.latenighthack.social.profiles.v1.ProfileId

/** A message shown in a room: who sent it, when, and its text. */
data class Message(
    val senderProfileId: ProfileId,
    val sentAtMillis: Long,
    val text: String,
)
