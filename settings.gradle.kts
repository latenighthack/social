rootProject.name = "social"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Group source directories by feature while preserving existing project paths, names,
// type-safe accessors and publication coordinates. Parent directories are not modules.

// Foundation for the client-side kotlin-inject wiring: the @SocialScope graph scope and the
// DomainLifecycle interface every feature manager implements. Depended on by the -domain modules.
include(":social-observability-core", ":social-observability-client", ":social-observability-server")
project(":social-observability-server").projectDir = file("observability/server")
project(":social-observability-core").projectDir = file("observability/core")
project(":social-observability-client").projectDir = file("observability/client")
include(":social-runtime") // plugins { id("social.kmp-library") }
project(":social-runtime").projectDir = file("runtime")

// Shared primitives reused across features. social-common-api owns the standard SignedContent
// (public-key-signed bytes) proto; social-common-domain owns its one sign/verify helper.
include(":social-common-api")    // plugins { id("social.kmp-proto") }   — common/v1/signing.proto
project(":social-common-api").projectDir = file("common/api")
include(":social-common-domain") // plugins { id("social.kmp-library") } — sign/verify over SignedContent
project(":social-common-domain").projectDir = file("common/domain")

pluginManagement {
    // Convention plugins (social.kmp-library / social.kmp-proto / social.jvm-service)
    // live in the build-logic included build.
    includeBuild("build-logic")
    repositories {
        // Global Maven Local is intentionally excluded; use -PfhWorkspace for local development.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Global Maven Local is intentionally excluded; use -PfhWorkspace for local development.
        google()
        mavenCentral()
    }
}

// Feature modules, one vertical slice per feature. account has no -service module:
// its public-key private-room lock is enforced by the lockers server's LockVerifier.
include(":account-api")      // plugins { id("social.kmp-proto") }   — proto/ + generated Kotlin
project(":account-api").projectDir = file("account/api")
include(":account-domain")   // plugins { id("social.kmp-library") } — identity + private-room session mgmt
project(":account-domain").projectDir = file("account/domain")
include(":account-usecase")  // plugins { id("social.kmp-library") } — CreateAccount / SignOut use cases
project(":account-usecase").projectDir = file("account/usecase")

// profiles: identities presented publicly, separate from the account id. Profile key pairs
// live as locked lockers in the account room; each profile has its own key-locked room.
include(":profiles-api")     // plugins { id("social.kmp-proto") }   — ProfileId / Profile / ProfileSource / LocalProfile
project(":profiles-api").projectDir = file("profiles/api")
include(":profiles-domain")  // plugins { id("social.kmp-library") } — My/ProfilesManager over the lockers client
project(":profiles-domain").projectDir = file("profiles/domain")
include(":profiles-usecase") // plugins { id("social.kmp-library") } — display-name watch / update use cases
project(":profiles-usecase").projectDir = file("profiles/usecase")

// rooms: shared, mutable, multi-participant spaces (not chat). Rendezvous rooms meet at the
// sha256 of an ECDH between two profiles; groups keep a shared-key-locked membership list.
// Group access is granted via server-mediated, revocable invite codes (rooms-service); rendezvous
// still bootstraps through a sealed invite in the invitee's open profile inbox keyspace.
include(":rooms-api")        // plugins { id("social.kmp-proto") }   — Invite / RoomInfo / Member / RoomRecord + Join service
project(":rooms-api").projectDir = file("rooms/api")
include(":rooms-domain")     // plugins { id("social.kmp-library") } — RoomsManager over the lockers client + JoinClient
project(":rooms-domain").projectDir = file("rooms/domain")
include(":rooms-usecase")    // plugins { id("social.kmp-library") } — create / invite-code / join / leave / info use cases
project(":rooms-usecase").projectDir = file("rooms/usecase")
include(":rooms-service")    // plugins { id("social.jvm-service") } — JoinService: holds room keys, mints revocable join codes
project(":rooms-service").projectDir = file("rooms/service")

