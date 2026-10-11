package com.latenighthack.social.login.core.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableNonceRegressionTest {
    @Test fun exactExpiryIsRejectedByMemoryAndDurableIssuers() = runTest {
        val database = LoginStorage.inMemory("nonce-expiry-boundary")
        database.open()
        try {
            for (store in listOf(null, ChallengeStore(database))) {
                var now = 1L
                val issuer = NonceService(ttlMillis = 10L, clock = { now }, store = store)
                val nonce = issuer.issue()
                now = 11L
                kotlin.test.assertFalse(issuer.consume(nonce, nonce))
            }
        } finally { database.close() }
    }

    @Test fun issuedNonceSurvivesDatabaseReopen() = runTest {
        val file = java.io.File.createTempFile("nonce-restart", ".db")
        val configuration = LoginStorage.configuration(file.name)
        var database = com.latenighthack.ktstore.createDatabase(configuration, file.absolutePath)
        try {
            database.open()
            val nonce = NonceService(store = ChallengeStore(database)).issue()
            database.close()
            database = com.latenighthack.ktstore.createDatabase(configuration, file.absolutePath)
            database.open()
            val restarted = NonceService(store = ChallengeStore(database))
            kotlin.test.assertTrue(restarted.consume(nonce, nonce))
            kotlin.test.assertFalse(restarted.consume(nonce, nonce))
        } finally { database.close(); file.delete() }
    }

    @Test fun nonceSurvivesIssuerReplacementAndIsConsumedOnceAcrossInstances() = runTest {
        val database = LoginStorage.inMemory("durable-nonce")
        database.open()
        val first = NonceService(store = ChallengeStore(database))
        val nonce = first.issue()
        val second = NonceService(store = ChallengeStore(database))
        val results = listOf(async { second.consume(nonce, nonce) }, async { first.consume(nonce, nonce) }).awaitAll()
        assertEquals(1, results.count { it })
    }
}
