plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The generated LoginServiceRpc stub + login.v1 request/response protos; the use-case
                // module speaks these types, so `api`.
                api(projects.loginApi)
                // RpcClient appears in the LoginClient provider signature; the generated stub extends
                // GrpcService in ktbuf-library.
                api(libs.ktbuf.rpc)
                implementation(libs.ktbuf.library)
            }
        }
        val androidMain by getting {
            dependencies {
                // Native Google sign-in: Credential Manager + the Google id credential type.
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services.auth)
                implementation(libs.google.id)
            }
        }
    }
}
