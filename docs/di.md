# Wiring and lifetime ownership

Use one configured ktstore `Database` for the connector, caches, drafts and durable queues.
Compose `ConnectorStorage.definitions`, `ProfilesStorage.definitions`,
`MessagesStorage.definitions`, `RemoteContentStorage.definitions` and host definitions
before opening it. `ConnectorStorage.configuration(name, additionalDefinitions)` builds
this configuration for a new installation. Keep account identity and connector session
`KeyValueStore` handles distinct. Neither manager nor connector shutdown closes the host database.

The [compiled component](../bootstrap-example/src/main/kotlin/com/latenighthack/social/example/SocialComponent.kt)
implements the provider interfaces, supplies `Database`, identity `KeyValueStore`, RPC and
HTTP clients, and chooses `RoomsKeySource` as the top `LockKeySource`. Account providers bind
`AccountSession` automatically. The shared `SocialTaskScope` owns all manager jobs; override
its provider to use an application-owned parent if needed. Only the consuming component runs
kotlin-inject KSP. The example is compiled and exercised by `:bootstrap-example:test`.

The [bootstrap functions](../bootstrap-example/src/main/kotlin/com/latenighthack/social/example/SocialBootstrap.kt)
show the required sequence:

1. Host composes the complete schema and opens the database.
2. Construct the component and call every lifecycle's suspending `prepare()`.
3. Create the suspending `LockersClient.create(...)` outside `@Provides`, passing the same database.
4. Start every lifecycle with that client. Collect `taskHealth` for retries and failures.
5. On shutdown, request `stop()` for all lifecycles, then await every `stopAndJoin()`.
6. Close the connector and cancel the graph scope. Host then closes its HTTP client and database.

`start` is idempotent for the same client. Await shutdown before replacing a client. Flow collectors
belong to their callers; cancel UI collections with the screen lifetime. Local drafts, pending
messages and uploads belong to the committed account; sign-out immediately withdraws access.
Persisted credentials may adopt legacy ownerless rows, but a new identity never inherits them.
Background work retries within its owned coroutine tree; cancellation propagates.

An app without rooms chooses `ProfileKeySource` as its top lock source and implements only the
provider interfaces required by its features. Contacts, receipts and typing use synced lockers;
they do not introduce separate local databases.

# Server configuration

Compose `LoginStorage.definitions` and `RoomsServiceStorage.definitions` into the lockers host
schema. Factories receive that shared database and never open or close it. An existing configured
V3 installation must apply `RoomsServiceStorage.upgrade(previousConfiguration)` to add the invite
code table in a consecutive migration; changing definitions under an existing version is rejected.

Production requires stable login custody and room invite master keys, configured OIDC audiences,
and single-use nonce enforcement. `ROOMS_MASTER_KEY` is base64 for 32 random bytes; custodial login
uses its documented key-id configuration for rotation. Development-only construction requires an
explicit development flag. Persist nonce challenges and encrypted invite codes in the host database.
See [storage](storage.md) and [protocol/keyspaces](keyspaces.md).

# Security boundaries

Account-owned profile and room private keys are encrypted before synchronization. Profile signatures
bind message authors, typing, read receipts and direct invites to their room and protocol label.
A group member holds shared room authority, so membership is not an administrator hierarchy. The
current protocol does not provide end-to-end message or attachment confidentiality: content bytes,
public profiles and traffic metadata retain their documented server visibility. Invite codes grant
membership and must be treated as capabilities.

Uploads use an independent expiring capability, immutable publication, a 16 MiB limit and bounded
admission. File hosting defaults to a 2 GiB/10,000-content quota. Active HTML/SVG are downloads with
restrictive headers. HTTP content sharing still requires a host policy appropriate for its users.
Uploads stop automatic retries after eight attempts or a permanent rejection and retain bytes with
an observable `UploadStatus.Failed`. Hosts expose explicit retry/discard actions; an expired upload
capability requires reattaching content and using the new download URL.

Synchronization encryption does not encrypt the entire local database. Hosts protect the account
identity `KeyValueStore`, draft/cache/upload bytes and database files using their platform storage
policy (including OS credential storage and disk encryption).

# Validation and dependency development

`scripts/verify.sh` runs enforced Detekt, JVM/server regressions, Android tests and compilation,
Kotlin/JS tests and compilation, and Apple tests and compilation. CI also runs web and UIKit renderer
regressions and makes publication depend on validation. Detekt's checked-in baseline comes from the
pre-review tree; it records historical findings rather than accepting new violations.

Released builds resolve published dependencies without global Maven Local. Paired Fullhouse work
uses `./fh deps resolve` and `./fh deps publish --library social`, with explicit library paths in the
ignored `.fh/workspace.json`. Publish prerequisites first; never change bytes under an existing
version. External publication and application release remain separate operations.
