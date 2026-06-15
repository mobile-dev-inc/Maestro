plugins {
    alias(libs.plugins.maestro.jvm.library)
    alias(libs.plugins.maestro.publish)
}

dependencies {
    api(libs.square.okio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.square.okhttp)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.observation)

    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.google.truth)
}
