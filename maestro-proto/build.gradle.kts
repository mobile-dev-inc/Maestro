
plugins {
    id("maven-publish")
    java
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    publishToMavenCentral(true)
    signAllPublications()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.named<Jar>("jar") {
    from("src/main/proto/maestro_android.proto")
}
