import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.maestro.jvm.library)
    alias(libs.plugins.maestro.publish)
}

dependencies {
    implementation(project(":maestro-utils"))
    implementation(libs.commons.io)

    api(libs.square.okhttp)
    api(libs.square.okio.jvm)
    api(libs.square.okhttp.logs)
    api(libs.jackson.module.kotlin)
    api(libs.jarchivelib)
    api(libs.kotlin.result)

    api(libs.logging.sl4j)
    api(libs.logging.api)
    api(libs.logging.layout.template)
    api(libs.log4j.core)

    api(libs.appdirs)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.google.truth)
    testImplementation(libs.mockk)
}

tasks.register<Exec>("buildIosDriver") {
    onlyIf {
        OperatingSystem.current().isMacOsX &&
            ProcessBuilder("which", "xcodebuild").start().waitFor() == 0
    }

    inputs.dir(rootProject.file("maestro-ios-xctest-runner"))
        .withPropertyName("iosXctestRunnerSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(projectDir.resolve("src/main/resources/driver-iPhoneSimulator"))
        .withPropertyName("iosSimulatorDriver")
    outputs.dir(projectDir.resolve("src/main/resources/driver-iphoneos"))
        .withPropertyName("iosDeviceDriver")

    workingDir = rootProject.projectDir
    commandLine("sh", "maestro-ios-xctest-runner/build-maestro-ios-runner-all.sh")
}

tasks.named("processResources") {
    dependsOn("buildIosDriver")
}

tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn("buildIosDriver")
}
