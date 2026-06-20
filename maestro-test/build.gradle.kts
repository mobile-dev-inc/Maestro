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

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(libs.wiremock.jre8)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// --- Driver Conformance Harness (excluded from test/check) ---
sourceSets {
    create("conformance") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

val conformanceImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["implementation"])
}

dependencies {
    conformanceImplementation(project(":maestro-client"))
    conformanceImplementation(libs.clikt)
    conformanceImplementation(libs.dadb)
    conformanceImplementation(libs.junit.jupiter.api)
    conformanceImplementation(libs.google.truth)
    "conformanceRuntimeOnly"(libs.junit.jupiter.engine)
}

tasks.register<JavaExec>("driverConformance") {
    group = "verification"
    description = "Run the driver conformance harness (device-backed; NOT part of check/test)."
    mainClass.set("maestro.conformance.cli.ConformanceCliKt")
    classpath = sourceSets["conformance"].runtimeClasspath
}

tasks.register<Test>("conformanceTest") {
    description = "Unit tests for conformance harness logic (no device)."
    testClassesDirs = sourceSets["conformance"].output.classesDirs
    classpath = sourceSets["conformance"].runtimeClasspath
    useJUnitPlatform()
}
