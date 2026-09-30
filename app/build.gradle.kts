import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.github.ardaulas.earshot.app"
    compileSdk = 36
    // Compile against the car API stub; at run time the library exists only on Android Automotive.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "io.github.ardaulas.earshot"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/** Copies models/manifest.json into the app's assets, so the app checks the same pinned hashes as the script. */
abstract class CopyModelManifest : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        manifest.get().asFile.copyTo(outputDir.file("models.json").get().asFile, overwrite = true)
    }
}

val copyModelManifest =
    tasks.register<CopyModelManifest>("copyModelManifest") {
        manifest.set(rootProject.layout.projectDirectory.file("models/manifest.json"))
        outputDir.set(layout.buildDirectory.dir("generated/modelManifest"))
    }

/**
 * SR-20: runs scripts/check_manifest.py on the merged manifest (after every library's manifest is merged
 * in). It fails on any permission outside an explicit allowlist, in every declaration form
 * (uses-permission, uses-permission-sdk-23, uses-permission-sdk-m), and on any exported component other
 * than the launcher activity (TH-2, TH-6). Negative fixtures: scripts/test_check_manifest.py.
 */
abstract class CheckMergedManifest : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    abstract val checker: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun check() {
        val result =
            exec.exec {
                commandLine("python3", checker.get().asFile.path, mergedManifest.get().asFile.path, report.get().asFile.path)
                isIgnoreExitValue = true
            }
        if (result.exitValue != 0) {
            throw GradleException("Merged manifest check failed:\n" + report.get().asFile.readText())
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyModelManifest, CopyModelManifest::outputDir)
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check =
            tasks.register<CheckMergedManifest>("verify${name}MergedManifest") {
                mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
                checker.set(rootProject.layout.projectDirectory.file("scripts/check_manifest.py"))
                report.set(layout.buildDirectory.file("reports/manifest-check/${variant.name}.txt"))
            }
        tasks.named("check") { dependsOn(check) }
    }
}

ktlint {
    version.set(libs.versions.ktlint.get())
    android.set(true)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":native:whisper"))
    implementation(project(":native:llama"))
    implementation(project(":vehicle:car"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
