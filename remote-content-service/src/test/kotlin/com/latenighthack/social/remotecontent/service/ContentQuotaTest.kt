package com.latenighthack.social.remotecontent.service

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ContentQuotaTest {
    @Test fun legacyPublicationsCountAndInterruptedPublicationFilesAreReclaimed() = runTest {
        val directory = Files.createTempDirectory("legacy-content-quota").toFile()
        try {
            val shard = java.io.File(directory, "01").also { it.mkdirs() }
            val legacy = java.io.File(shard, "01").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val store = FileContentStore(directory, maxStoredBytes = 16L * 1024 * 1024)
            assertFailsWith<IllegalStateException> { store.create(byteArrayOf(2), null, byteArrayOf(3)) }
            legacy.delete()
            val interrupted = java.io.File(shard, "01.12345.tmp").also { it.writeBytes(byteArrayOf(1)) }
            store.create(byteArrayOf(2), null, byteArrayOf(3))
            kotlin.test.assertFalse(interrupted.exists())
        } finally { directory.deleteRecursively() }
    }

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
