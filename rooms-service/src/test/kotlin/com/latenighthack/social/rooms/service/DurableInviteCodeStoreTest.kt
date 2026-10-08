package com.latenighthack.social.rooms.service

import com.latenighthack.ktstore.Database
import com.latenighthack.ktstore.InMemoryStoreDelegate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DurableInviteCodeStoreTest {
    @Test fun hostCanExplicitlyMigrateAnExistingDatabaseToInviteStorage() = runTest {
        val file = java.io.File.createTempFile("invite-migration", ".db")
        val previous = com.latenighthack.ktstore.DatabaseConfiguration("host", 1, emptyList())
        val original = com.latenighthack.ktstore.createDatabase(previous, file.absolutePath)
        original.open(); original.close()
        val upgraded = com.latenighthack.ktstore.createDatabase(RoomsServiceStorage.upgrade(previous), file.absolutePath)
        try {
            upgraded.open()
            val store = DurableInviteCodeStore(upgraded, ByteArray(32) { 5 })
            store.prepare()
            store.put(byteArrayOf(1), StoredInviteCode(byteArrayOf(2), byteArrayOf(3), 0, 1, byteArrayOf()))
            assertContentEquals(byteArrayOf(3), store.get(byteArrayOf(1))!!.groupPrivateKey)
        } finally { upgraded.close(); file.delete() }
    }

    @Test fun encryptedCodeSurvivesClosingAndReopeningSqlite() = runTest {
        val file = java.io.File.createTempFile("invite-restart", ".db")
        val configuration = RoomsServiceStorage.configuration(file.name)
        val key = ByteArray(32) { 3 }
        val privateKey = ByteArray(32) { (it * 7 + 9).toByte() }
        val code = ByteArray(32) { 4 }
        var database = com.latenighthack.ktstore.createDatabase(configuration, file.absolutePath)
        try {
            database.open()
            val first = DurableInviteCodeStore(database, key)
            first.prepare()
            first.put(code, StoredInviteCode(byteArrayOf(9), privateKey, 0, 1, byteArrayOf()))
            database.close()
            kotlin.test.assertFalse(file.readBytes().asList().windowed(privateKey.size).any { it == privateKey.toList() })
            database = com.latenighthack.ktstore.createDatabase(configuration, file.absolutePath)
            database.open()
            val reopened = DurableInviteCodeStore(database, key)
            reopened.prepare()
            assertContentEquals(privateKey, reopened.get(code)!!.groupPrivateKey)
            kotlin.test.assertTrue(reopened.consumeUse(code))
            kotlin.test.assertFalse(reopened.consumeUse(code))
        } finally { database.close(); file.delete() }
    }

    @Test fun replacementInstancesShareEncryptedCodesAndOneUse() = runTest {
        val database = Database(RoomsServiceStorage.configuration("durable-invites"), InMemoryStoreDelegate())
        database.open()
        val key = ByteArray(32) { 3 }
        val first = DurableInviteCodeStore(database, key)
        first.prepare()
        val code = byteArrayOf(1, 2)
        first.put(code, StoredInviteCode(byteArrayOf(9), byteArrayOf(8), 0, 1, byteArrayOf()))
        val second = DurableInviteCodeStore(database, key)
        second.prepare()
        assertContentEquals(byteArrayOf(8), second.get(code)!!.groupPrivateKey)
        val uses = listOf(async { first.consumeUse(code) }, async { second.consumeUse(code) }).awaitAll()
        assertEquals(1, uses.count { it })
        second.delete(code)
        assertNull(first.get(code))
    }
}
