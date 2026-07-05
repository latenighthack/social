plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.debugDomain)
            }
        }
    }
}
