import org.gradle.accessors.dm.LibrariesForLibs

// Convention for a feature -service module: a JVM-only backend that plugs into the
// lockers server — a LockerAgentRegistry and/or extra gRPC services + ktstore stores.
// Wired with kotlin-inject (KSP), mirroring the lockers server module.
// NOTE: not published (no mavenPublishing) — the server plug-in mechanism is deferred.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp")
}

val libs = the<LibrariesForLibs>()

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlin.inject.runtime)
    implementation(libs.ktbuf.library)
    implementation(libs.ktbuf.rpc)
    implementation(libs.ktbuf.server)
    implementation(libs.coroutines.core)
    implementation(libs.ktstore.library)

    ksp(libs.kotlin.inject.ksp)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktbuf.test)
    testImplementation(libs.assertk)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
