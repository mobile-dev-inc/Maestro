plugins {
    application
    alias(libs.plugins.maestro.jvm.library)
    alias(libs.plugins.maestro.publish)
    alias(libs.plugins.kotlin.serialization)
}

application {
    applicationName = "maestro-ai-demo"
    mainClass.set("maestro.ai.DemoAppKt")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "maestro.ai.DemoAppKt"
    }
}

dependencies {
    api(libs.kotlin.result)
    api(libs.square.okio)
    api(libs.square.okio.jvm)
    api(libs.square.okhttp)

    api(libs.logging.sl4j)
    api(libs.logging.api)
    api(libs.logging.layout.template)
    api(libs.log4j.core)


    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.serial.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.clikt)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.google.truth)
    testImplementation(libs.square.mock.server)
    testImplementation(libs.junit.jupiter.params)
}
