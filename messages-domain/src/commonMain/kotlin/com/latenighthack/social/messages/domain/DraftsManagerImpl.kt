package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.Database
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.DraftAttachment
import com.latenighthack.social.messages.v1.LocalDraft
import com.latenighthack.social.runtime.*
import com.latenighthack.social.messages.v1.copy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.latenighthack.social.runtime.TaskHealth
import com.latenighthack.social.runtime.recoverTask
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps each room's single draft in memory and mirrors it into a persistent [DraftStore]. Purely
 * local: it never touches lockers (the [start] lockers argument is ignored), so a draft never leaves
 * the device but survives a restart. Resumable: [start] hydrates from the store, [stop] cancels and
 * leaves the manager reusable.
 */
class DraftsManagerImpl(
    private val database: Database,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val session: AccountSession? = null,
) : DraftsManager, DomainLifecycle {

    private val store = DraftStore(database)
    private val mutex = Mutex()

    private var loadedOwner: String? = null
    private val _drafts = MutableStateFlow<Map<RoomId, Draft>>(emptyMap())

    override val taskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
    private val runner = com.latenighthack.social.runtime.ManagerRunner(scope)
    // Completes once the store has been loaded — gates all store access.
    private var ready = CompletableDeferred<Unit>()

    override suspend fun prepare() {
        store.prepare()
    }

    override fun start(lockers: LockersClient) {
        runner.start { recoverTask(taskHealth) {
            if (ready.isCancelled) ready = CompletableDeferred()
            try {
            session.ownerChanges().collectLatest { owner ->
                mutex.withLock {
                    _drafts.value = emptyMap()
                    loadedOwner = owner
                    if (owner != null) {
                        val rows = store.getAllDrafts()
                        for (row in rows) if (session != null && row.ownerAccountId.isEmpty()) {
                            if (session.owns(row.ownerAccountId)) store.saveDraft(row.copy(ownerAccountId = owner))
                            else store.removeDraft(RoomId(rawValue = row.roomId))
                        }
                        _drafts.value = rows.filter { session.owns(it.ownerAccountId) }.associate {
                            RoomId(rawValue = it.roomId) to (it.draft ?: Draft { })
                        }
                    }
                    if (!ready.isCompleted) ready.complete(Unit)
                }
                kotlinx.coroutines.awaitCancellation()
            }
            } catch (failure: Exception) {
                if (!ready.isCompleted) ready.completeExceptionally(failure)
                throw failure
            }
        } }
    }


    override fun stop() {
        runner.stop()
    }

    override suspend fun stopAndJoin() {
        runner.stopAndJoin()
    }

    override suspend fun setText(roomId: RoomId, text: String) = mutate(roomId) { current ->
        Draft { this.text = text; attachments = current.attachments }
    }

    override suspend fun addAttachment(roomId: RoomId, attachment: DraftAttachment) = mutate(roomId) { current ->
        Draft { text = current.text; attachments = current.attachments + attachment }
    }

    override suspend fun removeAttachment(roomId: RoomId, contentId: ByteArray) = mutate(roomId) { current ->
        Draft { text = current.text; attachments = current.attachments.filterNot { it.contentId.contentEquals(contentId) } }
    }

    // Read-modify-write of [roomId]'s draft: applies [transform] to the current draft (or an empty one)
    // and mirrors the result into memory and the store, so each field can be edited without clobbering
    // the others.
    private suspend fun mutate(roomId: RoomId, transform: (Draft) -> Draft) {
        val owner = session.currentOwner()
        ready.await()
        mutex.withLock {
            val updated = transform(if (loadedOwner == owner) _drafts.value[roomId] ?: Draft { } else Draft { })
            store.saveDraft(LocalDraft(roomId = roomId.rawValue, draft = updated, ownerAccountId = owner))
            check(session.currentOwner() == owner) { "account changed" }
            _drafts.value = (if (loadedOwner == owner) _drafts.value else emptyMap()) + (roomId to updated)
            loadedOwner = owner
        }
    }

    override suspend fun clear(roomId: RoomId) {
        val owner = session.currentOwner()
        ready.await()
        mutex.withLock {
            if (loadedOwner != owner) return
            store.removeDraft(roomId)
            _drafts.value = _drafts.value - roomId
        }
    }

    override suspend fun clearIfUnchanged(roomId: RoomId, sent: Draft) {
        val owner = session.currentOwner()
        ready.await()
        mutex.withLock {
            if (loadedOwner != owner || _drafts.value[roomId] != sent) return
            store.removeDraft(roomId)
            _drafts.value = _drafts.value - roomId
        }
    }

    override fun watchDraft(roomId: RoomId): Flow<Draft?> =
        combine(_drafts, session.ownerChanges()) { drafts, owner -> if (owner != null && owner == loadedOwner) drafts[roomId] else null }.distinctUntilChanged()
}
