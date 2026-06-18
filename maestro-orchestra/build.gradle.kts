plugins {
    alias(libs.plugins.maestro.jvm.library)
    alias(libs.plugins.maestro.publish)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":maestro-orchestra-models"))
    implementation(project(":maestro-client"))
    api(project(":maestro-ai"))
    api(project(":maestro-utils"))

    api(libs.square.okio)
    api(libs.jackson.core.databind)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.dataformat.yaml)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.datafaker)
    implementation(libs.kotlin.result)
    implementation(libs.dd.plist)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(libs.google.truth)
    testImplementation(libs.mockk)
}

tasks.named<Test>("test") {
    environment.put("PROJECT_DIR", projectDir.absolutePath)
}
