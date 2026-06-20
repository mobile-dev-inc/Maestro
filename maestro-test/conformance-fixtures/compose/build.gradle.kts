plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

android {
    namespace = "dev.mobile.maestro.fixture.compose"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mobile.maestro.fixture.compose"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}

val copyComposeFixture by tasks.registering(Copy::class) {
    dependsOn("assembleDebug")
    from("build/outputs/apk/debug/compose-debug.apk")
    into("${rootDir}/maestro-test/src/main/resources")
    rename { "compose-fixture.apk" }
}
tasks.named("assemble") { finalizedBy(copyComposeFixture) }
