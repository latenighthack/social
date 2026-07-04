plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.messagesDomain)
                // The durable uploader backs the photo-attach use case (RemoteContentUploader + Upload).
                implementation(projects.remoteContentDomain)
            }
        }
    }
}
