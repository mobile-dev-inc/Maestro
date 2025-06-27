import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("maven-publish")
    java
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.S01)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.named<Jar>("jar") {
    from("src/main/proto/maestro_android.proto")
}
