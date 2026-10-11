plugins {
    id("social.kmp-proto")
}

android {
    // Override the convention's derived name (…social.social.common.api) to match the package.
    namespace = "com.latenighthack.social.common.api"
}
