package com.latenighthack.social.login.core.service

import com.latenighthack.ktstore.*

/** Complete schemas must be composed before any shared handle is opened. */
object LoginStorage {
    val definitions: List<StoreDefinition<*>> = listOf(
        com.latenighthack.social.login.core.service.CredentialStoreDefinitionV1,
        com.latenighthack.social.login.core.service.ChallengeStoreDefinitionV1,
    )
    fun configuration(identity: String, additional: List<StoreDefinition<*>> = emptyList()) =
        definitionDatabaseConfiguration(identity, definitions + additional)
    fun inMemory(identity: String = "LoginStorage-test") =
        Database(configuration(identity), InMemoryStoreDelegate())
}
