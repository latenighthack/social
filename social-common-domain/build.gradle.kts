plugins {
    id("social.kmp-library")
}

android {
    // Override the convention's derived name (…social.social.common.domain) to match the package.
    namespace = "com.latenighthack.social.common.domain"
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // SignedContent is the return/parameter type of the public sign/verify API, so `api`.
                api(projects.socialCommonApi)
                api(libs.ktcrypto.library)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.assertk)
                implementation(libs.ktbuf.library)
                implementation(libs.ktbuf.rpc)
            }
        }
    }
}
