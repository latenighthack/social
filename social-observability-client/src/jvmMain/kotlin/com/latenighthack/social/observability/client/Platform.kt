package com.latenighthack.social.observability.client

actual fun socialPlatform(): String = "jvm"

private val traceRandom = java.security.SecureRandom()
internal actual suspend fun secureTraceBytes(size: Int): ByteArray = ByteArray(size).also(traceRandom::nextBytes)
