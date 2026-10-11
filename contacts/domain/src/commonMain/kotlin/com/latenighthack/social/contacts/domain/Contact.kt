package com.latenighthack.social.contacts.domain

import com.latenighthack.social.profiles.v1.ProfileId

/**
 * A single entry in the user's contact list: a profile the user has friended, blocked, or both.
 * Friended when [friendedAtMillis] is non-null; blocked when [blockedAtMillis] is non-null. The two
 * are independent, so a profile may carry both timestamps at once.
 */
data class Contact(
    val profileId: ProfileId,
    val friendedAtMillis: Long?,
    val blockedAtMillis: Long?,
)
