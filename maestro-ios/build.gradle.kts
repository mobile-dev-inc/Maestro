plugins {
    alias(libs.plugins.maestro.jvm.library)
    alias(libs.plugins.maestro.publish)
}

sourceSets {
    main {
        java {
            srcDirs(
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/kotlin",
            )
        }
    }
}

dependencies {
    implementation(project(":maestro-utils"))
    implementation(project(":maestro-ios-driver"))

    implementation(libs.kotlin.result)

    implementation(libs.logging.sl4j)
    implementation(libs.logging.api)
    implementation(libs.logging.layout.template)
    implementation(libs.log4j.core)

    implementation(libs.square.okio)
    implementation(libs.square.okio.jvm)
    api(libs.google.gson)
    api(libs.square.okhttp)
    api(libs.appdirs)
    api(libs.jackson.module.kotlin)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.google.truth)
}
