import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.accessors.dm.LibrariesForLibs

// Convention for a feature -service module: a JVM-only backend that plugs into the
// lockers server — a LockerAgentRegistry and/or extra gRPC services + ktstore stores.
// Wired with kotlin-inject (KSP), mirroring the lockers server module.
// Published so downstream server deployments (e.g. gwb) can mount the extensions
// from mavenLocal via the ServiceLoader plug-in mechanism.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp")
    id("com.vanniktech.maven.publish")
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

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(artifactId = project.name)
    pom {
        name.set(project.name)
        description.set("Late Night Hack social — ${project.name} (JVM server extension).")
    }
}
