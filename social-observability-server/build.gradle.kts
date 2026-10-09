plugins { id("social.jvm-service") }
dependencies {
    api(projects.socialObservabilityCore)
    api(libs.micrometer.core)
    api(libs.ktor.server.core)
    api(libs.ktor.client.core)
    api("io.opentelemetry:opentelemetry-api:1.45.0")
    implementation("io.opentelemetry:opentelemetry-extension-kotlin:1.45.0")
    implementation(libs.ktor.client.cio)
    implementation("org.slf4j:slf4j-api:2.0.16")
    testImplementation(libs.micrometer.registry.prometheus)
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
    testImplementation("io.ktor:ktor-client-mock:3.0.2")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.45.0")
}

// Real local parent-server fixture for Grafana/Loki/Tempo verification; never shipped in the library jar.
dependencies {
    testImplementation(projects.socialObservabilityClient)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.client.cio)
}
tasks.register<JavaExec>("observabilitySmokeServer") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.latenighthack.social.observability.server.SocialSmokeServerKt")
}
