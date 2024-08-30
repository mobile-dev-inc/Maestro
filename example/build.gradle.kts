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
    implementation("dev.mobile:maestro-client:1.38.1")
    implementation("dev.mobile:maestro-orchestra:1.38.1")
    implementation("dev.mobile:maestro-ios:1.38.1")
}