// messages: a chat layer over rooms. Each message is a SignedContent locker (signed by the sender's
// profile key) in a member room's messages keyspace, observed and cached locally per room.
include(":messages-api")     // plugins { id("social.kmp-proto") }   — MessageId / MessagePayload / LocalMessage
project(":messages-api").projectDir = file("messages/api")
include(":messages-domain")  // plugins { id("social.kmp-library") } — MessagesManager over the lockers client
project(":messages-domain").projectDir = file("messages/domain")
include(":messages-usecase") // plugins { id("social.kmp-library") } — send / watch message use cases
project(":messages-usecase").projectDir = file("messages/usecase")

// contacts: the user's friend and block lists. One ContactRecord locker per contact, keyed by
// profile id, in the user's own account room (authorized by the account key, so no key source of
// its own). Friend and block are independent — a profile may be both.
include(":contacts-api")     // plugins { id("social.kmp-proto") }   — ContactRecord(friend, block)
project(":contacts-api").projectDir = file("contacts/api")
include(":contacts-domain")  // plugins { id("social.kmp-library") } — ContactsManager over the account room
project(":contacts-domain").projectDir = file("contacts/domain")
include(":contacts-usecase") // plugins { id("social.kmp-library") } — add / block / unfriend / unblock / watch
project(":contacts-usecase").projectDir = file("contacts/usecase")

// login: custodial multi-method authentication. Binds a login method (Apple, Google, email
// magic-link, phone OTP) to an account id + account private key held server-side, and returns the
// key after the method is re-proven — enabling multi-device sign-in and account recovery. Touches no
// lockers; a standalone gRPC service on the lockers server (ServerExtension) plus clients that hand
// the recovered key to the existing AccountManager. Split into a shared core + à-la-carte providers:
// an app/server pulls only login-<provider>-* for the methods it wants. The wire stays one Login
// service in login-core-api; providers split the client code and (via a ServiceLoader SPI) the
// service handlers, so the core service enables whatever provider service modules are on the classpath.
include(":login-core-api")     // id("social.kmp-proto")   — the shared Login gRPC service + storage records
project(":login-core-api").projectDir = file("login/core/api")
include(":login-core-domain")  // id("social.kmp-library") — LoginClient (shared transport) + result mapping
project(":login-core-domain").projectDir = file("login/core/domain")
include(":login-core-usecase") // id("social.kmp-library") — SignInResult + BindCurrentAccountUseCase
project(":login-core-usecase").projectDir = file("login/core/usecase")
include(":login-core-service") // id("social.jvm-service") — custodial stores/crypto/bind + handler SPI
project(":login-core-service").projectDir = file("login/core/service")

include(":login-apple-domain")  // id("social.kmp-library") — AppleSignInClient + iOS native token
project(":login-apple-domain").projectDir = file("login/apple/domain")
include(":login-apple-usecase") // id("social.kmp-library") — AuthenticateWithAppleUseCase
project(":login-apple-usecase").projectDir = file("login/apple/usecase")
include(":login-apple-service") // id("social.jvm-service") — Apple id-token verifier (SPI)
project(":login-apple-service").projectDir = file("login/apple/service")

include(":login-google-domain")  // id("social.kmp-library") — GoogleSignInClient + Android native token
project(":login-google-domain").projectDir = file("login/google/domain")
include(":login-google-usecase") // id("social.kmp-library") — AuthenticateWithGoogleUseCase
project(":login-google-usecase").projectDir = file("login/google/usecase")
include(":login-google-service") // id("social.jvm-service") — Google id-token verifier (SPI)
project(":login-google-service").projectDir = file("login/google/service")

include(":login-email-usecase")  // id("social.kmp-library") — request / complete magic-link use cases
project(":login-email-usecase").projectDir = file("login/email/usecase")
include(":login-email-service")  // id("social.jvm-service") — SMTP / SendGrid / console senders (SPI)
project(":login-email-service").projectDir = file("login/email/service")

include(":login-phone-usecase")  // id("social.kmp-library") — request / verify OTP use cases
project(":login-phone-usecase").projectDir = file("login/phone/usecase")
include(":login-phone-service")  // id("social.jvm-service") — Twilio / console SMS senders (SPI)
project(":login-phone-service").projectDir = file("login/phone/service")

