plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The generated LoginServiceRpc stub + login.v1 request/response protos; the provider
                // use-case modules speak these types, so `api`.
                api(projects.loginCoreApi)
                // RpcClient appears in the LoginClient provider signature; the generated stub extends
                // GrpcService in ktbuf-library.
                api(libs.ktbuf.rpc)
                implementation(libs.ktbuf.library)
            }
        }
    }
}
