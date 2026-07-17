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
                // DomainLifecycle (the durable uploader's supertype) + @SocialScope; brings
                // LockersClient (in DomainLifecycle's signature) transitively.
                api(projects.socialRuntime)
                // Durable pending-upload queue.
                api(libs.ktstore.library)
                // Clock for enqueue timestamps.
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.core)
                implementation(libs.assertk)
                implementation(libs.ktstore.library)
                // Real end-to-end transfer-progress test: boot a tiny inline PUT/GET server and
                // transfer over a real ktor client, asserting watchTransfer(url) reports progress.
                implementation(libs.ktbuf.rpc)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.client.cio)
            }
        }
    }
}
