package com.latenighthack.social.runtime

/** Callers serialize access. TTL uses a local monotonic clock supplied by the owner. */
class BoundedCache<K, V>(private val capacity: Int, private val ttlMillis: Long, private val now: () -> Long) {
    private data class Entry<V>(val value: V, val at: Long)
    private val entries = linkedMapOf<K, Entry<V>>()
    init { require(capacity > 0 && ttlMillis > 0) }
    fun put(key: K, value: V) {
        prune()
        entries.remove(key)
        entries[key] = Entry(value, now())
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    fun values(): List<V> { prune(); return entries.values.map { it.value } }
    private fun prune() { val time = now(); entries.entries.removeAll { time - it.value.at >= ttlMillis } }
}

fun monotonicMillisClock(): () -> Long {
    val origin = kotlin.time.TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
