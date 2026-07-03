plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // MessageId / MessagePayload appear in the manager's public API, so `api`.
                api(projects.messagesApi)
                // RoomsManager (room list + markUpdated + localProfile) is a constructor dependency;
                // RoomId appears in the public API.
                api(projects.roomsDomain)
                // MyProfilesManager signs each message; ProfileId reaches the API transitively.
                api(projects.profilesDomain)
                // The shared SignedContent verify helper backs message-signature checking.
                implementation(projects.socialCommonDomain)
                // DomainLifecycle (a manager supertype) + @SocialScope appear in the public API.
                api(projects.socialRuntime)
                api(libs.lockers.api)
                api(libs.lockers.connector)
                api(libs.ktcrypto.library)
                api(libs.ktstore.library)
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
