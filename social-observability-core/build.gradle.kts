plugins { id("social.kmp-library") }
kotlin.sourceSets {
    val commonMain by getting { dependencies {
        api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    } }
    val commonTest by getting { dependencies {
        implementation(kotlin("test"))
        implementation(libs.coroutines.test)
    } }
}
