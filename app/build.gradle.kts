plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.github.ardaulas.earshot.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ardaulas.earshot"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
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
 * SR-20: fails if the merged manifest (after every library's manifest is merged in) requests INTERNET
 * or exports any component other than the launcher activity (TH-2, TH-6).
 */
abstract class CheckMergedManifest : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowedExported: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val android = "http://schemas.android.com/apk/res/android"
        val doc =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest.get().asFile)
        val problems = mutableListOf<String>()
        val permissions = doc.getElementsByTagName("uses-permission")
        for (i in 0 until permissions.length) {
            val name = (permissions.item(i) as org.w3c.dom.Element).getAttributeNS(android, "name")
            if (name == "android.permission.INTERNET") problems += "requests INTERNET"
        }
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as org.w3c.dom.Element
                val name = e.getAttributeNS(android, "name")
                if (e.getAttributeNS(android, "exported") == "true" && name !in allowedExported.get()) {
                    problems += "exports $tag $name"
                }
            }
        }
        val text = if (problems.isEmpty()) "OK" else problems.joinToString("\n")
        report.get().asFile.writeText(text + "\n")
        if (problems.isNotEmpty()) throw GradleException("Merged manifest check failed:\n$text")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyModelManifest, CopyModelManifest::outputDir)
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check =
            tasks.register<CheckMergedManifest>("verify${name}MergedManifest") {
                mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
                allowedExported.set(listOf("io.github.ardaulas.earshot.app.MainActivity"))
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
