plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Generated RemoteContent stubs + messages are part of this module's public API.
                api(projects.remoteContentApi)
                // RpcClient and HttpClient appear in the public Providers signature.
                api(libs.ktbuf.rpc)
                api(libs.ktor.client.core)
                // GrpcService supertype of the generated RpcClient stub.
                implementation(libs.ktbuf.library)
            }
        }
    }
}
