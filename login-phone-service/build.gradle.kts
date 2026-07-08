plugins {
    id("social.jvm-service")
}

dependencies {
    // The SmsSender interface + the LoginProviderFactory SPI + LoginHandler.
    implementation(projects.loginCoreService)
    // TwilioSmsSender calls the Twilio REST API over a shared ktor client.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
}
