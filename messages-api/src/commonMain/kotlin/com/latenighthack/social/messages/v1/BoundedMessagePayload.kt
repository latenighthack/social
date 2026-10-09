package com.latenighthack.social.messages.v1

/** Check the recursive wire structure before invoking the generated recursive protobuf decoder. */
object BoundedMessagePayload {
    const val MAX_BYTES = 65_536
    private const val MAX_NODES = 512
    private const val MAX_DEPTH = 32

    /** Reject a pathological local tree before the recursive encoder runs. */
    fun requireEncodable(component: Component) {
        val pending = ArrayDeque<Pair<Component, Int>>()
        pending.add(component to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            require(++nodes <= MAX_NODES && depth <= 12) { "component tree exceeds message bounds" }
            val children = (node.contents as? Component.OneOfContents.container)?.value?.children.orEmpty()
            require(children.size <= MAX_NODES - nodes)
            children.forEach { pending.add(it to depth + 1) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Untrusted protobuf must not restart the collector.
    fun decode(bytes: ByteArray): MessagePayload? {
        if (bytes.size > MAX_BYTES) return null
        return try {
            Wire(bytes).message(0, bytes.size, Schema.PAYLOAD, 0)
            MessagePayload.fromByteArray(bytes)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private enum class Schema { PAYLOAD, COMPONENT, CONTAINER, ACTION, IMAGE, TEXT, INLINE, RULE, TAPPABLE, ICON, GRID, LEAF }

    private val children = mapOf(
        Schema.PAYLOAD to mapOf(4 to Schema.COMPONENT),
        Schema.COMPONENT to mapOf(1 to Schema.ACTION, 2 to Schema.CONTAINER,
            101 to Schema.LEAF, 102 to Schema.IMAGE, 103 to Schema.LEAF, 104 to Schema.TEXT),
        Schema.CONTAINER to mapOf(1 to Schema.COMPONENT, 2 to Schema.LEAF, 3 to Schema.LEAF,
            4 to Schema.LEAF, 5 to Schema.GRID, 6 to Schema.LEAF, 7 to Schema.LEAF, 8 to Schema.LEAF),
        Schema.ACTION to mapOf(1 to Schema.LEAF, 2 to Schema.LEAF),
        Schema.IMAGE to mapOf(1 to Schema.LEAF),
        Schema.TEXT to mapOf(3 to Schema.INLINE),
        Schema.INLINE to mapOf(3 to Schema.RULE),
        Schema.RULE to mapOf(1 to Schema.LEAF, 2 to Schema.LEAF, 3 to Schema.LEAF,
            4 to Schema.TAPPABLE, 5 to Schema.LEAF, 6 to Schema.LEAF, 7 to Schema.ICON),
        Schema.TAPPABLE to mapOf(1 to Schema.ACTION),
        Schema.ICON to mapOf(1 to Schema.LEAF),
        Schema.GRID to mapOf(1 to Schema.LEAF),
    )

    private class Wire(private val bytes: ByteArray) {
        private var position = 0
        private var nodes = 0
        private fun varint(end: Int): Long {
            var value = 0L
            for (index in 0..9) {
                require(position < end)
                val byte = bytes[position++].toInt() and 255
                require(index != 9 || byte <= 1)
                value = value or ((byte and 127).toLong() shl (index * 7))
                if (byte < 128) return value
            }
            error("invalid varint")
        }
        fun message(start: Int, end: Int, schema: Schema, depth: Int) {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES)
            position = start
            while (position < end) {
                val tag = varint(end)
                require(tag in 1..Int.MAX_VALUE.toLong())
                val field = (tag ushr 3).toInt()
                require(field > 0)
                when ((tag and 7).toInt()) {
                    0 -> varint(end)
                    1 -> { require(end - position >= 8); position += 8 }
                    5 -> { require(end - position >= 4); position += 4 }
                    2 -> {
                        val length = varint(end)
                        require(length >= 0 && length <= end - position)
                        val limit = position + length.toInt()
                        child(schema, field)?.let { message(position, limit, it, depth + 1) }
                        position = limit
                    }
                    else -> throw IllegalArgumentException("unsupported protobuf wire type")
                }
            }
        }
        private fun child(schema: Schema, field: Int): Schema? = children[schema]?.get(field)

    }
}
