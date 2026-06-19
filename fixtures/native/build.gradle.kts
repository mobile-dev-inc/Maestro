plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.mobile.maestro.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mobile.maestro.fixture"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
    kotlinOptions { jvmTarget = "1.8" }
}

// No extra deps: the fixture uses only platform APIs (android.app.Activity,
// android.util.Log) + org.json, all in the Android SDK.

val copyNativeFixture by tasks.registering(Copy::class) {
    dependsOn("assembleDebug")
    from("build/outputs/apk/debug/native-debug.apk")
    into("${rootDir}/maestro-client/src/conformance/resources")
    rename { "native-fixture.apk" }
}
tasks.named("assemble") { finalizedBy(copyNativeFixture) }
