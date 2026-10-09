plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.mavenPublish) apply false
}

allprojects {
    group = "com.latenighthack.social"
    // Version is release-vs-SNAPSHOT driven by the CI ref:
    //   v* tag  -> the tag value (release, e.g. 0.1.0)
    //   otherwise (main push / local) -> "<baseVersion>-SNAPSHOT"
    // Bump `baseVersion` in gradle.properties after cutting a release.
    val base = providers.gradleProperty("baseVersion").get()
    val ref = System.getenv("GITHUB_REF").orEmpty()
    version = if (ref.startsWith("refs/tags/v")) {
        System.getenv("GITHUB_REF_NAME").removePrefix("v")
    } else {
        "$base-SNAPSHOT"
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        ignoreFailures = false
        source.setFrom(fileTree("src") { include("**/kotlin/**/*.kt"); exclude("**/*Test/**", "**/test/**") })
        baseline = file("detekt-baseline.xml")
        config.setFrom(rootProject.file("config/detekt.yml"))
        basePath = rootProject.projectDir.path
    }
}

// Portable dashboards and optional rules share the library version but have no runtime feature dependencies.
tasks.register<Zip>("observabilityBundle") {
    group = "distribution"
    description = "Packages modular Grafana dashboards, Prometheus rules and operating notes."
    archiveBaseName.set("social-observability")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("observability") { into("observability") }
}

// CI and local validation use a separately installed, pinned Node runtime. Distribution
// downloads cannot resolve from Maven repositories under PREFER_SETTINGS.
gradle.projectsEvaluated {
    allprojects {
        plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
            extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download = false
        }
    }
}

// Use the exact Kotlin Karma fork archive, avoiding npm's git-package preparation and workspace-link bug.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().versions.karma.version =
        "https://codeload.github.com/Kotlin/karma/tar.gz/239a8fc984584f0d96b1dd750e7a5e2c79da93a6"
}
