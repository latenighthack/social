plugins {
    id("social.kmp-proto")
}

// messages/v1/model.proto imports common/v1/signing.proto (SignedContent). Expose that module's
// proto/ dir on protoc's import path and depend on its generated Kotlin (SignedContent appears in
// LocalMessage's public API, so `api`).
extra["protoImportProjects"] = listOf(":social-common-api")

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.socialCommonApi)
            }
        }
    }
}
