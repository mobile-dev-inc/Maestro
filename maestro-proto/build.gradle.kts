plugins {
    java
    alias(libs.plugins.maestro.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.named<Jar>("jar") {
    from("src/main/proto/maestro_android.proto")
}
