package com.latenighthack.social.login.core.service

import com.latenighthack.ktstore.*
import com.latenighthack.social.login.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

class AuthenticationConcurrencyRegressionTest {
    @Test fun oneOtpCannotAuthenticateTwoConcurrentRequests() = runTest {
        val base = InMemoryStoreDelegate()
        var armed = false
        var reads = 0
        val firstRead = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val delegate = object : LifecycleStoreDelegate by base, ScopedStoreDelegate, IndexedQueryDelegate {
            override suspend fun query(tableName: String, query: IndexedQuery, identity: String, version: Int) = base.query(tableName, query, identity, version)
            override suspend fun count(tableName: String, query: IndexedQuery) = base.count(tableName, query)
            override suspend fun deleteBatch(tableName: String, query: IndexedQuery, identity: String, version: Int) = base.deleteBatch(tableName, query, identity, version)
            override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T): T = base.transaction(stores, mode, block)
            override suspend fun <T> transaction(block: suspend () -> T): T = base.transaction(block)
            override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = base.transaction(lockKey, block)
            override suspend fun get(tableName: String, relation: StoreRelation?): Any? {
                val snapshot = base.get(tableName, relation)
                if (armed && tableName == "login_challenges" && ++reads == 1) {
                    firstRead.complete(Unit)
                    releaseRead.await()
                }
                return snapshot
            }
        }
        val db = Database(LoginStorage.configuration("review-login"), delegate)
        db.open()
        var code = ""
        val service = LoginServiceImpl(CredentialStore(db), ChallengeStore(db),
            CustodyCrypto(ByteArray(32) { 1 }), Pbkdf2Hasher(1000), null, null, null,
            object : SmsSender { override suspend fun sendCode(phoneNumber: String, value: String) { code = value } },
            "https://example.test", maxAttempts = 1)
        val rpc = LocalLoginServiceRpc(service)
        rpc.startPhoneCode(StartPhoneCodeRequest { phoneNumber = "+15550000001" })
        armed = true
        suspend fun verify() = rpc.verifyPhoneCode(VerifyPhoneCodeRequest {
            phoneNumber = "+15550000001"; this.code = code
        }).result
        val first = async { verify() }
        firstRead.await()
        val second = async { verify() }
        runCurrent()
        releaseRead.complete(Unit)
        val results = listOf(first, second).awaitAll()
        assertEquals(1, results.count { it == LoginResult.LOGIN_RESULT_NEEDS_BINDING }, "one OTP produced $results")
    }
}
