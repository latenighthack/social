package com.latenighthack.social.runtime

import com.latenighthack.ktstore.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One database page at a time, without keeping a transaction open across suspension or I/O. */
fun <T> storePages(database: Database, definition: StoreDefinition<T>, index: TypedIndex<T, ByteArray>,
    lower: ByteArray? = null, upper: ByteArray? = null, pageSize: Int = 128): Flow<List<T>> = flow {
    var after: LocalContinuation? = null
    do {
        val page = database.transaction(setOf(definition.storeName), TransactionMode.READ_ONLY) {
            query(definition.storeName, index.query(pageSize, lower, upper, after = after))
        }
        @Suppress("UNCHECKED_CAST")
        emit(page.records.map { if (it is ByteArray) definition.decode(it) else it as T })
        after = page.continuation
    } while (after != null)
}
