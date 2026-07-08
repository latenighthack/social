plugins {
    id("social.jvm-service")
}

dependencies {
    // The EmailSender interface + the LoginProviderFactory SPI + LoginHandler.
    implementation(projects.loginCoreService)
    // SMTP transport for SmtpEmailSender (Jakarta Mail implementation).
    implementation(libs.angus.mail)
    // SendGridEmailSender calls the SendGrid REST API over a shared ktor client.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
}
