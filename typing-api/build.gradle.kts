plugins {
    id("social.kmp-proto")
}

kotlin { sourceSets { commonMain { dependencies { api(projects.socialCommonApi) } } } }

extra["protoImportProjects"] = listOf(":social-common-api")
