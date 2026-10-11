plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // LoginClient + the login.v1 protos (transitively). Provider use-case modules depend
                // on this module for the shared SignInResult / mapping / BindCurrentAccountUseCase.
                api(projects.loginCoreDomain)
                // AccountManager.restoreAccount / exportIdentity — the orchestration these wrap.
                api(projects.accountDomain)
                // The proto runtime: the result mapping matches on the login.v1 enums, whose ktbuf
                // Enum supertype must be on the classpath (login-core-api keeps it internal).
                implementation(libs.ktbuf.library)
            }
        }
    }
}
