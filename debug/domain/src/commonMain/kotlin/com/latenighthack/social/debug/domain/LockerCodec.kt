package com.latenighthack.social.debug.domain

/**
 * Parses a locker's raw bytes into a readable, hierarchical map for debugging. The canonical
 * implementation decodes the keyspace's proto type and calls the ktproto-generated `toValue()`,
 * e.g. `LockerCodec { MessagePayload.fromByteArray(it).toValue() }` — scalars pass through, bytes
 * become base64 strings, nested messages become nested maps and repeated fields become lists.
 */
fun interface LockerCodec {
    fun decode(bytes: ByteArray): Map<String, Any?>
}
