plugins {
    id("social.jvm-service")
}

dependencies {
    // JoinServer / JoinServiceRpc + the Invite proto (SealedEnvelope comes transitively via
    // social-common-api).
    implementation(projects.roomsApi)
    // Sealing.seal — the ECIES the server uses to scope each grant to the joiner's profile key.
    implementation(projects.socialCommonDomain)
    // RoomKeying.publicKeyed — verifies an uploaded key really belongs to the claimed room.
    implementation(libs.lockers.api)
    // The extension seam (ServerExtension / GrpcRouteProvider) lives in the lockers server.
    implementation(libs.lockers.server)
    // Secp256r1KeyPair.fromPrivateKey for the key↔room membership check.
    implementation(libs.ktcrypto.library)
    // The extension factory receives the server's shared MeterRegistry.
    implementation(libs.micrometer.core)

    // Integration test boots the Join service over an embedded server and drives it end-to-end.
    testImplementation(libs.lockers.server.test)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
}
