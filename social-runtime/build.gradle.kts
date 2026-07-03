plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // LockersClient appears in DomainLifecycle's public API, so `api`.
                api(libs.lockers.connector)
            }
        }
    }
}

android {
    // Override the convention's derived name (…social.social.runtime) to match the package.
    namespace = "com.latenighthack.social.runtime"
}
