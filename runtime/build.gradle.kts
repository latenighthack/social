plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonTest by getting {
            dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        }
        val commonMain by getting {
            dependencies {
                // LockersClient appears in DomainLifecycle's public API, so `api`.
                api(libs.ktstore.library)
                api(libs.lockers.connector)
                api(projects.socialObservabilityCore)
            }
        }
    }
}

android {
    // Override the convention's derived name (…social.social.runtime) to match the package.
    namespace = "com.latenighthack.social.runtime"
}
