plugins {
    id("social.jvm-service")
}

dependencies {
    // The SocialTokenVerifier interface + the LoginProviderFactory SPI + LoginHandler.
    implementation(projects.loginCoreService)
    // The Provider enum this factory tags its handler with.
    implementation(projects.loginCoreApi)
    // Apple id-token (JWT) verification against Apple's JWKS.
    implementation(libs.nimbus.jose.jwt)

    // The light SPI test builds a LoginProviderContext, which needs an HttpClient.
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
}
