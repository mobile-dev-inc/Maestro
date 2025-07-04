import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.detekt)
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

detekt {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = true
    config = files("${rootDir}/detekt.yml")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("com.squareup.okhttp3:okhttp:4.9.2")
    implementation("com.google.protobuf:protobuf-java:3.17.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("junit:junit:4.13.2")
}

version = "1.0.0"

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

publishing {
    publications {
        create<MavenPublication>("myMavenPublication") {
            from(components["java"])
            groupId = "com.example"
            artifactId = "maestro"
            version = "1.0.0"

            pom {
                name.set("Maestro Project")
                description.set("A Kotlin project for automating tasks in the Maestro system")
                url.set("https://github.com/Franlexa/maestro")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            url = uri("file://${buildDir}/repo") // Local repo for testing
        }
    }
}

tasks.register("cleanBuild") {
    dependsOn("clean", "build")
    group = "Build"
    description = "Cleans and rebuilds the project."
}

