// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.account.domain

import kotlinx.coroutines.flow.asStateFlow

import com.latenighthack.ktcrypto.AES
import com.latenighthack.ktcrypto.digest
import com.latenighthack.ktcrypto.AESSymmetricKey
import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.decodeKey
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.fromPrivateKey
import com.latenighthack.ktcrypto.generate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.lockers.common.v1.LockScope
import com.latenighthack.lockers.common.v1.LockScopeKind
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.StreamFatalError
import com.latenighthack.lockers.connector.TypedLockerClient
import com.latenighthack.social.account.domain.AccountManager.Lifecycle
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.account.v1.AccountRecord
import com.latenighthack.social.account.v1.AccountState
import com.latenighthack.social.account.v1.copy
import com.latenighthack.social.account.v1.fromByteArray
import com.latenighthack.social.account.v1.toByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.latenighthack.social.runtime.TaskHealth
import com.latenighthack.social.runtime.recoverTask
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * The account. It owns the identity key (persisted as a typed [AccountRecord] in its own
 * [keyValueStore], never synced), drives the session lifecycle over the client passed to
 * [start], locks/loads the private room, and mirrors the session id into the synced
 * [AccountState]. Its key material is handed to the client through an [AccountKeySource]
 * wrapping this manager (the manager itself does not implement the connector interfaces).
 */
