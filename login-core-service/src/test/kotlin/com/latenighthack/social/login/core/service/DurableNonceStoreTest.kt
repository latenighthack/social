package com.latenighthack.social.login.core.service

import com.latenighthack.ktstore.createDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class DurableNonceStoreTest {
    @Test fun issuedNoncesSurviveReopenAndCanOnlyBeConsumedOnce() = runBlocking {
        val file = File.createTempFile("social-nonce", ".db")
        val config = LoginStorage.configuration(file.name)
        var database = createDatabase(config, file.absolutePath)
        try {
            database.open()
            val nonce = NonceService(clock = { 1000 }, store = DurableNonceStore(database)).issue()
            database.close()
            database = createDatabase(config, file.absolutePath)
            database.open()
            val service = NonceService(clock = { 2000 }, store = DurableNonceStore(database))
            assertTrue(service.consume(nonce, nonce))
            assertFalse(service.consume(nonce, nonce))
            val expired = NonceService(ttlMillis = 1, clock = { 1000 }, store = DurableNonceStore(database)).issue()
            assertFalse(service.consume(expired, expired))
        } finally { database.close(); file.delete() }
    }
}
