plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // ContactRecord is the stored payload; used internally only.
                implementation(projects.contactsApi)
                // ProfileId keys every contact and appears in the manager's public API, so `api`.
                api(projects.profilesApi)
                // AccountManager is a constructor + providers-interface dependency (the account room
                // is where contacts live). Its Lifecycle.Ready.privateRoom is read at start.
                api(projects.accountDomain)
                // DomainLifecycle (a manager supertype) + @SocialScope appear in the public API.
                api(projects.socialRuntime)
                api(libs.lockers.api)
                api(libs.lockers.connector)
                implementation(libs.kotlinx.datetime)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.assertk)
                implementation(libs.ktbuf.library)
                implementation(libs.ktbuf.rpc)
                implementation(libs.ktbuf.server)
                implementation(libs.ktbuf.test)
                implementation(libs.ktstore.library)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.lockers.server)
                implementation(libs.lockers.server.test)
            }
        }
    }
}
