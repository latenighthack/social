package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.LocalDraft
import com.latenighthack.social.runtime.DomainLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps each room's single draft in memory and mirrors it into a persistent [DraftStore]. Purely
 * local: it never touches lockers (the [start] lockers argument is ignored), so a draft never leaves
 * the device but survives a restart. Resumable: [start] hydrates from the store, [stop] cancels and
 * leaves the manager reusable.
 */
class DraftsManagerImpl(
    private val delegate: StoreDelegate,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DraftsManager, DomainLifecycle {

    private val store = DraftStore(delegate)

    private val _drafts = MutableStateFlow<Map<RoomId, Draft>>(emptyMap())

    private var job: Job? = null
    private var storesInitialized = false
    // Completes once the store has been created and loaded — gates all store access.
    private val ready = CompletableDeferred<Unit>()

    override fun start(lockers: LockersClient) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!storesInitialized) {
                store.prepare()
                delegate.createStores()
                storesInitialized = true
            }

            _drafts.value = buildMap {
                store.getAllDrafts().forEach { local ->
                    put(RoomId(rawValue = local.roomId), local.draft ?: Draft { })
                }
            }
            if (!ready.isCompleted) ready.complete(Unit)
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override suspend fun save(roomId: RoomId, draft: Draft) {
        ready.await()
        _drafts.value = _drafts.value + (roomId to draft)
        store.saveDraft(LocalDraft(roomId = roomId.rawValue, draft = draft))
    }

    override suspend fun clear(roomId: RoomId) {
        ready.await()
        _drafts.value = _drafts.value - roomId
        store.removeDraft(roomId)
    }

    override fun watchDraft(roomId: RoomId): Flow<Draft?> =
        _drafts.map { it[roomId] }.distinctUntilChanged()
}
