plugins { id("social.kmp-library") }
kotlin.sourceSets {
    val commonMain by getting { dependencies {
        api(projects.socialObservabilityCore)
        api(libs.ktor.client.core)
        api(libs.ktbuf.rpc)
        api(libs.ktbuf.library)
        implementation(libs.ktcrypto.library)
    } }
    val commonTest by getting { dependencies {
        implementation(kotlin("test"))
        implementation(libs.coroutines.test)
        implementation("io.ktor:ktor-client-mock:3.0.2")
    } }
}
