plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // MessagesManager (latest-message lookup) is a constructor + providers dependency;
                // MessageId appears in the manager's public API (watchReadReceipts + markRead), so `api`.
                api(projects.messagesApi)
                api(projects.messagesDomain)
                // RoomsManager (localProfile) is a constructor dependency; RoomId appears in the public API.
                api(projects.roomsDomain)
                // ProfileId keys every receipt and appears in the manager's public API.
                api(projects.profilesApi)
                // DomainLifecycle (a manager supertype) + @SocialScope appear in the public API.
                api(projects.socialRuntime)
                // ReadReceipt is the stored payload; used internally only.
                implementation(projects.readReceiptsApi)
                api(libs.lockers.api)
                api(libs.lockers.connector)
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
