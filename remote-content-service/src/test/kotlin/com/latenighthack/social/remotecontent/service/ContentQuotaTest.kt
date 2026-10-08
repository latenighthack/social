package com.latenighthack.social.remotecontent.service

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ContentQuotaTest {
    @Test fun instancesShareDiskBudgetAndExpiredReservationsReleaseIt() = runTest {
        val directory = Files.createTempDirectory("content-quota").toFile()
        var now = 1L
        try {
            val first = FileContentStore(directory, maxStoredBytes = 16L * 1024 * 1024, clock = { now })
            val second = FileContentStore(directory, maxStoredBytes = 16L * 1024 * 1024, clock = { now })
            first.create(byteArrayOf(1), null, byteArrayOf(2))
            assertFailsWith<IllegalStateException> { second.create(byteArrayOf(3), null, byteArrayOf(4)) }
            now += 15 * 60_000
            second.create(byteArrayOf(3), null, byteArrayOf(4))
        } finally { directory.deleteRecursively() }
    }
}
