import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.mavenPublish)
}

// device-core pin. The committed `devicecore.version` records which device-core build this Maestro
// commit works with — the integration pin. A gitignored `devicecore.version.local` overrides it
// during local iteration (the sync script writes that one). Local wins when present, else the
// committed pin, else fail fast.
val devicecoreVersion: String =
    listOf("devicecore.version.local", "devicecore.version")
        .map(rootProject::file)
        .firstOrNull { it.exists() }
        ?.readText()?.trim()
        .orEmpty()
        .ifEmpty { error("device-core version not set — run ./scripts/devicecore-sync.sh (see DEVICE_CORE_INTEGRATION.md)") }

dependencies {
    api(project(":maestro-orchestra-models"))
    implementation(project(":maestro-client"))
    api(project(":maestro-ai"))
    api(project(":maestro-utils"))

    api(libs.square.okio)
    api(libs.jackson.core.databind)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.dataformat.yaml)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.datafaker)
    implementation(libs.kotlin.result)
    implementation(libs.dd.plist)

    // device-core's api surface, resolved from mavenLocal (see settings.gradle.kts). Version comes
    // from the committed devicecore.version pin, or a gitignored devicecore.version.local override.
    implementation("dev.mobile.devicecore:implementation:$devicecoreVersion")
    runtimeOnly("dev.mobile.devicecore:drivers-core:$devicecoreVersion")

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(libs.google.truth)
    testImplementation(libs.mockk)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.named("compileKotlin", KotlinCompilationTask::class.java) {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjdk-release=17")
    }
}

mavenPublishing {
    publishToMavenCentral(true)
    signAllPublications()
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    environment.put("PROJECT_DIR", projectDir.absolutePath)
}