// remote-content: a server-side upload/hosting add-on (the first -service). A client makes one gRPC
// call to get a content id + upload/download URLs, PUTs raw bytes to the upload URL, and later fetches
// them (with the create-time mime type) from the download URL. Attaches to the lockers server via the
// ServerExtension seam; storage is behind a pluggable ContentStore (filesystem + in-memory).
include(":remote-content-api")     // plugins { id("social.kmp-proto") }   — RemoteContent service + ContentId
project(":remote-content-api").projectDir = file("remote-content/api")
include(":remote-content-domain")  // plugins { id("social.kmp-library") } — RemoteContentClient (gRPC call + HTTP up/download)
project(":remote-content-domain").projectDir = file("remote-content/domain")
include(":remote-content-service") // plugins { id("social.jvm-service") } — ContentStore + gRPC impl + HTTP routes + extension
project(":remote-content-service").projectDir = file("remote-content/service")

// avatars: upload a chosen photo and bind its download URL onto the user's profile or a room they
// belong to. Orchestration only — the durable uploader lives in remote-content-domain and the URL
// rides an avatar disclosure on the existing Profile / RoomInfo.
include(":avatars-usecase")  // plugins { id("social.kmp-library") } — set my / room avatar use cases
project(":avatars-usecase").projectDir = file("avatars/usecase")

// typing: "someone is typing" indicators for rooms. Each member has a placeholder locker (keyed by
// profile id) in a room's typing keyspace; the start/stop signal rides as an ephemeral event attached
// to an update. Sends are debounced; receivers time a member out 15s after their last signal.
include(":typing-api")     // plugins { id("social.kmp-proto") }   — TypingPayload
project(":typing-api").projectDir = file("typing/api")
include(":typing-domain")  // plugins { id("social.kmp-library") } — TypingManager over the lockers client
project(":typing-domain").projectDir = file("typing/domain")
include(":typing-usecase") // plugins { id("social.kmp-library") } — set / watch typing use cases
project(":typing-usecase").projectDir = file("typing/usecase")

// read receipts: per-room "read up to here" pointers. Each member has one ReadReceipt locker (keyed by
// profile id) in a room's read-receipts keyspace, whose body is the message id of the most recently
// read message. markRead advances the pointer to the room's latest message; the reader list is folded
// into the messages use cases (each watched Message carries the profiles that have read it).
include(":read-receipts-api")    // plugins { id("social.kmp-proto") }   — ReadReceipt
project(":read-receipts-api").projectDir = file("read-receipts/api")
include(":read-receipts-domain") // plugins { id("social.kmp-library") } — ReadReceiptsManager over the lockers client
project(":read-receipts-domain").projectDir = file("read-receipts/domain")

// debug: introspect the raw contents of lockers. Lists every locker the client knows about (from the
// connector's local cache), grouped by keyspace, decoding each value into a readable hierarchical map
// via app-registered per-keyspace codecs (each `Type.fromByteArray(bytes).toValue()`). No -api module —
// the output is a plain Map<String, Any?> assembled from existing feature types.
include(":debug-domain")   // plugins { id("social.kmp-library") } — DebugManager + LockerCodecs registry
project(":debug-domain").projectDir = file("debug/domain")
include(":debug-usecase")  // plugins { id("social.kmp-library") } — watch locker dump use case
project(":debug-usecase").projectDir = file("debug/usecase")

// messages-view: pure, importable platform-native renderers for the messages.v1.Component tree.
// Classic Android Views (here), plus a UIKit Swift package and a React package under messages/view/
// (both generated from the shared protos via buf — not Gradle modules). The Android library reuses
// :messages-api's generated Kotlin types directly. The demo app renders the shared .pb fixtures and
// hosts the Roborazzi screenshot task used for cross-platform parity. See messages/view/README.md.
include(":messages-view-android")      // plugins { id("com.android.library") }     — Component renderer (Android Views)
project(":messages-view-android").projectDir = file("messages/view/android")
include(":messages-view-demo-android") // plugins { id("com.android.application") } — showcase + Roborazzi screenshots
project(":messages-view-demo-android").projectDir = file("messages/view/demo/android")

// Explicit isolated library development; release builds use published dependencies.
apply(from = "gradle/fh-workspace.settings.gradle")

// Compiled documentation/DI contract, intentionally not published.
include(":bootstrap-example")
