package com.latenighthack.social.observability.client

import org.khronos.webgl.Int8Array

actual fun socialPlatform(): String = "js"

internal actual suspend fun secureTraceBytes(size: Int): ByteArray {
    val bytes = Int8Array(size)
    js("globalThis.crypto.getRandomValues(bytes)")
    return bytes.unsafeCast<ByteArray>()
}
