plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(projects.accountDomain)
    implementation(projects.profilesDomain)
    implementation(projects.roomsDomain)
    implementation(projects.messagesDomain)
    implementation(projects.contactsDomain)
    implementation(projects.typingDomain)
    implementation(projects.readReceiptsDomain)
    implementation(projects.remoteContentDomain)
    implementation(libs.kotlin.inject.runtime)
    implementation(libs.ktor.client.cio)
    ksp(libs.kotlin.inject.ksp)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktbuf.test)
    testImplementation(libs.lockers.server.test)
    testImplementation(libs.lockers.server)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
    testImplementation(libs.coroutines.test)
}
