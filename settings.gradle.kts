rootProject.name = "social"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Foundation for the client-side kotlin-inject wiring: the @SocialScope graph scope and the
// DomainLifecycle interface every feature manager implements. Depended on by the -domain modules.
include(":social-runtime") // plugins { id("social.kmp-library") }

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
