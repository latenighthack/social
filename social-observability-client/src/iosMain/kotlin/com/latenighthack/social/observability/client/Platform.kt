package com.latenighthack.social.observability.client

import com.latenighthack.ktcrypto.RNG
import com.latenighthack.ktcrypto.randomBytes

actual fun socialPlatform(): String = "ios"

internal actual suspend fun secureTraceBytes(size: Int): ByteArray = RNG.randomBytes(size)
