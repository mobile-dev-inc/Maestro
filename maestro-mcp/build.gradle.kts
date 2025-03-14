import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("maestro.mcp.MaestroMCPServer")
}

dependencies {
    // MCP Kotlin SDK - version 0.3.0
    implementation("io.modelcontextprotocol:kotlin-sdk:0.3.0")
    
    // Maestro CLI
    implementation(project(":maestro-cli"))
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    
    // CLI parsing
    implementation("info.picocli:picocli:4.7.5")
    
    // Logging - proper implementation with file appender
    implementation("ch.qos.logback:logback-classic:1.4.11")
    implementation("org.slf4j:slf4j-api:2.0.9")
    
    // Testing
    testImplementation(kotlin("test"))
}

// Create distribution with proper executable name
tasks.named<Sync>("installDist") {
    into(layout.buildDirectory.dir("install/maestro-mcp"))
}

// Make sure the distribution includes the right scripts
tasks.named<CreateStartScripts>("startScripts") {
    applicationName = "maestro-mcp"
}
