plugins {
    id("social.jvm-service")
}

dependencies {
    implementation(projects.remoteContentApi)
    // The extension seam (ServerExtension / GrpcRouteProvider) lives in the lockers server.
    implementation(libs.lockers.server)
    // Raw HTTP upload/download routes.
    implementation(libs.ktor.server.core)
    // The extension factory receives the server's shared MeterRegistry.
    implementation(libs.micrometer.core)

    // Integration test boots the service over an embedded server and drives it end-to-end.
    testImplementation(libs.lockers.server.test)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
}
