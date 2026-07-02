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

// Feature modules are added here as they land, one vertical slice per feature, e.g.:
//   include(":account-api")      // plugins { id("social.kmp-proto") }   — proto/ + generated Kotlin
//   include(":account-domain")   // plugins { id("social.kmp-library") } — repository over lockers-connector
//   include(":account-usecase")  // plugins { id("social.kmp-library") } — use cases over -domain
//   include(":account-service")  // plugins { id("social.jvm-service") } — JVM backend (agent / gRPC + ktstore)
