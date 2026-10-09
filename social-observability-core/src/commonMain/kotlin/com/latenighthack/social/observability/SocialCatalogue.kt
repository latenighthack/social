package com.latenighthack.social.observability

/** Bounded vocabulary shared by instrumentation, the relay and dashboard validation. */
object SocialCatalogue {
    private val lifecycle = setOf("prepare", "start", "stop", "watch", "cache", "observe", "ingest")
    val operations: Map<String, Set<String>> = mapOf(
        "runtime" to setOf("start", "stop", "report", "export", "dropped"),
        "account" to setOf("createAccount", "restoreAccount", "signOut", "initializePrivateRoom"),
        "profiles" to setOf("createProfile", "updateProfile", "deleteProfile", "observe", "getProfile", "getProfiles", "verifyDisclosures"),
        "login" to setOf("requestNonce", "nativeSignIn", "authenticateSocial", "startEmailLink", "completeEmailLink", "startPhoneCode", "verifyPhoneCode", "bind", "verify", "send", "recover", "challenge", "provider"),
        "rooms" to setOf("createGroup", "openRendezvous", "openDerivedRoom", "inviteToRoom", "markUpdated", "invite", "createInviteCode", "joinByCode", "join", "revokeInviteCode", "leave", "updateInfo", "acceptInvite"),
        "messages" to setOf("send", "retry", "attemptSend", "dead_letter", "signature", "queue", "setText", "addAttachment", "removeAttachment", "clear"),
        "remote_content" to setOf("createContent", "upload", "download", "enqueue", "retry", "queue", "storage"),
        "contacts" to setOf("add", "block", "unfriend", "unblock", "addContact", "blockContact", "unfriendContact", "unblockContact"),
        "typing" to setOf("setTyping", "debounced", "expired"),
        "read_receipts" to setOf("markRead"),
        "avatars" to setOf("setMyAvatar", "setRoomAvatar"),
        "debug" to setOf("watchLockers", "decode"),
        "transport" to setOf("rpc", "stream", "http"),
    ).mapValues { (_, value) -> value + lifecycle }
    val providers = setOf("none", "apple", "google", "email", "phone")
    val platforms = setOf("jvm", "android", "ios", "js")
    val kinds = setOf("operation", "event", "queue_depth", "queue_age", "bytes", "feature")
    val results = setOf("ok", "error", "cancelled", "rejected", "noop", "unauthorized", "invalid",
        "provider_unavailable", "expired", "already_bound", "needs_binding", "invalid_code", "not_allowed",
        "exhausted", "retry", "dead_letter", "debounced", "redundant", "no_profile", "no_message",
        "not_found", "invalid_signature", "malformed", "unknown")
    fun outcome(result: String): String = when (result) {
        "ok", "needs_binding" -> "success"
        "error", "retry", "dead_letter", "unknown" -> "error"
        "cancelled" -> "cancelled"
        "noop", "debounced", "redundant", "no_profile", "no_message" -> "noop"
        else -> "rejected"
    }
}

fun socialResult(enumValue: String): String = enumValue.substringAfter("RESULT_", enumValue).lowercase()
    .let { if (it in SocialCatalogue.results) it else "unknown" }
fun socialProvider(number: Int): String = when (number) { 1 -> "apple"; 2 -> "google"; 3 -> "email"; 4 -> "phone"; else -> "none" }

/** HTTP denials are expected rejections; only server/dependency failures are infrastructure errors. */
fun socialHttpResult(status: Int): String = when {
    status in 200..399 -> "ok"
    status == 401 -> "unauthorized"
    status == 403 -> "not_allowed"
    status == 404 -> "not_found"
    status in 400..499 -> "rejected"
    else -> "error"
}
