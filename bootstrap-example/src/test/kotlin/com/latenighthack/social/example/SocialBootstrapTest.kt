package com.latenighthack.social.example

import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.connector.ConnectorStorage
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.profiles.domain.ProfilesStorage
import com.latenighthack.social.messages.domain.MessagesStorage
import com.latenighthack.social.remotecontent.domain.RemoteContentStorage
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SocialBootstrapTest {
    @Test(timeout = 30000)
    fun compiledComponentOwnsItsTasksButNeverClosesTheHostDatabase() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            withContext(Dispatchers.Default) {
                val database = Database(MessagesStorage.configuration("compiled-example",
                    ConnectorStorage.definitions + ProfilesStorage.definitions + RemoteContentStorage.definitions),
                    InMemoryStoreDelegate())
                val http = HttpClient()
                database.open()
                val component = SocialComponent::class.create(
                    KeyValueStore(InMemoryKeyValueStoreDelegate()), database, server.rpcClient, http)
                val client = component.startSocial(KeyValueStore(InMemoryKeyValueStoreDelegate()), Version(0, 0, 1))
                try {
                    component.account.createAccount()
                    component.profiles.createProfile("Example")
                    component.rooms.createGroup("Compiled wiring")
                } finally { component.stopSocial(client) }
                // A closed shared database rejects transactions. Manager and connector shutdown
                // must leave ownership here, and every contributed lifecycle has been joined.
                database.transaction(database.configuration.stores.map { it.name }.toSet(), TransactionMode.READ_ONLY) {
                    getAll(StoreName("profiles"))
                }
                database.close()
                http.close()
            }
        }
}
