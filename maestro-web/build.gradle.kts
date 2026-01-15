import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.mavenPublish)
}

dependencies {
    implementation(libs.square.okio)

    api(libs.selenium)
    api(libs.selenium.devtools)
    implementation(libs.jcodec)
    implementation(libs.jcodec.awt)

    // Ktor
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.serial.json)
    implementation(libs.ktor.client.content.negotiation)
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
