plugins {
    kotlin("jvm")
    `java-library`
}

// Plugin metadata configuration
ext {
    set("pluginName", project.findProperty("pluginName") ?: "Maestro Example Plugins")
    set("pluginVersion", project.findProperty("pluginVersion") ?: project.version)
    set("pluginDescription", project.findProperty("pluginDescription") ?: "Example plugins for Maestro automation framework")
    set("pluginJarName", project.findProperty("pluginJarName") ?: "maestro-example-plugins")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Plugin API dependencies
    implementation(project(":maestro-plugins"))
    implementation(project(":maestro-orchestra-models"))
    implementation(project(":maestro-orchestra"))
    
    // Jackson for JSON/YAML processing
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
}

tasks.test {
    useJUnitPlatform()
}

// Task to build a standalone plugin JAR
val pluginJar by tasks.registering(Jar::class) {
    archiveBaseName.set(ext.get("pluginJarName") as String)
    archiveClassifier.set("plugin")
    from(sourceSets.main.get().output)
    
    // Include dependencies in the JAR
    from(configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) })
    
    // Ensure META-INF/services files are preserved
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    manifest {
        attributes(
            "Plugin-Name" to (ext.get("pluginName") as String),
            "Plugin-Version" to (ext.get("pluginVersion") as String),
            "Plugin-Description" to (ext.get("pluginDescription") as String)
        )
    }
}

// Print plugin information when building
val pluginInfo by tasks.registering {
    doLast {
        println("Plugin Configuration:")
        println("  Name: ${ext.get("pluginName")}")
        println("  Version: ${ext.get("pluginVersion")}")
        println("  Description: ${ext.get("pluginDescription")}")
        println("  JAR Name: ${ext.get("pluginJarName")}")
        println("  Output: build/libs/${ext.get("pluginJarName")}-plugin.jar")
    }
}

// Make pluginJar depend on pluginInfo for visibility
pluginJar {
    dependsOn(pluginInfo)
}
