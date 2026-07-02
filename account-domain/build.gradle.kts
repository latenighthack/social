plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.accountApi)
                // Exposed in account-domain's public API (identity supertypes, RoomId,
                // LockersClient, StreamFatalError, KeyValueStore ctor params), so `api`.
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
