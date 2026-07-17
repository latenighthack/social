import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File

// Convention for a feature -api module: a KMP library (via social.kmp-library) plus
// protobuf codegen from a module-local proto/ directory.
plugins {
    id("social.kmp-library")
}

val libs = the<LibrariesForLibs>()

// Proto codegen is driven by protoc directly rather than the com.google.protobuf Gradle
// plugin (which binds to java/android source sets instead of a shared commonMain output).
// protoc is resolved as a pinned artifact; protoc-gen-kt (the ktbuf Kotlin codegen plugin,
// a Go binary) is discovered on PATH (~/go/bin fallback). Install the pinned version:
//   go install latenighthack.com/protoc-gen-kt@v0.0.0-20251214023608-0fa742406fbf
val protocVersion = "4.33.0"

val protocClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        os.contains("mac") || os.contains("darwin") -> "osx"
        os.contains("win") -> "windows"
        else -> "linux"
    }
    val archPart = when (arch) {
        "aarch64", "arm64" -> "aarch_64"
        "x86_64", "amd64" -> "x86_64"
        else -> arch
    }
    "$osPart-$archPart"
}

val protocExecutable: Configuration by configurations.creating
dependencies {
    protocExecutable("com.google.protobuf:protoc:$protocVersion:$protocClassifier@exe")
}

val protocGenKt: File = run {
    val onPath = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .map { File(it, "protoc-gen-kt") }
        .firstOrNull { it.canExecute() }
    onPath ?: File(System.getProperty("user.home"), "go/bin/protoc-gen-kt")
}

// Extra proto source roots to expose on protoc's import path (-I) so this module can
// `import` messages defined in a dependency -api module. Set from a consumer build via
//   extra["protoImportProjects"] = listOf(":social-common-api")
// Files under these roots are parsed to resolve imports but NOT code-generated here (only the
// files passed on the command line are), so the imported type is referenced, never duplicated.
val protoImportDirs = provider {
    @Suppress("UNCHECKED_CAST")
    (project.findProperty("protoImportProjects") as? List<String>).orEmpty()
        .map { project.project(it).layout.projectDirectory.dir("proto").asFile }
}

val generateProto by tasks.registering(Exec::class) {
    group = "build"
    description = "Generate Kotlin protobuf sources via protoc-gen-kt"

    val protoRoot = layout.projectDirectory.dir("proto").asFile
    val protoFiles = fileTree(protoRoot) { include("**/*.proto") }
    val importDirs = protoImportDirs
    val outDir = layout.buildDirectory.dir("generated/ktproto/kotlin")

    inputs.files(protoFiles)
    inputs.files(importDirs.map { dirs -> dirs.map { fileTree(it) { include("**/*.proto") } } })
    inputs.file(protocGenKt)
    outputs.dir(outDir)

    doFirst {
        val protoc = protocExecutable.singleFile.apply { setExecutable(true) }
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        commandLine(
            buildList {
                add(protoc.absolutePath)
                add("--plugin=protoc-gen-kt=${protocGenKt.absolutePath}")
                add("--kt_out=${out.absolutePath}")
                add("-I")
                add(protoRoot.absolutePath)
                importDirs.get().forEach {
                    add("-I")
                    add(it.absolutePath)
                }
                addAll(protoFiles.files.map { it.absolutePath })
            },
        )
    }
}

// social.kmp-library applies the KMP plugin; configure its extension explicitly rather
// than via the `kotlin { }` accessor (not generated for a sibling convention plugin).
extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.getByName("commonMain") {
        // Passing the task provider wires the generateProto dependency into every
        // compilation that reads commonMain, with no manual dependsOn needed.
        kotlin.srcDir(generateProto)
        dependencies {
            // Generated proto/rpc types are part of this module's public API and extend
            // ktbuf supertypes (proto.Enum, proto.Message, rpc.*). Kotlin 2.2 rejects a
            // public supertype coming from a non-exposed dependency, so expose these as `api`.
            api(libs.ktbuf.library)
            api(libs.ktbuf.rpc)
            implementation(libs.coroutines.core)
        }
    }
}
