plugins {
    id("social.jvm-service")
}

dependencies {
    // Generated LoginServer / LoginServiceRpc + the login.v1 wire and storage record protos.
    implementation(projects.loginApi)
    // The extension seam (ServerExtension / ServerExtensionFactory / GrpcRouteProvider).
    implementation(libs.lockers.server)
    // The extension factory receives the server's shared MeterRegistry.
    implementation(libs.micrometer.core)
    // Apple/Google id-token (JWT) verification against the providers' JWKS.
    implementation(libs.nimbus.jose.jwt)
    // SMTP transport for the magic-link email sender (Jakarta Mail implementation).
    implementation(libs.angus.mail)
    // SendGrid / Twilio REST senders call the provider HTTP APIs over a ktor client.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // Integration tests boot the Login service over an embedded server and drive it end-to-end.
    testImplementation(libs.lockers.server.test)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
}
