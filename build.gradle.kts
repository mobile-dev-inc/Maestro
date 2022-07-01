group = "dev.mobile"
version = "1.0.0"

extra["isReleaseVersion"] = !version.toString().endsWith("SNAPSHOT")

buildscript {
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:4.1.3")
    }
}

plugins {
    id("com.vanniktech.maven.publish") version "0.19.0"
    id("io.gitlab.arturbosch.detekt") version "1.21.0-RC2"
    id("org.jetbrains.kotlin.jvm") version "1.5.31"

    `java-library`
    `maven-publish`

    signing
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.21.0-RC2")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = true
    config = files("${rootDir}/detekt.yml")
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks {
    javadoc {
        if (JavaVersion.current().isJava9Compatible) {
            (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
        }
    }

    clean {
        delete("${rootProject.buildDir}")
    }

    test {
        useJUnitPlatform()
    }
}

signing {
    setRequired({
        (project.extra["isReleaseVersion"] as Boolean) && gradle.taskGraph.hasTask("publish")
    })
}

publishing {
    publications {
        create<MavenPublication>("main") {
            from(components["java"])

            afterEvaluate {
                artifactId = tasks.jar.get().archiveBaseName.get()
            }

            pom {
                name.set("Conductor")
                description.set("A library to automate in-app navigation")
                url.set("https://www.mobile.dev/")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("mobile-dev-inc")
                        name.set("mobile.dev Inc.")
                        email.set("support@mobile.dev")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/mobile-dev-inc/conductor.git")
                    developerConnection.set("scm:git:ssh://github.com/mobile-dev-inc/conductor.git")
                    url.set("https://www.mobile.dev/")
                }
            }
        }
    }
}
