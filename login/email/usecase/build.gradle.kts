plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // ChallengeResult / SignInResult + mappings; brings LoginClient, AccountManager and
                // the login.v1 protos transitively. Email has no native client, so no -domain.
                api(projects.loginCoreUsecase)
                // The proto runtime: builds Start/CompleteEmailLinkRequest and reads the result enum.
                implementation(libs.ktbuf.library)
            }
        }
    }
}
