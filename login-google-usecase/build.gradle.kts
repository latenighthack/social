plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val jvmTest by getting {
            dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        }
        val commonMain by getting {
            dependencies {
                // GoogleSignInClient (the native token collaborator).
                api(projects.loginGoogleDomain)
                // SignInResult + toSignInResult; brings LoginClient, AccountManager, login.v1 protos.
                api(projects.loginCoreUsecase)
                // The proto runtime: builds AuthenticateSocialRequest and matches the Provider enum.
                implementation(libs.ktbuf.library)
            }
        }
    }
}
