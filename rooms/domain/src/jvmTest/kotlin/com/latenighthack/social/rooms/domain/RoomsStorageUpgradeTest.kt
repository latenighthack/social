package com.latenighthack.social.rooms.domain

import com.latenighthack.ktstore.createDatabase
import com.latenighthack.ktstore.definitionDatabaseConfiguration
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.ConnectorStorage
import com.latenighthack.social.rooms.v1.LocalPendingRoom
import com.latenighthack.social.rooms.v1.RoomRecord
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class RoomsStorageUpgradeTest {
    @Test fun `an existing host database gains the rooms store and keeps reopening`() = runBlocking {
        val file = File.createTempFile("roomsstorage-upgrade", ".db")
        try {
            // A host database already on a device, created before rooms had a local store.
            val previous = definitionDatabaseConfiguration(file.name, ConnectorStorage.definitions)
            createDatabase(previous, file.absolutePath).also { it.open() }.close()

            val upgraded = RoomsStorage.upgrade(previous)
            assertEquals(previous.version + 1, upgraded.version)
            val room = RoomId(rawValue = byteArrayOf(1, 2, 3))
            for (attempt in 1..2) {
                val database = createDatabase(upgraded, file.absolutePath)
                try {
                    database.open()
                    val store = PendingRoomStore(database).also { it.prepare() }
                    if (attempt == 1) store.savePending(LocalPendingRoom(roomId = room.rawValue, ownerAccountId = "owner",
                        record = RoomRecord(roomId = room.rawValue)))
                    assertContentEquals(room.rawValue, store.pendingFor("owner").single().roomId)
                    assertTrue(store.pendingFor("other").isEmpty())
                } finally { database.close() }
            }
        } finally { file.delete() }
    }

    @Test fun `adding the rooms definitions without the upgrade cannot open an existing database`() = runBlocking {
        val file = File.createTempFile("roomsstorage-unversioned", ".db")
        try {
            createDatabase(definitionDatabaseConfiguration(file.name, ConnectorStorage.definitions), file.absolutePath).also { it.open() }.close()
            val unversioned = createDatabase(definitionDatabaseConfiguration(file.name, ConnectorStorage.definitions + RoomsStorage.definitions), file.absolutePath)
            try { assertFails { unversioned.open() } } finally { unversioned.close() }
        } finally { file.delete() }
        Unit
    }
}
