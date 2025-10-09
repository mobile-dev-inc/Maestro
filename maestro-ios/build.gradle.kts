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

    implementation(libs.logging.sl4j)
    implementation(libs.logging.api)
    implementation(libs.logging.layout.template)
    implementation(libs.log4j.core)

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
}
