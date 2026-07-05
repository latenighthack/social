package com.latenighthack.social.debug.domain

import com.latenighthack.lockers.common.v1.LockerKeyspace

/**
 * The per-keyspace codecs the debug tool uses to parse locker bytes into readable maps. Mirrors the
 * connector's `NotificationCodecs`: build it once (typically in the app, which knows every feature's
 * proto types) and hand it to the [DebugManager]. A keyspace with no registered codec falls back to
 * showing raw base64, so registration is the sole opt-in to decoding a keyspace.
 */
class LockerCodecs private constructor(
    private val perKeyspace: Map<Long, Entry>,
) {
    /** A registered codec plus a human-readable name for its keyspace (e.g. "messages"). */
    class Entry(val name: String, val codec: LockerCodec)

    fun entryFor(keyspace: LockerKeyspace): Entry? = perKeyspace[keyspace.value]

    class Builder {
        private val perKeyspace = mutableMapOf<Long, Entry>()

        /** Registers [codec] (and a display [name]) for [keyspace]; a later call replaces an earlier one. */
        fun register(keyspace: Long, name: String, codec: LockerCodec) = apply {
            perKeyspace[keyspace] = Entry(name, codec)
        }

        fun build(): LockerCodecs = LockerCodecs(perKeyspace.toMap())
    }

    companion object {
        fun builder(): Builder = Builder()

        /** No codecs registered — every keyspace renders as raw base64. */
        fun empty(): LockerCodecs = LockerCodecs(emptyMap())
    }
}