class AccountManagerImpl(
    private val keyValueStore: KeyValueStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AccountManager, DomainLifecycle {

    // --- key material (owned here; AccountKeySource forwards to these) ---

    private val identityMutex = Mutex()
    private val _owner = MutableStateFlow<String?>(null)
    override val owner: StateFlow<String?> = _owner.asStateFlow()
    private val _generation = MutableStateFlow(0L)
    override val generation: StateFlow<Long> = _generation.asStateFlow()
    override var mayAdoptLegacyStorage: Boolean = false
        private set

    private suspend fun publishOwner(key: Secp256r1KeyPair) {
        _owner.value = key.publicKey.encode().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    private var cachedKeyPair: Secp256r1KeyPair? = null
    private var tentativeKeyPair: Secp256r1KeyPair? = null
    private var pendingKeyPair = CompletableDeferred<Secp256r1KeyPair>()
    private val hasKey = MutableStateFlow(false)

    internal suspend fun sessionKeyPair(): Secp256r1KeyPair {
        cachedKeyPair?.let { return it }
        tentativeKeyPair?.let { return it }
        val record = loadRecord() ?: return pendingKeyPair.await()
        return Secp256r1KeyPair.fromPrivateKey(record.privateKey)!!.also { cachedKeyPair = it }
    }

    internal suspend fun hasSessionKey(): Boolean {
        val present = cachedKeyPair != null || loadRecord() != null
        hasKey.value = present
        if (present) publishOwner(sessionKeyPair())
        return present
    }

    internal suspend fun writeKey(roomId: RoomId, lockerId: LockerId): Secp256r1KeyPair? {
        val authority = RoomKeying.authorityKey(roomId) ?: return null
        val keyPair = sessionKeyPair()
        return if (authority.contentEquals(keyPair.publicKey.encode())) keyPair else null
    }

    /** The session key was (re)generated — mint a fresh one and re-lock the room on reconnect. */
    internal suspend fun regenerateSession() = identityMutex.withLock {
        if (!hasSessionKey()) generateKey()
        roomInitialized = false
    }

    internal suspend fun revokeSession() = identityMutex.withLock {
        keyValueStore.delete<AccountRecord>(ACCOUNT_RECORD_KEY)
        cachedKeyPair = null
        tentativeKeyPair = null
        pendingKeyPair = CompletableDeferred()
        hasKey.value = false
        mayAdoptLegacyStorage = false
        _generation.value += 1
        _owner.value = null
        roomInitialized = false
        _lifecycle.value = if (everReady) Lifecycle.SignedOut else Lifecycle.NoAccount
    }

    private suspend fun generateKey() {
        val keyPair = Secp256r1KeyPair.generate()
        keyValueStore.save(
            ACCOUNT_RECORD_KEY,
            AccountRecord {
                privateKey = keyPair.privateKey.encode()
                createdAtMillis = Clock.System.now().toEpochMilliseconds()
            },
            AccountRecord::toByteArray,
        )
        cachedKeyPair = keyPair
        mayAdoptLegacyStorage = false
        publishOwner(keyPair)
        hasKey.value = true
        pendingKeyPair.complete(keyPair)
        everReady = true
        _lifecycle.value = Lifecycle.Ready(keyPair.publicKey.encode(), RoomKeying.publicKeyed(keyPair.publicKey.encode()))
    }

    private suspend fun loadRecord(): AccountRecord? =
        keyValueStore.get(ACCOUNT_RECORD_KEY, AccountRecord.Companion::fromByteArray).also {
            if (it != null && cachedKeyPair == null && tentativeKeyPair == null && _owner.value == null) mayAdoptLegacyStorage = true
        }

    private suspend fun accountId(): ByteArray = sessionKeyPair().publicKey.encode()

    private suspend fun privateRoomId(): RoomId = RoomKeying.publicKeyed(accountId())

    // --- session lifecycle ---

    private val _lifecycle = MutableStateFlow<Lifecycle>(Lifecycle.NoAccount)
    override val lifecycle: StateFlow<Lifecycle> = _lifecycle.asStateFlow()

    private val mutableTaskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
    override val taskHealth = mutableTaskHealth.asStateFlow()
    private val runner = com.latenighthack.social.runtime.ManagerRunner(scope)
    private var everReady = false
    private var roomInitialized = false
    private var roomInitializedOwner: String? = null
    private val lockers: LockersClient? get() = runner.token as? LockersClient

    override suspend fun createAccount(): ByteArray = identityMutex.withLock {
        if (!hasSessionKey()) {
            generateKey()
        }
        accountId()
    }

    override suspend fun localAccountRoom(): RoomId? =
        if (hasSessionKey()) privateRoomId() else null

    override suspend fun restoreAccount(privateKeyBytes: ByteArray): ByteArray = identityMutex.withLock {
        if (hasSessionKey()) throw IllegalStateException("an account already exists on this device")
        val keyPair = Secp256r1KeyPair.fromPrivateKey(privateKeyBytes)
            ?: throw IllegalArgumentException("invalid private key")
        val client = lockers ?: error("restoreAccount requires start(lockers) first")

        // Let the connector open a session with this identity WITHOUT committing it: hasKey
        // stays false, so the lifecycle collector won't initialize (and create) the room yet.
        tentativeKeyPair = keyPair
        pendingKeyPair.complete(keyPair)
        var committed = false
        try {
        client.awaitConnected()

        val account = client.typed(
            AccountKeyspaces.ACCOUNT_STATE, AccountState::toByteArray, AccountState.Companion::fromByteArray,
        )
        val roomId = RoomKeying.publicKeyed(keyPair.publicKey.encode())

        // Authoritative existence check — getLocker fetches from the server on a cold cache
        // and performs no write.
        if (account.getLocker(roomId, AccountKeyspaces.ACCOUNT_STATE_LOCKER) == null) {
            cachedKeyPair = null
            pendingKeyPair = CompletableDeferred()
            throw NoAccountToRestoreException()
        }

        // Commit the supplied identity; the collector drives → Ready and initializePrivateRoom
        // loads (does not recreate) the existing AccountState.
        keyValueStore.save(
            ACCOUNT_RECORD_KEY,
            AccountRecord {
                privateKey = keyPair.privateKey.encode()
                createdAtMillis = Clock.System.now().toEpochMilliseconds()
            },
            AccountRecord::toByteArray,
        )
        cachedKeyPair = keyPair
        tentativeKeyPair = null
        committed = true
        mayAdoptLegacyStorage = false
        publishOwner(keyPair)
        hasKey.value = true
        everReady = true
        _lifecycle.value = Lifecycle.Ready(keyPair.publicKey.encode(), roomId)
        keyPair.publicKey.encode()
        } finally {
            if (!committed) withContext(NonCancellable) {
                tentativeKeyPair = null
                pendingKeyPair = CompletableDeferred()
                hasKey.value = false
            }
        }
    }

    override suspend fun signOut() = revokeSession()

    override suspend fun exportIdentity(): AccountManager.Identity {
        if (!hasSessionKey()) throw IllegalStateException("no account to export")
        val keyPair = sessionKeyPair()
        return AccountManager.Identity(keyPair.publicKey.encode(), keyPair.privateKey.encode())
    }

    private suspend fun secretKey(context: String): AESSymmetricKey {
        require(context.isNotBlank())
        check(hasSessionKey()) { "account has no committed identity" }
        return AESSymmetricKey.decodeKey(SHA256.digest(
            "social/account-secret/v1/$context".encodeToByteArray() + sessionKeyPair().privateKey.encode(),
        ))
    }

    override suspend fun protectSecret(context: String, plaintext: ByteArray): ByteArray =
        AES.GCM.encrypt(secretKey(context), plaintext)

    override suspend fun unprotectSecret(context: String, ciphertext: ByteArray): ByteArray =
        AES.GCM.decrypt(secretKey(context), ciphertext)

    override fun start(lockers: LockersClient) {
        runner.start(lockers) {
        roomInitialized = false
             recoverTask(mutableTaskHealth) { run(lockers) } }
    }

    override fun stop() {
        runner.stop()
    }

    override suspend fun stopAndJoin() {
        runner.stopAndJoin()
    }

    private suspend fun run(lockers: LockersClient) {
        val account = lockers.typed(
            AccountKeyspaces.ACCOUNT_STATE, AccountState::toByteArray, AccountState.Companion::fromByteArray,
        )
        identityMutex.withLock { hasSessionKey() } // seed the committed identity from persistence

        combine(_owner, lockers.isConnected, lockers.fatalError) { owner, connected, fatal ->
            Triple(owner, connected, fatal)
        }.collectLatest { (owner, connected, fatal) ->
            // combine can deliver an older snapshot after a synchronous identity transition.
            // Never let that snapshot overwrite the state published by create/signOut.
            if (_owner.value != owner) return@collectLatest
            if (owner == null) {
                roomInitialized = false
                _lifecycle.value = if (everReady) Lifecycle.SignedOut else Lifecycle.NoAccount
                return@collectLatest
            }
            val key = cachedKeyPair ?: return@collectLatest
            val id = key.publicKey.encode()
            if (_owner.value != owner) return@collectLatest
            everReady = true
            _lifecycle.value = if (fatal != null) Lifecycle.Fatal(fatal)
                else Lifecycle.Ready(id, RoomKeying.publicKeyed(id))
            if (fatal == null && connected && (!roomInitialized || roomInitializedOwner != owner)) {
                val initialized = try {
                    initializePrivateRoom(lockers, account)
                    true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (_owner.value == owner) {
                    roomInitialized = initialized
                    if (initialized) roomInitializedOwner = owner
                }
            }
        }
    }

    private suspend fun initializePrivateRoom(lockers: LockersClient, account: TypedLockerClient<AccountState>) {
        val roomId = privateRoomId()
        val keyPair = sessionKeyPair()

        account.subscribeToRoom(roomId)
        // Lock the whole room to this identity. On a public-keyed room the root lock must be
        // signed by the room authority (self); an already-present lock is a no-op on reconnect.
        account.lockLocker(
            roomId,
            LockScope(kind = LockScopeKind.LOCK_SCOPE_ROOM),
            keyPair,
            parentKeyPair = keyPair,
        )

        if (account.getLocker(roomId, AccountKeyspaces.ACCOUNT_STATE_LOCKER) == null) {
            account.updateLocker(roomId, AccountKeyspaces.ACCOUNT_STATE_LOCKER) {
                it.copy {
                    createdAtMillis = Clock.System.now().toEpochMilliseconds()
                    schemaVersion = SCHEMA_VERSION
                }
            }
        }

        // Mirror the session id (read cleanly from the client) into the synced AccountState.
        lockers.sessionId.value?.let { sid ->
            account.updateLocker(roomId, AccountKeyspaces.ACCOUNT_STATE_LOCKER) {
                it.copy { sessionId = sid.rawValue }
            }
        }
    }


    internal companion object {
        const val SCHEMA_VERSION = 1
        const val ACCOUNT_RECORD_KEY = "account_record"
    }
}
