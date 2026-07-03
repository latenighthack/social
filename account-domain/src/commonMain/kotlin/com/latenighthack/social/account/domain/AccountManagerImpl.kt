package com.latenighthack.social.account.domain

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

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

    private var cachedKeyPair: Secp256r1KeyPair? = null
    private var pendingKeyPair = CompletableDeferred<Secp256r1KeyPair>()
    private val hasKey = MutableStateFlow(false)

    internal suspend fun sessionKeyPair(): Secp256r1KeyPair {
        cachedKeyPair?.let { return it }
        val record = loadRecord() ?: return pendingKeyPair.await()
        return Secp256r1KeyPair.fromPrivateKey(record.privateKey)!!.also { cachedKeyPair = it }
    }

    internal suspend fun hasSessionKey(): Boolean {
        val present = cachedKeyPair != null || loadRecord() != null
        hasKey.value = present
        return present
    }

    internal suspend fun writeKey(roomId: RoomId, lockerId: LockerId): Secp256r1KeyPair? {
        val authority = RoomKeying.authorityKey(roomId) ?: return null
        val keyPair = sessionKeyPair()
        return if (authority.contentEquals(keyPair.publicKey.encode())) keyPair else null
    }

    /** The session key was (re)generated — mint a fresh one and re-lock the room on reconnect. */
    internal suspend fun regenerateSession() {
        generateKey()
        roomInitialized = false
    }

    internal suspend fun revokeSession() {
        keyValueStore.delete<AccountRecord>(ACCOUNT_RECORD_KEY)
        cachedKeyPair = null
        pendingKeyPair = CompletableDeferred()
        hasKey.value = false
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
        hasKey.value = true
        pendingKeyPair.complete(keyPair)
    }

    private suspend fun loadRecord(): AccountRecord? =
        keyValueStore.get(ACCOUNT_RECORD_KEY, AccountRecord.Companion::fromByteArray)

    private suspend fun accountId(): ByteArray = sessionKeyPair().publicKey.encode()

    private suspend fun privateRoomId(): RoomId = RoomKeying.publicKeyed(accountId())

    // --- session lifecycle ---

    private val _lifecycle = MutableStateFlow<Lifecycle>(Lifecycle.NoAccount)
    override val lifecycle: StateFlow<Lifecycle> get() = _lifecycle

    private var job: Job? = null
    private var everReady = false
    private var roomInitialized = false
    private var lockers: LockersClient? = null

    override suspend fun createAccount(): ByteArray {
        if (!hasSessionKey()) {
            generateKey()
        }
        return accountId()
    }

    override suspend fun restoreAccount(privateKeyBytes: ByteArray): ByteArray {
        if (hasSessionKey()) throw IllegalStateException("an account already exists on this device")
        val keyPair = Secp256r1KeyPair.fromPrivateKey(privateKeyBytes)
            ?: throw IllegalArgumentException("invalid private key")
        val client = lockers ?: error("restoreAccount requires start(lockers) first")

        // Let the connector open a session with this identity WITHOUT committing it: hasKey
        // stays false, so the lifecycle collector won't initialize (and create) the room yet.
        cachedKeyPair = keyPair
        pendingKeyPair.complete(keyPair)
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
        hasKey.value = true
        return keyPair.publicKey.encode()
    }

    override suspend fun signOut() = revokeSession()

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
        if (job?.isActive == true) return
        roomInitialized = false
        job = scope.launch { run(lockers) }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(lockers: LockersClient) {
        val account = lockers.typed(
            AccountKeyspaces.ACCOUNT_STATE, AccountState::toByteArray, AccountState.Companion::fromByteArray,
        )
        hasSessionKey() // seed hasKey from persistence

        combine(hasKey, lockers.isConnected, lockers.fatalError, ::Inputs).collect { (present, connected, fatal) ->
            _lifecycle.value = when {
                fatal != null -> Lifecycle.Fatal(fatal)
                !present -> {
                    roomInitialized = false
                    if (everReady) Lifecycle.SignedOut else Lifecycle.NoAccount
                }
                !connected -> Lifecycle.Connecting
                else -> {
                    if (!roomInitialized) {
                        initializePrivateRoom(lockers, account)
                        roomInitialized = true
                    }
                    everReady = true
                    Lifecycle.Ready(accountId(), privateRoomId())
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

    private data class Inputs(val hasKey: Boolean, val connected: Boolean, val fatal: StreamFatalError?)

    internal companion object {
        const val SCHEMA_VERSION = 1
        const val ACCOUNT_RECORD_KEY = "account_record"
    }
}
