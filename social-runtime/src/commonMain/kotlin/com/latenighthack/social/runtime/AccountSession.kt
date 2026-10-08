package com.latenighthack.social.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** The host's current committed account; null withdraws all account-owned capabilities. */
interface AccountSession {
    val owner: StateFlow<String?>
    val generation: StateFlow<Long> get() = DEFAULT_GENERATION
    val mayAdoptLegacyStorage: Boolean get() = false
}

fun AccountSession?.ownerChanges(): Flow<String?> = this?.owner ?: flowOf("")
fun AccountSession?.currentOwner(): String = if (this == null) "" else checkNotNull(owner.value) { "account is signed out" }
fun AccountSession?.owns(recordOwner: String): Boolean = this == null ||
    (owner.value != null && (recordOwner == owner.value || (recordOwner.isEmpty() && mayAdoptLegacyStorage)))

private val DEFAULT_GENERATION: StateFlow<Long> = kotlinx.coroutines.flow.MutableStateFlow(0L)
