import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

dependencies {
    implementation(project(":maestro-utils"))
    api(libs.dadb)
    api(libs.kotlinx.coroutines.core)
    api(libs.slf4j)
    api(libs.jackson.module.kotlin)
    
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.google.truth)
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

mavenPublishing {
    publishToMavenCentral(SonatypeHost.S01)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}