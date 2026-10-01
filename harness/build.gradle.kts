// The regression harness: runs a labelled test set through the same rules, language-model fallback
// and policy as the app, on the development host, and gates changes against a baseline. Plain JVM, so
// the reference (text-only) run needs no models, NDK or emulator and runs in CI.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

ktlint {
    version.set(libs.versions.ktlint.get())
}

application {
    mainClass.set("io.github.ardaulas.earshot.harness.MainKt")
    applicationName = "harness"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.snakeyaml.engine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}

// Paths on the command line (--suite testset/car, --out reports/x) are relative to the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
