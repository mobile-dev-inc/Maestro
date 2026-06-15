plugins {
    alias(libs.plugins.maestro.jvm.library)
    alias(libs.plugins.maestro.publish)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.square.okio)

    api(libs.selenium)
    api(libs.selenium.devtools)
    implementation(libs.jcodec)
    implementation(libs.jcodec.awt)

    // Ktor
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.serial.json)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.google.truth)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.named<Test>("test") {
    environment.put("PROJECT_DIR", projectDir.absolutePath)
}
