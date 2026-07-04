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
// Invites are sealed and dropped in the invitee's open profile inbox keyspace.
include(":rooms-api")        // plugins { id("social.kmp-proto") }   — SealedEnvelope / Invite / RoomInfo / Member / RoomRecord
include(":rooms-domain")     // plugins { id("social.kmp-library") } — RoomsManager over the lockers client
include(":rooms-usecase")    // plugins { id("social.kmp-library") } — create / invite / leave / info use cases

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

// remote-content: a server-side upload/hosting add-on (the first -service). A client makes one gRPC
// call to get a content id + upload/download URLs, PUTs raw bytes to the upload URL, and later fetches
// them (with the create-time mime type) from the download URL. Attaches to the lockers server via the
// ServerExtension seam; storage is behind a pluggable ContentStore (filesystem + in-memory).
include(":remote-content-api")     // plugins { id("social.kmp-proto") }   — RemoteContent service + ContentId
include(":remote-content-domain")  // plugins { id("social.kmp-library") } — RemoteContentClient (gRPC call + HTTP up/download)
include(":remote-content-service") // plugins { id("social.jvm-service") } — ContentStore + gRPC impl + HTTP routes + extension
