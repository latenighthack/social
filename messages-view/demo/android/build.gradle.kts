plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.takahirom.roborazzi")
}

// Showcase app that renders the shared .pb fixtures, and hosts the Roborazzi screenshot
// test used for cross-platform parity. Not published.
android {
    namespace = "com.latenighthack.social.messages.view.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.latenighthack.social.messages.view.demo"
        minSdk = 24
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.messagesViewAndroid)
    // Component.fromByteArray is an inline ktbuf call, so the decoder needs ktbuf on the classpath.
    implementation(libs.ktbuf.library)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.junit.rule)
}
