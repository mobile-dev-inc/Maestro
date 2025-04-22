import org.jreleaser.model.Active.ALWAYS
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jreleaser.model.Stereotype
import java.util.Properties

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.shadow)
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
    // Include the driver source directly
    from("../maestro-ios-xctest-runner") {
        into("driver/ios")
        include(
            "maestro-driver-ios/**",
            "maestro-driver-iosUITests/**",
            "maestro-driver-ios.xcodeproj/**",
        )
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir
}

tasks.named<CreateStartScripts>("startScripts") {
    classpath = files("$buildDir/libs/*")
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
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jansi)
    implementation(libs.jcodec)
    implementation(libs.jcodec.awt)
    implementation(libs.square.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.jarchivelib)
    implementation(libs.commons.codec)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.html)
    implementation(libs.skiko.macos.arm64)
    implementation(libs.skiko.macos.x64)
    implementation(libs.skiko.linux.arm64)
    implementation(libs.skiko.linux.x64)
    implementation(libs.skiko.windows.arm64)
    implementation(libs.skiko.windows.x64)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.google.truth)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.named("compileKotlin", KotlinCompilationTask::class.java) {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjdk-release=1.8")
    }
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

tasks.register<Copy>("createTestResources") {
    from("../maestro-ios-xctest-runner") {
        into("driver/ios")
        include(
            "maestro-driver-ios/**",
            "maestro-driver-iosUITests/**",
            "maestro-driver-ios.xcodeproj/**"
        )
    }
    into(layout.buildDirectory.dir("resources/test"))
}

tasks.named("classes") {
    dependsOn("createTestResources")
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
        name.set("Maestro CLI")
        description.set("The easiest way to automate UI testing for your mobile app")
        links {
            homepage.set("https://maestro.mobile.dev")
            bugTracker.set("https://github.com/mobile-dev-inc/maestro/issues")
        }
        authors.set(listOf("Dmitry Zaytsev", "Amanjeet Singh", "Leland Takamine", "Arthur Saveliev", "Axel Niklasson", "Berik Visschers"))
        license.set("Apache-2.0")
        copyright.set("mobile.dev 2024")
    }

    distributions {
        create("maestro") {
            stereotype.set(Stereotype.CLI)

            executable {
                name.set("maestro")
            }

            artifact {
                setPath("build/distributions/maestro.zip")
            }

            release {
                github {
                    repoOwner.set("mobile-dev-inc")
                    name.set("maestro")
                    tagName.set("cli-$CLI_VERSION")
                    releaseName.set("CLI $CLI_VERSION")
                    overwrite.set(true)

                    changelog {
                        // GitHub removes dots Markdown headers (1.37.5 becomes 1375)
                        extraProperties.put("versionHeader", CLI_VERSION.replace(".", ""))

                        formatted.set(ALWAYS)
                        content.set("""
                            [See changelog in the CHANGELOG.md file][link]

                            [link]: https://github.com/mobile-dev-inc/maestro/blob/main/CHANGELOG.md#{{changelogVersionHeader}}
                        """.trimIndent()
                        )
                    }
                }
            }
        }
    }

    packagers {
        brew {
            setActive("RELEASE")
            extraProperties.put("skipJava", "true")
            formulaName.set("Maestro")

            // The default template path
            templateDirectory.set(file("src/jreleaser/distributions/maestro/brew"))

            repoTap {
                repoOwner.set("mobile-dev-inc")
                name.set("homebrew-tap")
            }

            dependencies {
                dependency("openjdk")
            }
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
