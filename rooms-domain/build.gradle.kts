plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // RoomInfo / RoomId appear in the manager's public API, so `api`.
                api(projects.roomsApi)
                // MyProfilesManager is a constructor dependency (profile keys + ECDH); ProfileId
                // appears in the public API. account-domain comes transitively via profiles-domain.
                api(projects.profilesDomain)
                // DomainLifecycle (a manager supertype) + @SocialScope appear in the public API.
                api(projects.socialRuntime)
                api(libs.lockers.api)
                api(libs.lockers.connector)
                api(libs.ktcrypto.library)
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
