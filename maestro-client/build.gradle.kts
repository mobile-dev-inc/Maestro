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

    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }

    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }

            task.builtins {
                create("kotlin")
            }
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn("generateProto")
}

tasks.named("processResources") {
    dependsOn(":maestro-android:copyMaestroAndroid")
}

tasks.whenTaskAdded {
    if (name == "sourcesJar" && this is Jar) {
        dependsOn(":maestro-android:copyMaestroAndroid")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

kotlin.sourceSets.all {
    // Prevent build warnings for grpc's generated opt-in code
    languageSettings.optIn("kotlin.RequiresOptIn")
}

sourceSets {
    main {
        java {
            srcDirs(
                "build/generated/source/proto/main/grpc",
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/kotlin"
            )
        }
    }
}

dependencies {
    protobuf(project(":maestro-proto"))
    implementation(project(":maestro-utils"))
    implementation(project(":maestro-ios-driver"))

    api(libs.graaljs)
    api(libs.graaljsEngine)
    api(libs.graaljsLanguage)

    api(libs.grpc.kotlin.stub)
    api(libs.grpc.stub)
    api(libs.grpc.netty)
    api(libs.grpc.protobuf)
    api(libs.grpc.okhttp)
    api(libs.google.protobuf.kotlin)
    api(libs.kotlin.result)
    api(libs.dadb)
    api(libs.square.okio)
    api(libs.image.comparison)
    api(libs.mozilla.rhino)
    api(libs.square.okhttp)
    api(libs.jarchivelib)
    api(libs.jackson.core.databind)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.dataformat.yaml)
    api(libs.jackson.dataformat.xml)
    api(libs.apk.parser)

    implementation(project(":maestro-ios"))
    implementation(project(":maestro-web"))
    implementation(libs.google.findbugs)
    implementation(libs.axml)
    implementation(libs.selenium)
    implementation(libs.selenium.devtools)
    implementation(libs.jcodec)
    implementation(libs.datafaker)

    api(libs.logging.sl4j)
    api(libs.logging.api)
    api(libs.logging.layout.template)
    api(libs.log4j.core)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.google.truth)
    testImplementation(libs.square.mock.server)
    testImplementation(libs.junit.jupiter.params)
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
