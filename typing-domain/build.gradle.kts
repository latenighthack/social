plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // TypingPayload appears in the manager's public API path, so `api`.
                api(projects.typingApi)
                // RoomsManager (room list + localProfile) is a constructor dependency; RoomId appears
                // in the public API.
                api(projects.roomsDomain)
                // ProfileId appears in the manager's public API (watchTyping returns a Set<ProfileId>).
                api(projects.profilesApi)
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
