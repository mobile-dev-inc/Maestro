plugins {
    alias(libs.plugins.maestro.jvm.library)
}

dependencies {
    implementation(project(":maestro-orchestra"))
    implementation(project(":maestro-client"))
    implementation(project(":maestro-utils"))

    implementation(libs.google.truth)
    implementation(libs.square.okio)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(libs.wiremock.jre8)
}
