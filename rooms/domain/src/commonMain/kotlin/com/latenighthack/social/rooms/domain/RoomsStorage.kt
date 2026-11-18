package com.latenighthack.social.rooms.domain

import com.latenighthack.ktstore.*

/** Complete schemas must be composed before any shared handle is opened. */
object RoomsStorage {
    val definitions: List<StoreDefinition<*>> = listOf(PendingRoomStoreDefinitionV1)

    /**
     * Adds the rooms stores to a host configuration that already exists on devices, as one new
     * schema version that creates them. A host must use this rather than adding [definitions] to an
     * already-shipped configuration: an unchanged version with a different schema fails to open.
     * Append to the host's existing version; never reinterpret its historical schema.
     */
    fun upgrade(previous: DatabaseConfiguration): DatabaseConfiguration {
        val names = definitions.map { it.storeName }.toSet()
        require(previous.stores.none { it.name in names }) { "rooms stores are already part of this configuration" }
        val target = previous.stores + definitions.map { it.declaration }
        val migration = DatabaseMigration.configured(previous.version, previous.version + 1, previous.stores, target) {
            for (definition in definitions) createStore(definition.declaration)
        }
        return previous.copy(version = previous.version + 1, stores = target, migrations = previous.migrations + migration)
    }

    fun configuration(identity: String, additional: List<StoreDefinition<*>> = emptyList()) =
        definitionDatabaseConfiguration(identity, definitions + additional)
    fun inMemory(identity: String = "RoomsStorage-test") =
        Database(configuration(identity), InMemoryStoreDelegate())
}
