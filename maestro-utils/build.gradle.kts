import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.protobuf)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.googleProtobuf.get()}"
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("kotlin")
            }
        }
    }
}

dependencies {
    protobuf(project(":maestro-proto"))
    
    api(libs.square.okio)
    api(libs.grpc.stub)
    api(libs.google.protobuf.kotlin)
    implementation(libs.square.okhttp)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.observation)

    testImplementation(libs.mockk)
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

kotlin.sourceSets.all {
    // Prevent build warnings for grpc's generated opt-in code
    languageSettings.optIn("kotlin.RequiresOptIn")
}

sourceSets {
    main {
        java {
            srcDirs(
                "build/generated/source/proto/main/kotlin"
            )
        }
    }
}

tasks.named("compileKotlin", KotlinCompilationTask::class.java) {
    dependsOn("generateProto")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
