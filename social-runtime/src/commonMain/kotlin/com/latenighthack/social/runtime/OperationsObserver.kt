package com.latenighthack.social.runtime

/** Best-effort local measurements. Names are finite constants, never IDs, URLs or content. */
fun interface OperationsObserver {
    fun observe(stage: String, outcome: String, seconds: Double, depth: Int, bytes: Long)
    companion object { val NONE = OperationsObserver { _, _, _, _, _ -> } }
}

fun OperationsObserver.record(stage: String, outcome: String = "success", seconds: Double = 0.0, depth: Int = 0, bytes: Long = 0) {
    runCatching { observe(stage, outcome, seconds, depth, bytes) }
}
