package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.*

/** Account-partitioned local stores; the host owns the shared handle and migration chain. */
object MessagesStorage {
    val legacyDefinitions: List<StoreDefinition<*>> = listOf(
        DeadLetterStoreDefinitionV1, DraftStoreDefinitionV1, MessageStoreDefinitionV1, PendingMessageStoreDefinitionV1)
    val definitions: List<StoreDefinition<*>> = listOf(
        DeadLetterStoreDefinitionV2, DraftStoreDefinitionV2, MessageStoreDefinitionV2, PendingMessageStoreDefinitionV2)

    /** Append to the host's existing version; never reinterpret its historical schema. */
    fun upgrade(previous: DatabaseConfiguration): DatabaseConfiguration {
        legacyDefinitions.forEach { old ->
            val actual = previous.stores.single { it.name == old.storeName }
            val expected = old.declaration
            require(actual.primaryKey.name == expected.primaryKey.name)
            require(actual.keys.map { listOf(it.name, it::class, it.nullable, (it as? StoreKey.CompositeKey)?.names) } ==
                expected.keys.map { listOf(it.name, it::class, it.nullable, (it as? StoreKey.CompositeKey)?.names) })
        }
        val replacements = definitions.associateBy { it.storeName }
        val target = previous.stores.map { replacements[it.name]?.declaration ?: it }
        val migration = DatabaseMigration.configured(previous.version, previous.version + 1, previous.stores, target) {
            for (definition in definitions) {
                @Suppress("UNCHECKED_CAST")
                val typed = definition as StoreDefinition<Any>
                rebuildStore(typed.storeName, typed.declaration) { bytes ->
                    val row = typed.encodeRow(typed.decode(bytes))
                    StoreRow(bytes.copyOf(), row.keys)
                }
            }
        }
        return previous.copy(version = previous.version + 1, stores = target, migrations = previous.migrations + migration)
    }

    fun configuration(identity: String, additional: List<StoreDefinition<*>> = emptyList()) =
        upgrade(definitionDatabaseConfiguration(identity, legacyDefinitions + additional))
    fun inMemory(identity: String = "MessagesStorage-test") =
        Database(configuration(identity), InMemoryStoreDelegate())
}
