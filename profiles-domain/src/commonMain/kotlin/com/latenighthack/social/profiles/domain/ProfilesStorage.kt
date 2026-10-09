package com.latenighthack.social.profiles.domain

import com.latenighthack.ktstore.*

/** Complete schemas must be composed before any shared handle is opened. */
object ProfilesStorage {
    val definitions: List<StoreDefinition<*>> = listOf(
        com.latenighthack.social.profiles.domain.ProfileStoreDefinitionV1,
    )
    fun configuration(identity: String, additional: List<StoreDefinition<*>> = emptyList()) =
        definitionDatabaseConfiguration(identity, definitions + additional)
    fun inMemory(identity: String = "ProfilesStorage-test") =
        Database(configuration(identity), InMemoryStoreDelegate())
}
