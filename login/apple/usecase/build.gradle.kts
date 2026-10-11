plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // AppleSignInClient (the native token collaborator).
                api(projects.loginAppleDomain)
                // SignInResult + toSignInResult + BindCurrentAccountUseCase; brings LoginClient,
                // AccountManager, and the login.v1 protos transitively (all api-exposed).
                api(projects.loginCoreUsecase)
                // The proto runtime: builds AuthenticateSocialRequest and matches the Provider enum,
                // whose ktbuf Enum supertype must be on the classpath.
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
                // Boot the real login core service in-process for the bind→restore round-trip.
                implementation(projects.loginCoreService)
            }
        }
    }
}
