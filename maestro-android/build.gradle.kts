import org.jetbrains.kotlin.config.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
                create("grpc") { option("lite") }
            }

            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

kotlin.sourceSets.configureEach {
    // Prevent build warnings for grpc's generated opt-in code
    languageSettings.optIn("kotlin.RequiresOptIn")
}

android {
    namespace = "dev.mobile.maestro"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.mobile.maestro"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        named("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        named("debug") {
            storeFile = file("../debug.keystore")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

tasks.register<Copy>("copyMaestroAndroid") {
    val maestroAndroidApkPath = "outputs/apk/debug/maestro-android-debug.apk"
    val maestroAndroidApkDest = "../../maestro-client/src/main/resources"
    val maestroAndroidApkDestPath = "../../maestro-client/src/main/resources/maestro-android-debug.apk"

    from(layout.buildDirectory.dir(maestroAndroidApkPath))
    into(layout.buildDirectory.file(maestroAndroidApkDest))

    doLast {
        if (!layout.buildDirectory.file(maestroAndroidApkDestPath).get().asFile.exists()) {
            throw GradleException("Error: Input source for copyMaestroAndroid doesn't exist")
        }

        File("./maestro-client/src/main/resources/maestro-android-debug.apk").renameTo(File("./maestro-client/src/main/resources/maestro-app.apk"))
    }
}

tasks.register<Copy>("copyMaestroServer") {
    val maestroServerApkPath = "outputs/apk/androidTest/debug/maestro-android-debug-androidTest.apk"
    val maestroServerApkDest = "../../maestro-client/src/main/resources"
    val maestroServerApkDestPath = "../../maestro-client/src/main/resources/maestro-android-debug-androidTest.apk"

    from(layout.buildDirectory.dir(maestroServerApkPath))
    into(layout.buildDirectory.file(maestroServerApkDest))

    doLast {
        if (!layout.buildDirectory.file(maestroServerApkDestPath).get().asFile.exists()) {
            throw GradleException("Error: Input source for copyMaestroServer doesn't exist")
        }

        File("./maestro-client/src/main/resources/maestro-android-debug-androidTest.apk").renameTo(File("./maestro-client/src/main/resources/maestro-server.apk"))
    }
}

tasks.named("assemble") {
    // lint.enabled = false
    // lintVitalRelease.enabled = false
    finalizedBy("copyMaestroAndroid")
}

tasks.named("assembleAndroidTest") {
    // lint.enabled = false
    // lintVitalRelease.enabled = false
    finalizedBy("copyMaestroServer")
}

sourceSets {
    create("generated") {
        java {
            srcDirs(
                "build/generated/source/proto/main/grpc",
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/kotlin",
            )
        }
    }
}

dependencies {
    protobuf(project(":maestro-proto"))

    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.okhttp)
    implementation(libs.google.protobuf.kotlin.lite)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serial.gson)

    implementation(libs.commons.lang3)
    implementation(libs.hiddenapibypass)

    androidTestImplementation(libs.gmsLocation)
    implementation(libs.gmsLocation)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.kotlin.retry)
}
