package com.latenighthack.social.example

import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockersClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The database must be composed and opened by the host before calling this function. */
@Suppress("TooGenericExceptionCaught") // Release constructed resources on every startup failure, then rethrow.
suspend fun SocialComponent.startSocial(connectorState: KeyValueStore, version: Version): LockersClient {
    lifecycles.forEach { it.prepare() }
    val client = LockersClient.create(
        rpcClient = rpcClient, database = database, keyValueStore = connectorState,
        keySource = authenticationKeySource, appVersion = version, lockKeySource = lockKeySource,
    )
    try {
        lifecycles.forEach { it.start(client) }
        return client
    } catch (failure: Throwable) {
        withContext(NonCancellable) { stopSocial(client) }
        throw failure
    }
}

/** Stop children before closing connector resources; the host closes database and HTTP later. */
suspend fun SocialComponent.stopSocial(client: LockersClient) {
    lifecycles.forEach { it.stop() }
    lifecycles.forEach { it.stopAndJoin() }
    tasks.cancel()
    client.close()
}
