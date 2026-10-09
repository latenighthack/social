package com.latenighthack.social.runtime

/** Prepare suspendable signatures again whenever the storage CAS supplies a fresher value. */
suspend fun <T> rebasedUpdate(initial: T, prepare: suspend (T) -> T, commit: suspend ((T) -> T) -> T?): T {
    var base = initial
    repeat(32) {
        val prepared = prepare(base)
        try {
            return commit { current ->
                if (current != base) {
                    base = current
                    throw RebaseRequired()
                }
                prepared
            } ?: prepared
        } catch (_: RebaseRequired) {
            // The fresh CAS value, rather than an eventually consistent cache, is the next base.
        }
    }
    error("concurrent changes prevented update; retry the operation")
}
private class RebaseRequired : Exception()
