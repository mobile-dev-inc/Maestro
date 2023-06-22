import java.util.Properties

plugins {
    kotlin("jvm")
    application
    id("org.jreleaser") version "1.0.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "dev.mobile"

val CLI_VERSION: String by project

application {
    applicationName = "maestro"
    mainClass.set("maestro.cli.AppKt")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "maestro.cli.AppKt"
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir
}

dependencies {
    implementation(project(path = ":maestro-utils"))
    annotationProcessor(libs.picocli.codegen)

    implementation(project(":maestro-client"))
    implementation(project(":maestro-orchestra"))
    implementation(project(":maestro-ios"))
    implementation(project(":maestro-ios-driver"))
    implementation(project(":maestro-studio:server"))
    implementation(libs.dadb)
    implementation(libs.picocli)
    implementation(libs.jackson.core.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.dataformat.xml)
    implementation(libs.jansi)
    implementation(libs.square.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.jarchivelib)
    implementation(libs.commons.codec)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.google.truth)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.create("createProperties") {
    dependsOn("processResources")

    doLast {
        File("$buildDir/resources/main/version.properties").writer().use { w ->
            val p = Properties()
            p["version"] = CLI_VERSION
            p.store(w, null)
        }
    }
}

tasks.named("classes") {
    dependsOn("createProperties")
}

tasks.named<Zip>("distZip") {
    archiveFileName.set("maestro.zip")
}

tasks.named<Tar>("distTar") {
    archiveFileName.set("maestro.tar")
}

jreleaser {
    version = CLI_VERSION
    gitRootSearch.set(true)

    project {
        website.set("https://maestro.mobile.dev")
        description.set("Maestro CLI")
        authors.set(listOf("Dmitry Zaytsev", "Amanjeet Singh", "Leland Takamine", "Arthur Saveliev", "Axel Niklasson", "Berik Visschers"))
        license.set("Apache-2.0")
    }

    release {
        github {
            owner.set("mobile-dev-inc")
            name.set("maestro")
            tagName.set("cli-$CLI_VERSION")
            releaseName.set("CLI $CLI_VERSION")
            overwrite.set(true)
        }
    }

    distributions {
        create("maestro") {
            artifact {
                setPath("build/distributions/maestro.zip")
            }
            brew {
                extraProperties.put("skipJava", "true")
                setActive("RELEASE")
                formulaName.set("Maestro")

                repoTap {
                    owner.set("mobile-dev-inc")
                    name.set("homebrew-tap")
                }
            }
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
