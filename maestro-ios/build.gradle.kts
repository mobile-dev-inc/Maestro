import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

sourceSets {
    main {
        java {
            srcDirs(
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/kotlin",
            )
        }
    }
}

dependencies {
    implementation(project(":maestro-utils"))
    implementation(project(":maestro-ios-driver"))

    implementation(libs.kotlin.result)
    implementation(libs.slf4j)
    implementation(libs.square.okio)
    api(libs.google.gson)
    api(libs.square.okhttp)
    api(libs.appdirs)
    api(libs.jackson.module.kotlin)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
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
