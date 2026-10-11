package com.latenighthack.social.messages.v1

/** Half-open UTF-16 range, clipped to the source and expanded to complete surrogate pairs. */
fun inlineRange(text: String, offset: Int, length: Int): Pair<Int, Int> {
    var start = offset.coerceIn(0, text.length)
    if (length <= 0) return start to start
    var end = (offset.toLong() + length).coerceIn(start.toLong(), text.length.toLong()).toInt()
    if (end == start) return start to end
    if (start > 0 && start < text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
    if (end > 0 && end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end++
    return start to end
}
