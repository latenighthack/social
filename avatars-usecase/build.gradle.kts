plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The durable uploader that mints the URL and transfers the bytes.
                implementation(projects.remoteContentDomain)
                // MyProfilesManager + Profile avatar disclosure builder; ProfileId reaches the API.
                api(projects.profilesDomain)
                // RoomsManager + RoomInfo avatar disclosure builder; RoomId appears in the API.
                api(projects.roomsDomain)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.core)
                implementation(libs.assertk)
            }
        }
    }
}
