plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val androidMain by getting {
            dependencies {
                // Native Google sign-in: Credential Manager + the Google id credential type.
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services.auth)
                implementation(libs.google.id)
            }
        }
    }
}
