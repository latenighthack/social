rootProject.name = "social"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
