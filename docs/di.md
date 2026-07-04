# Wiring the feature modules with kotlin-inject

Every client-side `-domain` and `-usecase` module ships a kotlin-inject **`@Provides` interface** —
`AccountProviders`, `AccountUseCaseProviders`, `ProfilesProviders`, `ProfilesUseCaseProviders`,
`RoomsProviders`, `RoomsUseCaseProviders`. The feature classes themselves stay annotation-free; all
the bindings live in these interfaces. A consuming app builds one `@Component` that implements the
interfaces it wants, and kotlin-inject resolves the manager graph, the use cases, and the key-source
fallback chain by type.

The modules depend only on `kotlin-inject-runtime` (for the annotations). **Only the app runs the
KSP compiler** — you do not need KSP configured in the feature modules.

## What the app must supply

The interfaces reference a few types they don't provide; the app binds them:

- `KeyValueStore` — the device-local store `AccountManagerImpl` persists the identity key in.
- `StoreDelegate` — the store the observed-profiles cache (`ProfilesManagerImpl`), the messages
  cache, and the remote-content upload queue are created from.
- `HttpClient` — a ktor client (with a platform engine) the remote-content transport uses to PUT/GET
  raw content bytes.
- The client's own `rpcClient`, `appVersion`, and its *own* `StoreDelegate` + `KeyValueStore` — the
  connector persists its session state separately from the managers, so these are distinct instances
  (keep them out of the graph as plain fields to avoid ambiguous `KeyValueStore`/`StoreDelegate`
  bindings).
- The **top of the lock-key chain**. Each feature provides its concrete key source
  (`AccountKeySource` → `ProfileKeySource` → `RoomsKeySource`), but which one is the client's
  `LockKeySource` depends on the feature set, so the app picks it. `AuthenticationKeySource` is
  always the account key and is already bound by `AccountProviders`.

## The two-phase bootstrap

The managers deliberately take the `LockersClient` at `start(lockers)`, not in their constructor —
and the client is built *from* the managers' key sources. So construction and start are two phases:

1. `SocialComponent::class.create(...)` — builds every manager, key source, and use case.
2. Get the `LockersClient` (provided from the key sources) and `start` every manager over it.

Each manager implements `DomainLifecycle` and is contributed `@IntoSet`, so the app starts and stops
them all through one `Set<DomainLifecycle>` without naming each.

## Example component

```kotlin
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import com.latenighthack.social.account.domain.AccountProviders
import com.latenighthack.social.account.usecase.AccountUseCaseProviders
import com.latenighthack.social.profiles.domain.ProfilesProviders
import com.latenighthack.social.profiles.usecase.ProfilesUseCaseProviders
import com.latenighthack.social.rooms.domain.RoomsProviders
import com.latenighthack.social.rooms.domain.RoomsKeySource
import com.latenighthack.social.rooms.usecase.RoomsUseCaseProviders
import com.latenighthack.social.remotecontent.domain.RemoteContentProviders
import com.latenighthack.social.avatars.usecase.AvatarsUseCaseProviders
import com.latenighthack.social.avatars.usecase.SetMyAvatarUseCase
import io.ktor.client.HttpClient
import com.latenighthack.lockers.connector.AuthenticationKeySource
import com.latenighthack.lockers.connector.LockKeySource
import com.latenighthack.lockers.connector.LockersClient
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@SocialScope
@Component
abstract class SocialComponent(
    // Manager-owned stores enter the graph.
    @get:Provides protected val keyValueStore: KeyValueStore,
    @get:Provides protected val storeDelegate: StoreDelegate,
    // The ktor client the remote-content transport uploads/downloads bytes with.
    @get:Provides protected val httpClient: HttpClient,
    // The gRPC channel. Shared: the connector uses it, and the remote-content client makes its
    // CreateContent call over it, so unlike the stores it enters the graph (no ambiguity — one RpcClient).
    @get:Provides protected val rpcClient: RpcClient,
    // Connector-owned stores: plain fields, NOT @Provides (distinct from the manager stores).
    private val appVersion: Version,
    private val connectorStoreDelegate: StoreDelegate,
    private val connectorKeyValueStore: KeyValueStore,
) : AccountProviders,
    AccountUseCaseProviders,
    ProfilesProviders,
    ProfilesUseCaseProviders,
    RoomsProviders,
    RoomsUseCaseProviders,
    RemoteContentProviders,
    AvatarsUseCaseProviders {

    // This app includes rooms, so the room key source is the top of the lock chain.
    @Provides
    fun lockKeySource(roomsKeySource: RoomsKeySource): LockKeySource = roomsKeySource

    @Provides
    @SocialScope
    fun lockersClient(auth: AuthenticationKeySource, lock: LockKeySource): LockersClient =
        LockersClient.create(
            rpcClient = rpcClient,
            storeDelegate = connectorStoreDelegate,
            keyValueStore = connectorKeyValueStore,
            keySource = auth,
            appVersion = appVersion,
            lockKeySource = lock,
        )

    // Use cases the UI layer collects.
    abstract val createAccount: CreateAccountUseCase
    abstract val watchRooms: WatchRoomsUseCase
    abstract val createGroup: CreateGroupUseCase
    abstract val setMyAvatar: SetMyAvatarUseCase
    // …the rest as needed.

    // Everything to drive over the client. The remote-content uploader is contributed @IntoSet like
    // every manager, so it starts and stops with this same set (its start ignores the client).
    abstract val client: LockersClient
    abstract val lifecycles: Set<DomainLifecycle>
}

// Bootstrap:
val component = SocialComponent::class.create(
    keyValueStore, storeDelegate, httpClient, rpcClient, appVersion, connectorStoreDelegate, connectorKeyValueStore,
)
val client = component.client
component.lifecycles.forEach { it.start(client) }
// …on shutdown: component.lifecycles.forEach { it.stop() }
```

## Dropping a feature

Include only the providers you want. An app without rooms implements just the account and profiles
interfaces and binds `lockKeySource(profileKeySource: ProfileKeySource): LockKeySource = profileKeySource`
— the chain, use cases, and lifecycle set shrink to match. No feature binds an unqualified
`LockKeySource`, so there is never a duplicate-binding clash; the app always names the top.
