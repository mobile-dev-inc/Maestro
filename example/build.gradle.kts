import org.gradle.internal.impldep.org.junit.experimental.categories.Categories.CategoryFilter.include
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    applicationName = "maestro-example"
    mainClass.set("MainKt")
}

// Set both Java and Kotlin compatibility to Java 8
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

kotlin {
    jvmToolchain(8)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":maestro-utils"))
    implementation(project(":maestro-client"))
    implementation(project(":maestro-orchestra"))
    implementation(project(":maestro-ios"))
}
