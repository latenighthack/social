package com.latenighthack.social.messages.domain

/** Stable on every platform, independent of sender clocks and the order events arrived. */
internal val messageOrder = Comparator<MessageEntry> { a, b ->
    val counter = a.payload.orderingCounter.compareTo(b.payload.orderingCounter)
    if (counter != 0) counter else compareMessageIds(a.payload.messageId, b.payload.messageId)
}
private fun compareMessageIds(a: ByteArray, b: ByteArray): Int {
    for (index in 0 until minOf(a.size, b.size)) {
        val compared = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
        if (compared != 0) return compared
    }
    return a.size.compareTo(b.size)
}
