import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.security.MessageDigest

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

// ----------------------------------------------------------------------------
// Verify the committed maestro-android driver APKs are fresh relative to source
// ----------------------------------------------------------------------------
// Pure file I/O — no Android Gradle Plugin loaded, no ANDROID_HOME required.
// Compares a sha256 of maestro-android source files against the sentinel that
// was written when copyMaestroAndroid last ran. If they don't match, the build
// fails with the exact command the contributor needs to run.
//
// This makes JVM-only consumers (maestro-cli, downstream worker builds, etc.)
// build cleanly without an Android SDK, while still preventing contributors
// from accidentally shipping stale committed APKs.
// ----------------------------------------------------------------------------

val maestroAndroidProjectDir = rootProject.file("maestro-android")
val maestroAndroidSourceTree = fileTree(maestroAndroidProjectDir) {
    include(
        "src/**/*.kt",
        "src/**/*.java",
        "src/**/*.xml",
        "src/**/*.aidl",
        "build.gradle.kts",
        "build.gradle",
        "proguard-rules.pro"
    )
    exclude("build/**", ".gradle/**")
}
val maestroAndroidSentinel = file("src/main/resources/maestro-android-source.sha256")

val checkAndroidApksFresh = tasks.register("checkAndroidApksFresh") {
    inputs.files(maestroAndroidSourceTree).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(maestroAndroidSentinel).withPathSensitivity(PathSensitivity.NONE)

    doLast {
        if (!maestroAndroidSentinel.exists()) {
            throw GradleException(
                "Missing maestro-android-source.sha256 sentinel.\n" +
                "Run: ./gradlew :maestro-android:assemble :maestro-android:assembleAndroidTest"
            )
        }
        val md = MessageDigest.getInstance("SHA-256")
        maestroAndroidSourceTree.files
            .sortedBy { it.relativeTo(maestroAndroidProjectDir).invariantSeparatorsPath }
            .forEach { f ->
                md.update(f.relativeTo(maestroAndroidProjectDir).invariantSeparatorsPath.toByteArray())
                md.update(0)
                md.update(f.readBytes())
                md.update(0)
            }
        val bytes = md.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        val computed = sb.toString()
        val stored = maestroAndroidSentinel.readText().trim()
        if (computed != stored) {
            throw GradleException(
                """
                |maestro-android source has changed since the committed driver APKs were
                |last regenerated.
                |
                |  Stored sentinel:    $stored
                |  Current source hash: $computed
                |
                |Run:
                |    ./gradlew :maestro-android:assemble :maestro-android:assembleAndroidTest
                |
                |Then commit the regenerated files (all three travel together):
                |    maestro-client/src/main/resources/maestro-app.apk
                |    maestro-client/src/main/resources/maestro-server.apk
                |    maestro-client/src/main/resources/maestro-android-source.sha256
                |""".trimMargin()
            )
        }
    }
}

tasks.named("processResources") {
    dependsOn(checkAndroidApksFresh)
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
    api(libs.square.okio.jvm)
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
