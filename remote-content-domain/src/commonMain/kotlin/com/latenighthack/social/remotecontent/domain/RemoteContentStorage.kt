package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktstore.*

/** Complete schemas must be composed before any shared handle is opened. */
object RemoteContentStorage {
    val definitions: List<StoreDefinition<*>> = listOf(
        com.latenighthack.social.remotecontent.domain.PendingUploadStoreDefinitionV1,
    )
    fun configuration(identity: String, additional: List<StoreDefinition<*>> = emptyList()) =
        definitionDatabaseConfiguration(identity, definitions + additional)
    fun inMemory(identity: String = "RemoteContentStorage-test") =
        Database(configuration(identity), InMemoryStoreDelegate())
}
