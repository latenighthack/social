plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // LoginClient + the native sign-in clients (+ login.v1 protos transitively).
                api(projects.loginDomain)
                // AccountManager.createAccount / restoreAccount / exportIdentity — the orchestration
                // these use cases wrap.
                api(projects.accountDomain)
                // The proto runtime: these use cases match on the login.v1 result/provider enums,
                // whose ktbuf Enum supertype must be on the classpath (login-api keeps it internal).
                implementation(libs.ktbuf.library)
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
                // Boot the real login gRPC service in-process for the bind→restore round-trip.
                implementation(projects.loginService)
            }
        }
    }
}
