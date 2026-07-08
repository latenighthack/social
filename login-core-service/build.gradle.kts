plugins {
    id("social.jvm-service")
}

dependencies {
    // Generated LoginServer / LoginServiceRpc + the login.v1 wire and storage record protos.
    implementation(projects.loginCoreApi)
    // The extension seam (ServerExtension / ServerExtensionFactory / GrpcRouteProvider).
    implementation(libs.lockers.server)
    // The extension factory receives the server's shared MeterRegistry.
    implementation(libs.micrometer.core)
    // HttpClient appears in the LoginProviderContext (REST senders use it); the factory builds one.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // Integration tests boot the Login service over an embedded server and drive it end-to-end.
    testImplementation(libs.lockers.server.test)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
}
