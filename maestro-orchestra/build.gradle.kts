import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.mavenPublish)
}

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

    // device-core's prototype api surface, resolved local-first from ~/.m2 (see settings.gradle.kts),
    // falling back to the private GitHub Package. drivers-core arrives transitively via prototype's POM.
    // Pinned to an immutable, commit-addressed device-core build (device-core #138): the version is
    // `0.1.0-<git short-12 sha>` of the device-core HEAD this Maestro build was validated against, so we
    // adopt a device-core build intentionally rather than floating on a -SNAPSHOT that changes underneath
    // us. To move to a newer device-core: publish it (`./gradlew :drivers-core:publishToMavenLocal
    // :prototype:publishToMavenLocal -x provisionBinaries`), then bump the sha here.
    implementation("dev.mobile.devicecore:prototype:0.1.0-1c9bdd054852-dirty")

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
