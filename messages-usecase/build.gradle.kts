plugins {
    id("social.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.messagesDomain)
                // ReadReceiptsManager backs markRead and the readBy annotation on watched messages.
                api(projects.readReceiptsDomain)
                // The durable uploader backs the photo-attach use case (RemoteContentUploader + Upload).
                implementation(projects.remoteContentDomain)
            }
        }
    }
}
