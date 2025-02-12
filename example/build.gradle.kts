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

tasks.named("compileKotlin", KotlinCompilationTask::class.java) {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjdk-release=1.8")
    }
}

dependencies {
    implementation(project(":maestro-utils"))
    implementation(project(":maestro-client"))
    implementation(project(":maestro-orchestra"))
    implementation(project(":maestro-ios"))
}

