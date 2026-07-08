rootProject.name = "social"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Foundation for the client-side kotlin-inject wiring: the @SocialScope graph scope and the
// DomainLifecycle interface every feature manager implements. Depended on by the -domain modules.
include(":social-runtime") // plugins { id("social.kmp-library") }

// Shared primitives reused across features. social-common-api owns the standard SignedContent
// (public-key-signed bytes) proto; social-common-domain owns its one sign/verify helper.
include(":social-common-api")    // plugins { id("social.kmp-proto") }   — common/v1/signing.proto
include(":social-common-domain") // plugins { id("social.kmp-library") } — sign/verify over SignedContent

pluginManagement {
    // Convention plugins (social.kmp-library / social.kmp-proto / social.jvm-service)
    // live in the build-logic included build.
    includeBuild("build-logic")
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // mavenLocal first so the peer "lockers" repo's SNAPSHOTs (published via
        // `./gradlew publishToMavenLocal` in ../kitkit) resolve during local dev.
        mavenLocal()
        google()
        mavenCentral()
    }
}

// Feature modules, one vertical slice per feature. account has no -service module:
// its public-key private-room lock is enforced by the lockers server's LockVerifier.
include(":account-api")      // plugins { id("social.kmp-proto") }   — proto/ + generated Kotlin
include(":account-domain")   // plugins { id("social.kmp-library") } — identity + private-room session mgmt
include(":account-usecase")  // plugins { id("social.kmp-library") } — CreateAccount / SignOut use cases

// profiles: identities presented publicly, separate from the account id. Profile key pairs
// live as locked lockers in the account room; each profile has its own key-locked room.
include(":profiles-api")     // plugins { id("social.kmp-proto") }   — ProfileId / Profile / ProfileSource / LocalProfile
include(":profiles-domain")  // plugins { id("social.kmp-library") } — My/ProfilesManager over the lockers client
include(":profiles-usecase") // plugins { id("social.kmp-library") } — display-name watch / update use cases

// rooms: shared, mutable, multi-participant spaces (not chat). Rendezvous rooms meet at the
// sha256 of an ECDH between two profiles; groups keep a shared-key-locked membership list.
// Group access is granted via server-mediated, revocable invite codes (rooms-service); rendezvous
// still bootstraps through a sealed invite in the invitee's open profile inbox keyspace.
include(":rooms-api")        // plugins { id("social.kmp-proto") }   — Invite / RoomInfo / Member / RoomRecord + Join service
include(":rooms-domain")     // plugins { id("social.kmp-library") } — RoomsManager over the lockers client + JoinClient
include(":rooms-usecase")    // plugins { id("social.kmp-library") } — create / invite-code / join / leave / info use cases
include(":rooms-service")    // plugins { id("social.jvm-service") } — JoinService: holds room keys, mints revocable join codes

// messages: a chat layer over rooms. Each message is a SignedContent locker (signed by the sender's
// profile key) in a member room's messages keyspace, observed and cached locally per room.
include(":messages-api")     // plugins { id("social.kmp-proto") }   — MessageId / MessagePayload / LocalMessage
include(":messages-domain")  // plugins { id("social.kmp-library") } — MessagesManager over the lockers client
include(":messages-usecase") // plugins { id("social.kmp-library") } — send / watch message use cases

// contacts: the user's friend and block lists. One ContactRecord locker per contact, keyed by
// profile id, in the user's own account room (authorized by the account key, so no key source of
// its own). Friend and block are independent — a profile may be both.
include(":contacts-api")     // plugins { id("social.kmp-proto") }   — ContactRecord(friend, block)
include(":contacts-domain")  // plugins { id("social.kmp-library") } — ContactsManager over the account room
include(":contacts-usecase") // plugins { id("social.kmp-library") } — add / block / unfriend / unblock / watch

