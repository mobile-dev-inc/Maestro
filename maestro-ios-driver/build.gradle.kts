import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.S01)
}

dependencies {
    implementation(project(":maestro-utils"))
    implementation(libs.commons.io)

    api(libs.square.okhttp)
    api(libs.square.okhttp.logs)
    api(libs.jackson.module.kotlin)
    api(libs.jarchivelib)
    api(libs.kotlin.result)
    api(libs.slf4j)
    api(libs.appdirs)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.google.truth)
    testImplementation(libs.mockk)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.named("compileKotlin", KotlinCompilationTask::class.java) {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjdk-release=1.8")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
