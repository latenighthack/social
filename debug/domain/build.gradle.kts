plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // DomainLifecycle (a manager supertype) + @SocialScope appear in the public API.
                api(projects.socialRuntime)
                // LockerKeyspace / LockerId / RoomId appear in the codec registry's public API.
                api(libs.lockers.api)
                // LockersClient + LockerClient.LockerUpdate reach the public API (start / enumeration).
                api(libs.lockers.connector)
                // toBase64String() for rendering raw bytes when no codec is registered.
                implementation(libs.ktbuf.library)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.assertk)
                // A real generated social proto (AccountState) to exercise the codec's toValue() path.
                implementation(projects.accountApi)
                // Secp256r1KeyPair for the test's bare AuthenticationKeySource.
                implementation(libs.ktcrypto.library)
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
