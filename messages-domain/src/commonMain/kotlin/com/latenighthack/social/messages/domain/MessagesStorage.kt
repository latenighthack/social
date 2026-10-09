package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.*

/** Complete schemas must be composed before any shared handle is opened. */
object MessagesStorage {
    val definitions: List<StoreDefinition<*>> = listOf(
        com.latenighthack.social.messages.domain.DeadLetterStoreDefinitionV1,
        com.latenighthack.social.messages.domain.DraftStoreDefinitionV1,
        com.latenighthack.social.messages.domain.MessageStoreDefinitionV1,
        com.latenighthack.social.messages.domain.PendingMessageStoreDefinitionV1,
    )
    fun configuration(identity: String, additional: List<StoreDefinition<*>> = emptyList()) =
        definitionDatabaseConfiguration(identity, definitions + additional)
    fun inMemory(identity: String = "MessagesStorage-test") =
        Database(configuration(identity), InMemoryStoreDelegate())
}
