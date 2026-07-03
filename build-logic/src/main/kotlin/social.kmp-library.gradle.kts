import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.accessors.dm.LibrariesForLibs

// Base convention for a Kotlin Multiplatform library published to Maven Central.
// Used by the client-side feature modules: -domain and -usecase.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

val libs = the<LibrariesForLibs>()

kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget { publishLibraryVariants("release") }
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.coroutines.core)
                // Every -domain/-usecase module ships a kotlin-inject @Provides interface; the
                // consuming app's @Component (which runs the KSP compiler) needs the annotations
                // on the compile classpath, so expose the runtime as `api`.
                api(libs.kotlin.inject.runtime)
            }
        }
    }
}

android {
    // e.g. project "account-domain" -> com.latenighthack.social.account.domain
    namespace = "com.latenighthack.social." + project.name.replace('-', '.')
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(artifactId = project.name)
    pom {
        name.set(project.name)
        description.set("Late Night Hack social — ${project.name} (Kotlin Multiplatform).")
    }
}
