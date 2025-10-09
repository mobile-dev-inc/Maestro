import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    publishToMavenCentral(true)
    signAllPublications()
}

dependencies {
    implementation(project(":maestro-utils"))
    implementation(libs.commons.io)

    api(libs.square.okhttp)
    api(libs.square.okhttp.logs)
    api(libs.jackson.module.kotlin)
    api(libs.jarchivelib)
    api(libs.kotlin.result)

    api(libs.logging.sl4j)
    api(libs.logging.api)
    api(libs.logging.layout.template)
    api(libs.log4j.core)

    api(libs.appdirs)

    testImplementation(libs.junit.jupiter.api)
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

tasks.named<Test>("test") {
    useJUnitPlatform()
}
