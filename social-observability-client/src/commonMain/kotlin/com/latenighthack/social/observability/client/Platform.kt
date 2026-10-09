package com.latenighthack.social.observability.client

expect fun socialPlatform(): String

internal expect suspend fun secureTraceBytes(size: Int): ByteArray
