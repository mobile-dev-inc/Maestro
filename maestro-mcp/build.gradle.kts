import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("maestro.mcp.MaestroMCPServer")
    
    // Add system property to suppress SLF4J warning messages
    applicationDefaultJvmArgs = listOf(
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
        "-Dorg.slf4j.simpleLogger.log.org.slf4j.LoggerFactory=off"
    )
}


dependencies {
    // MCP Kotlin SDK - version 0.3.0
    implementation("io.modelcontextprotocol:kotlin-sdk:0.3.0")
    
    // Maestro CLI
    implementation(project(":maestro-cli"))
    
    // CLI parsing
    implementation("info.picocli:picocli:4.7.5")
    
    // Logging - explicit configuration to silence warnings
    implementation(libs.slf4j)
    implementation(libs.logback)
    implementation("org.slf4j:slf4j-nop:2.0.9") // NOP binding to suppress warnings
    
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
