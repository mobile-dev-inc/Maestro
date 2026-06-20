import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    implementation(project(":maestro-orchestra"))
    implementation(project(":maestro-client"))
    implementation(project(":maestro-utils"))

    implementation(libs.google.truth)
    implementation(libs.square.okio)
    implementation(libs.clikt)
    implementation(libs.dadb)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(libs.wiremock.jre8)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// --- Driver Conformance Harness (device-backed; NOT part of test/check) ---
// The fixture APK is a gitignored build artifact: building the fixture (an Android app
// under conformance-fixtures/native) copies it into src/main/resources. Only this task
// depends on that build — normal `:maestro-test:test`/`build` never triggers AGP.
tasks.register<JavaExec>("driverConformance") {
    group = "verification"
    description = "Run the driver conformance harness (device-backed; NOT part of check/test)."
    mainClass.set("maestro.conformance.cli.ConformanceCliKt")
    dependsOn(":maestro-test:conformance-fixtures:native:copyNativeFixture")
    dependsOn(":maestro-test:conformance-fixtures:compose:copyComposeFixture")
    // Include src/main/resources directly so the freshly-copied (gitignored) APK is on the
    // classpath even when processResources ran before it existed (fresh checkout).
    classpath = sourceSets["main"].runtimeClasspath + files("src/main/resources")
}