// login: custodial multi-method authentication. Binds a login method (Apple, Google, email
// magic-link, phone OTP) to an account id + account private key held server-side, and returns the
// key after the method is re-proven — enabling multi-device sign-in and account recovery. Unlike
// every other feature it touches no lockers; it is a standalone gRPC service on the lockers server
// (ServerExtension) plus clients that hand the recovered key to the existing AccountManager.
include(":login-api")      // plugins { id("social.kmp-proto") }   — Login service + storage records
include(":login-domain")   // plugins { id("social.kmp-library") } — LoginClient (gRPC) + native sign-in
include(":login-usecase")  // plugins { id("social.kmp-library") } — authenticate / bind use cases
include(":login-service")  // plugins { id("social.jvm-service") } — custodial store + gRPC impl + extension

// remote-content: a server-side upload/hosting add-on (the first -service). A client makes one gRPC
// call to get a content id + upload/download URLs, PUTs raw bytes to the upload URL, and later fetches
// them (with the create-time mime type) from the download URL. Attaches to the lockers server via the
// ServerExtension seam; storage is behind a pluggable ContentStore (filesystem + in-memory).
include(":remote-content-api")     // plugins { id("social.kmp-proto") }   — RemoteContent service + ContentId
include(":remote-content-domain")  // plugins { id("social.kmp-library") } — RemoteContentClient (gRPC call + HTTP up/download)
include(":remote-content-service") // plugins { id("social.jvm-service") } — ContentStore + gRPC impl + HTTP routes + extension

// avatars: upload a chosen photo and bind its download URL onto the user's profile or a room they
// belong to. Orchestration only — the durable uploader lives in remote-content-domain and the URL
// rides an avatar disclosure on the existing Profile / RoomInfo.
include(":avatars-usecase")  // plugins { id("social.kmp-library") } — set my / room avatar use cases

// typing: "someone is typing" indicators for rooms. Each member has a placeholder locker (keyed by
// profile id) in a room's typing keyspace; the start/stop signal rides as an ephemeral event attached
// to an update. Sends are debounced; receivers time a member out 15s after their last signal.
include(":typing-api")     // plugins { id("social.kmp-proto") }   — TypingPayload
include(":typing-domain")  // plugins { id("social.kmp-library") } — TypingManager over the lockers client
include(":typing-usecase") // plugins { id("social.kmp-library") } — set / watch typing use cases

// read receipts: per-room "read up to here" pointers. Each member has one ReadReceipt locker (keyed by
// profile id) in a room's read-receipts keyspace, whose body is the message id of the most recently
// read message. markRead advances the pointer to the room's latest message; the reader list is folded
// into the messages use cases (each watched Message carries the profiles that have read it).
include(":read-receipts-api")    // plugins { id("social.kmp-proto") }   — ReadReceipt
include(":read-receipts-domain") // plugins { id("social.kmp-library") } — ReadReceiptsManager over the lockers client

// debug: introspect the raw contents of lockers. Lists every locker the client knows about (from the
// connector's local cache), grouped by keyspace, decoding each value into a readable hierarchical map
// via app-registered per-keyspace codecs (each `Type.fromByteArray(bytes).toValue()`). No -api module —
// the output is a plain Map<String, Any?> assembled from existing feature types.
include(":debug-domain")   // plugins { id("social.kmp-library") } — DebugManager + LockerCodecs registry
include(":debug-usecase")  // plugins { id("social.kmp-library") } — watch locker dump use case

// messages-view: pure, importable platform-native renderers for the messages.v1.Component tree.
// Classic Android Views (here), plus a UIKit Swift package and a React package under messages-view/
// (both generated from the shared protos via buf — not Gradle modules). The Android library reuses
// :messages-api's generated Kotlin types directly. The demo app renders the shared .pb fixtures and
// hosts the Roborazzi screenshot task used for cross-platform parity. See messages-view/README.md.
include(":messages-view-android")      // plugins { id("com.android.library") }     — Component renderer (Android Views)
project(":messages-view-android").projectDir = file("messages-view/android")
include(":messages-view-demo-android") // plugins { id("com.android.application") } — showcase + Roborazzi screenshots
project(":messages-view-demo-android").projectDir = file("messages-view/demo/android")
