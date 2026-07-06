plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Pure, importable renderer for the messages.v1.Component tree using classic Android
// Views. Reuses :messages-api's generated Kotlin types directly (no second codegen).
android {
    namespace = "com.latenighthack.social.messages.view"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The Component types are part of this library's public API (callers pass a Component).
    api(projects.messagesApi)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}
