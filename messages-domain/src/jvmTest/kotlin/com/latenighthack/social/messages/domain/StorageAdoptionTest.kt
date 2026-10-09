package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

/** Frozen V1 wire fixtures, including an unknown field and legacy Android blob TEXT values. */
class StorageAdoptionTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val fixtures = mapOf(
        "messages" to StoreRow(hex("0a010112030a0102a00607"), listOf(BoundStoreKey.SerializedKey("roomId", hex("01")), BoundStoreKey.SerializedKey("messageIdtoByteArray", hex("0a0102")))),
        "pending_messages" to StoreRow(hex("0a010112030a0102a00607"), listOf(BoundStoreKey.SerializedKey("roomId", hex("01")), BoundStoreKey.SerializedKey("messageIdtoByteArray", hex("0a0102")))),
        "dead_letter_messages" to StoreRow(hex("0a010112030a0102a00607"), listOf(BoundStoreKey.SerializedKey("roomId", hex("01")), BoundStoreKey.SerializedKey("messageIdtoByteArray", hex("0a0102")))),
        "drafts" to StoreRow(hex("0a0101a00607"), listOf(BoundStoreKey.SerializedKey("roomId", hex("01"))))
    )
    @Test fun adoptsEveryOwnedStorePreservesBytesAndSupportsReopen() = runBlocking {
        val file = File.createTempFile("messagesstorage-legacy", ".db")
        val configuration = MessagesStorage.configuration(file.name)
        val legacy = SqlStoreDelegate(JdbcDriver(file.absolutePath, "sqlite"), "BLOB", legacyBinaryText = true)
        try {
            configuration.stores.forEach { legacy.registerStore(it.name.value, it.keys, it.primaryKey) }
            legacy.createStores()
            fixtures.forEach { (table, row) -> legacy.save(table, row.data, row.keys) }
        } finally { legacy.close() }
        fun handle() = createDatabase(configuration, file.absolutePath)
        var current = handle()
        try {
            current.open()
            for (attempt in 1..2) {
                current.transaction(configuration.stores.map { it.name }.toSet(), TransactionMode.READ_ONLY) {
                    fixtures.forEach { (table, expected) ->
                        assertContentEquals(expected.data as ByteArray, getAll(StoreName(table)).single() as ByteArray)
                        // Every independently captured scalar key remains queryable after rebuilding.
                        expected.keys.forEach { key ->
                            assertContentEquals(expected.data as ByteArray, get(StoreName(table), StoreRelation.Eq(key)) as ByteArray)
                        }
                    }
                }
                current.close()
                if (attempt == 1) { current = handle(); current.open() }
            }
        } finally { current.close(); file.delete() }
    }
}
